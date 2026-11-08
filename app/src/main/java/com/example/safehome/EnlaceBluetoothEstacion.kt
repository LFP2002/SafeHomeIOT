package com.example.safehome

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cliente SPP, sin discovery, persistencia ni dependencia de Firebase.
 * Cada conexión tiene una generación: el trabajo anterior nunca publica en la siguiente. */
@SuppressLint("MissingPermission") // Todos los accesos comprueban CONNECT y capturan revocación.
class EnlaceBluetoothEstacion(context: Context) {
    private val contexto = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val conexiones = Mutex()
    private val escrituras = Mutex()
    private val _estado = MutableStateFlow(EstadoHardwareSafeHome())
    val estado = _estado.asStateFlow()
    private val cambios = Channel<CambioHardware>(128)
    val cambiosConfirmados = cambios.receiveAsFlow()
    private var timeoutOperacion: Job? = null
    private var generacion = 0L
    private var secuencia = 0L
    private var tarea: Job? = null
    private var recursos: Recursos? = null
    private var pingPendiente: Long? = null
    private var destruido = false

    private class Recursos(val socket: BluetoothSocket) {
        @Volatile var entrada: InputStream? = null
        @Volatile var salida: OutputStream? = null
        fun cerrar() {
            // Cerrar socket desbloquea connect/read/write incluso si su coroutine fue cancelada.
            runCatching { socket.close() }
            runCatching { entrada?.close() }
            runCatching { salida?.close() }
        }
    }

    private fun adaptador(): BluetoothAdapter? = contexto.getSystemService(BluetoothManager::class.java)?.adapter
    private fun tienePermiso() = Build.VERSION.SDK_INT < 31 ||
        contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun disponibilidad(): ConexionEstacionIoT = when {
        adaptador() == null -> ConexionEstacionIoT.BLUETOOTH_NO_DISPONIBLE
        !tienePermiso() -> ConexionEstacionIoT.SIN_PERMISO
        adaptador()?.isEnabled != true -> ConexionEstacionIoT.BLUETOOTH_APAGADO
        else -> ConexionEstacionIoT.DESCONECTADO
    }

    private fun publicar(id: Long, cambio: (EstadoHardwareSafeHome) -> EstadoHardwareSafeHome) = synchronized(monitor) {
        if (!destruido && id == generacion) _estado.value = cambio(_estado.value)
    }

    fun obtenerDispositivosEmparejados() {
        val id = synchronized(monitor) { generacion }
        scope.launch {
            try {
                val disponible = disponibilidad()
                if (disponible != ConexionEstacionIoT.DESCONECTADO) {
                    terminar(id, disponible)
                    return@launch
                }
                val dispositivos = adaptador()?.bondedDevices.orEmpty().map { dispositivo: BluetoothDevice ->
                    DispositivoBluetoothVinculado(dispositivo.name ?: "Dispositivo sin nombre", dispositivo.address)
                }.sortedBy { it.nombre }
                publicar(id) {
                    it.copy(dispositivos = dispositivos, dispositivosConsultados = true,
                        conexion = if (it.conectado || it.conexion == ConexionEstacionIoT.CONECTANDO) it.conexion else disponible)
                }
            } catch (_: SecurityException) {
                terminar(id, ConexionEstacionIoT.SIN_PERMISO)
            }
        }
    }

    fun conectar(direccion: String) {
        synchronized(monitor) {
            if (destruido || _estado.value.conectado || _estado.value.conexion == ConexionEstacionIoT.CONECTANDO) return
            val id = ++generacion
            _estado.value = EstadoHardwareSafeHome(
                conexion = ConexionEstacionIoT.CONECTANDO, direccionBluetooth = direccion,
                dispositivos = _estado.value.dispositivos, dispositivosConsultados = _estado.value.dispositivosConsultados)
            tarea = scope.launch {
                conexiones.withLock {
                    var actuales: Recursos? = null
                    try {
                        val disponible = disponibilidad()
                        if (disponible != ConexionEstacionIoT.DESCONECTADO) {
                            terminar(id, disponible)
                            return@withLock
                        }
                        val dispositivo = adaptador()?.bondedDevices?.find { it.address == direccion }
                            ?: throw IOException("Dispositivo no emparejado")
                        actuales = Recursos(dispositivo.createRfcommSocketToServiceRecord(UUID_SPP))
                        val vigente = synchronized(monitor) {
                            if (id != generacion || destruido) false else { recursos = actuales; true }
                        }
                        if (!vigente) return@withLock
                        actuales.socket.connect()
                        actuales.entrada = actuales.socket.inputStream
                        actuales.salida = actuales.socket.outputStream
                        publicar(id) { it.copy(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO, nombreBluetooth = dispositivo.name) }
                        escribir(id, nuevoComando("HELLO", "APP_STATION"))
                        leer(id, actuales.entrada!!)
                        terminar(id, ConexionEstacionIoT.DESCONECTADO)
                    } catch (_: SecurityException) {
                        terminar(id, ConexionEstacionIoT.SIN_PERMISO)
                    } catch (_: IOException) {
                        terminar(id, ConexionEstacionIoT.ERROR, "No se pudo mantener la conexión Bluetooth. Revisa el dispositivo e inténtalo nuevamente.")
                    } finally {
                        actuales?.cerrar()
                    }
                }
            }
        }
    }

    private fun nuevoComando(dato: String, valor: String): MensajeSafeHome = synchronized(monitor) {
        // Secuencia exclusiva de hardware. Nunca emite Long.MAX_VALUE ni desborda.
        secuencia = if (secuencia >= Long.MAX_VALUE - 1) 1 else secuencia + 1
        MensajeSafeHome(secuencia, TipoMensajeSafeHome.CMD, dato, valor)
    }

    fun encenderSistemaIoT() = ejecutar(AccionHardware.ENCENDER)
    fun apagarSistemaIoT() = ejecutar(AccionHardware.APAGAR)
    fun reconocerAlarmaIoT() = ejecutar(AccionHardware.RECONOCER_ALARMA)

    private fun ejecutar(accion: AccionHardware) = synchronized(monitor) {
        val mensaje = nuevoComando(accion.dato, accion.valor)
        val siguiente = iniciarOperacionHardware(_estado.value, accion, mensaje.secuencia)
        if (destruido || siguiente.operacion?.secuencia != mensaje.secuencia) return
        val id = generacion
        _estado.value = siguiente
        timeoutOperacion?.cancel()
        timeoutOperacion = scope.launch {
            delay(4_000)
            var solicitar = false
            publicar(id) {
                val resultado = expirarOperacionHardware(it, mensaje.secuencia)
                solicitar = resultado.solicitarEstado
                resultado.estado
            }
            if (solicitar) escribir(id, nuevoComando("STATUS", "ALL"))
        }
        scope.launch { escribir(id, mensaje) }
        Unit
    }

    fun solicitarEstadoIoT() {
        val id = synchronized(monitor) {
            if (!_estado.value.verificado || destruido || _estado.value.operacion != null) return
            generacion
        }
        scope.launch { escribir(id, nuevoComando("STATUS", "ALL")) }
    }

    fun contextoVigente(id: Long): Boolean = synchronized(monitor) { id == generacion && !destruido }

    fun informarSincronizacion(id: Long, error: String?) = publicar(id) {
        // Un éxito posterior no oculta que pudo perderse un evento anterior.
        if (error != null) it.copy(errorSincronizacion = error) else it
    }

    fun enviar(mensaje: MensajeSafeHome) {
        val id = synchronized(monitor) { generacion }
        ProtocoloSafeHome.serializar(mensaje) // Rechaza errores del llamador antes de programar IO.
        scope.launch { escribir(id, mensaje) }
    }

    private suspend fun escribir(id: Long, mensaje: MensajeSafeHome) = escrituras.withLock {
        val salida = synchronized(monitor) {
            val esAccion = mensaje.tipo == TipoMensajeSafeHome.CMD && mensaje.dato in setOf("SYSTEM", "ALARM")
            if (id == generacion && !destruido && _estado.value.conectado &&
                (!esAccion || _estado.value.operacion?.secuencia == mensaje.secuencia)) recursos?.salida else null
        } ?: return@withLock
        try {
            if (!tienePermiso()) throw SecurityException()
            salida.write(ProtocoloSafeHome.serializar(mensaje).toByteArray(Charsets.UTF_8))
            salida.flush()
        } catch (_: SecurityException) {
            terminar(id, ConexionEstacionIoT.SIN_PERMISO)
        } catch (_: IOException) {
            terminar(id, ConexionEstacionIoT.ERROR, "No se pudo enviar el mensaje Bluetooth.")
        }
    }

    fun probarComunicacion() {
        synchronized(monitor) {
            if (!_estado.value.conectado || pingPendiente != null || destruido) return
            val id = generacion
            val mensaje = nuevoComando("PING", "1")
            pingPendiente = mensaje.secuencia
            _estado.value = _estado.value.copy(prueba = PruebaComunicacion.ESPERANDO)
            scope.launch {
                escribir(id, mensaje)
                delay(10_000)
                publicar(id) {
                    if (pingPendiente == mensaje.secuencia) {
                        pingPendiente = null
                        it.copy(prueba = PruebaComunicacion.SIN_RESPUESTA)
                    } else it
                }
            }
        }
    }

    private suspend fun leer(id: Long, entrada: InputStream) {
        // UTF-8: hasta cuatro bytes por carácter, más CR. Nunca usamos readLine sin límite.
        val bytes = ByteArray(ProtocoloSafeHome.MAXIMO_CARACTERES * 4 + 1)
        var usados = 0
        var descartando = false
        while (synchronized(monitor) { id == generacion && !destruido }) {
            val byte = entrada.read()
            if (byte == -1) return
            if (byte == 10) {
                if (!descartando) {
                    val mensaje = try {
                        val longitud = if (usados > 0 && bytes[usados - 1] == 13.toByte()) usados - 1 else usados
                        val linea = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, 0, longitud)).toString()
                        ProtocoloSafeHome.parsear(linea)
                    } catch (_: IllegalArgumentException) { null }
                    catch (_: java.nio.charset.CharacterCodingException) { null }
                    if (mensaje != null) recibir(id, mensaje)
                    else publicar(id) { it.copy(error = "Se descartó un mensaje Bluetooth inválido.") }
                }
                usados = 0
                descartando = false
            } else if (!descartando) {
                if (usados == bytes.size) {
                    descartando = true
                    publicar(id) { it.copy(error = "Se descartó un mensaje Bluetooth demasiado largo.") }
                } else bytes[usados++] = byte.toByte()
            }
        }
    }

    private suspend fun recibir(id: Long, mensaje: MensajeSafeHome) {
        var pedirEstado = false
        var rechazado = false
        publicar(id) { previo ->
            val resultado = procesarMensajeHardware(previo, mensaje)
            var siguiente = resultado.estado
            pedirEstado = resultado.solicitarEstado
            rechazado = siguiente.conexion == ConexionEstacionIoT.ERROR
            if (siguiente.operacion == null) timeoutOperacion?.cancel()
            // No observar transiciones mediante StateFlow: puede omitir estados intermedios de una ráfaga.
            if (resultado.evento != null || resultado.sistemaParaSincronizar != null) {
                if (cambios.trySend(CambioHardware(id, resultado.evento, resultado.sistemaParaSincronizar)).isFailure) {
                    siguiente = siguiente.copy(errorSincronizacion = "No se pudo registrar toda la actividad del hardware: cola llena.")
                }
            }
            // PING se confirma exclusivamente con ACK correlacionado, nunca por escribir al socket.
            if (mensaje.tipo == TipoMensajeSafeHome.ACK && mensaje.dato.toLongOrNull() == pingPendiente &&
                pingPendiente != null && mensaje.valor == "OK") {
                pingPendiente = null
                siguiente = siguiente.copy(prueba = PruebaComunicacion.RESPUESTA_VALIDA)
            }
            siguiente
        }
        if (rechazado) terminar(id, ConexionEstacionIoT.ERROR, "El dispositivo conectado no corresponde a una Estación SafeHome.")
        else if (pedirEstado) escribir(id, nuevoComando("STATUS", "ALL"))
    }

    private fun terminar(id: Long, conexion: ConexionEstacionIoT, error: String? = null) {
        synchronized(monitor) {
            if (id != generacion || destruido) return
            desconectarInterno(false)
            _estado.value = _estado.value.copy(conexion = conexion, error = error ?: _estado.value.error,
                dispositivos = if (conexion == ConexionEstacionIoT.SIN_PERMISO) emptyList() else _estado.value.dispositivos)
        }
    }

    fun desconectar(olvidarDispositivo: Boolean = false) = synchronized(monitor) {
        desconectarInterno(olvidarDispositivo)
    }

    private fun desconectarInterno(olvidar: Boolean) {
        generacion++
        tarea?.cancel()
        tarea = null
        val anteriores = recursos
        recursos = null
        pingPendiente = null
        timeoutOperacion?.cancel()
        timeoutOperacion = null
        val previo = _estado.value
        _estado.value = if (olvidar) EstadoHardwareSafeHome() else invalidarEstadoHardware(previo).copy(
            error = if (previo.conectado) "Conexión Bluetooth finalizada. Estado físico sin confirmar." else null)
        scope.launch { anteriores?.cerrar() }
    }

    fun cerrar(): Unit = synchronized(monitor) {
        if (destruido) return
        val anteriores = recursos
        desconectarInterno(true)
        destruido = true
        cambios.close()
        scope.launch { anteriores?.cerrar(); scope.cancel() }
    }

    private companion object {
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

data class CambioHardware(val generacion: Long, val evento: EventoFisico?, val sistemaActivo: Boolean?)
