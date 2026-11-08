package com.example.safehome

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** USB local de diagnóstico. No conoce Firebase, MQTT ni comandos de actuadores. */
class EnlaceUsbPortatil(context: Context) {
    private val contexto = context.applicationContext
    private val manager = contexto.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val io = Mutex()
    private val sesion = SesionUsbPortatil()
    private val _estado = MutableStateFlow(sesion.estado)
    val estado = _estado.asStateFlow()
    private var habilitado = false
    private var destruido = false
    private var secuencia = 0L
    private var tarea: Job? = null
    private var cierrePendiente: Job? = null
    private var espera: Job? = null
    private var recursos: Recursos? = null
    private var dispositivoSeleccionado: String? = null
    private var receptorPermiso: BroadcastReceiver? = null
    private var permisoPendiente: PendingIntent? = null

    private class Recursos(val puerto: UsbSerialPort, val conexion: UsbDeviceConnection) {
        val lineas = ReceptorLineasSafeHome()
        var lector: SerialInputOutputManager? = null
        private var cerrado = false
        // Todas las aperturas/escrituras/cierres se serializan mediante io, fuera de Main.
        fun cerrar() {
            if (cerrado) return
            cerrado = true
            lector?.setListener(null)
            runCatching { lector?.stop() }
            runCatching { puerto.close() }
            runCatching { conexion.close() }
        }
    }

    private val receptorConexion = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val id = synchronized(monitor) {
                if (!habilitado || destruido) return
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    val dispositivo = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dispositivo != null && dispositivo.deviceName == dispositivoSeleccionado) {
                        terminar(sesion.generacion, ConexionUsbPortatil.DESCONECTADO, "Dispositivo portátil desconectado.")
                    }
                }
                sesion.generacion
            }
            refrescarDispositivos(id)
        }
    }

    init {
        ContextCompat.registerReceiver(contexto, receptorConexion, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun vigente(id: Long) = !destruido && habilitado && id == sesion.generacion

    private fun publicar(id: Long, cambio: (EstadoHardwarePortatil) -> EstadoHardwarePortatil) = synchronized(monitor) {
        if (vigente(id) && sesion.actualizar(id, cambio)) _estado.value = sesion.estado
    }

    private fun descripcion(driver: UsbSerialDriver): DispositivoUsbPortatil {
        val device = driver.device
        val nombre = if (driver is Cp21xxSerialDriver) "ESP32 / CP210x" else device.productName?.takeIf { it.isNotBlank() } ?: "Dispositivo USB Serial"
        return DispositivoUsbPortatil(device.deviceName, nombre, device.vendorId, device.productId)
    }

    fun listarDispositivos() {
        val id = synchronized(monitor) {
            if (destruido) return
            habilitado = true
            sesion.generacion
        }
        refrescarDispositivos(id)
    }

    private fun refrescarDispositivos(id: Long) {
        scope.launch {
            if (!synchronized(monitor) { vigente(id) }) return@launch
            try {
                if (manager == null || !contexto.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                    publicar(id) { it.copy(conexion = ConexionUsbPortatil.USB_NO_DISPONIBLE, dispositivos = emptyList()) }
                    return@launch
                }
                val lista = UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter { it.ports.isNotEmpty() }.map(::descripcion)
                publicar(id) { it.copy(dispositivos = lista) }
            } catch (_: Exception) {
                publicar(id) { it.copy(error = "No se pudieron consultar los dispositivos USB.") }
            }
        }
    }

    fun solicitarConexion(clave: String) {
        synchronized(monitor) {
            if (destruido || sesion.estado.conectado || sesion.estado.conexion == ConexionUsbPortatil.ABRIENDO || sesion.estado.solicitandoPermiso) return
            habilitado = true
            val dispositivo = sesion.estado.dispositivos.find { it.clave == clave } ?: return
            val id = sesion.reiniciar(EstadoHardwarePortatil(conexion = ConexionUsbPortatil.ABRIENDO,
                dispositivos = sesion.estado.dispositivos, claveUsb = clave, nombreUsb = dispositivo.nombre,
                vendorId = dispositivo.vendorId, productId = dispositivo.productId))
            dispositivoSeleccionado = clave
            _estado.value = sesion.estado
            val cierreAnterior = cierrePendiente
            tarea = scope.launch {
                cierreAnterior?.join()
                io.withLock {
                    try {
                        val device = manager?.deviceList?.get(clave)
                        if (device == null) {
                            terminar(id, ConexionUsbPortatil.DESCONECTADO, "Dispositivo portátil desconectado.")
                        } else if (manager.hasPermission(device)) abrir(id, device)
                        else pedirPermiso(id, device)
                    } catch (_: Exception) {
                        terminar(id, ConexionUsbPortatil.ERROR, "No se pudo solicitar la conexión USB.")
                    }
                }
            }
        }
    }

    private fun pedirPermiso(id: Long, device: UsbDevice) {
        synchronized(monitor) {
            if (!vigente(id)) return
            val accion = "${contexto.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
            val receptor = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != accion) return
                    synchronized(monitor) {
                        if (!vigente(id)) return
                        limpiarPermiso()
                    }
                    scope.launch {
                        io.withLock {
                            if (!synchronized(monitor) { vigente(id) }) return@withLock
                            try {
                                val actual = manager?.deviceList?.get(device.deviceName)
                                if (actual == null || actual.deviceId != device.deviceId) {
                                    terminar(id, ConexionUsbPortatil.DESCONECTADO, "Dispositivo portátil desconectado.")
                                } else if (manager.hasPermission(actual)) {
                                    publicar(id) { it.copy(conexion = ConexionUsbPortatil.ABRIENDO, solicitandoPermiso = false) }
                                    abrir(id, actual)
                                } else publicar(id) { it.copy(conexion = ConexionUsbPortatil.PERMISO_REQUERIDO,
                                    solicitandoPermiso = false, error = "Acceso USB no autorizado. Puedes volver a conectar para solicitarlo.") }
                            } catch (_: Exception) {
                                terminar(id, ConexionUsbPortatil.ERROR, "No se pudo abrir el dispositivo USB.")
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(contexto, receptor, IntentFilter(accion), ContextCompat.RECEIVER_NOT_EXPORTED)
            receptorPermiso = receptor
            // Inmutable, de un solo uso y limitado al paquete. No dependemos de extras mutables:
            // se vuelve a consultar hasPermission y deviceList al recibir la respuesta del sistema.
            permisoPendiente = PendingIntent.getBroadcast(contexto, 0, Intent(accion).setPackage(contexto.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            publicar(id) { it.copy(conexion = ConexionUsbPortatil.PERMISO_REQUERIDO, solicitandoPermiso = true, error = null) }
            manager?.requestPermission(device, permisoPendiente)
        }
    }

    private fun abrir(id: Long, device: UsbDevice) {
        if (!synchronized(monitor) { vigente(id) }) return
        if (manager?.hasPermission(device) != true) throw SecurityException()
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: throw IOException()
        val puerto = driver.ports.firstOrNull() ?: throw IOException()
        val connection = manager.openDevice(device) ?: throw IOException()
        val actuales = Recursos(puerto, connection)
        var instalado = false
        try {
            puerto.open(connection)
            puerto.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            puerto.setFlowControl(UsbSerialPort.FlowControl.NONE)
            val hello = synchronized(monitor) {
                if (!vigente(id)) return
                recursos = actuales
                val mensaje = nuevoMensaje(ComandoPortatil.HELLO)
                publicar(id) { puertoPortatilAbierto(it, mensaje.secuencia) }
                mensaje
            }
            actuales.lector = SerialInputOutputManager(puerto, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    synchronized(monitor) {
                        if (!vigente(id)) return
                        actuales.lineas.recibir(data, { recibir(id, it) }, {
                            publicar(id) { it.copy(error = "Se descartó información USB inválida.") }
                        })
                    }
                }
                override fun onRunError(e: Exception) {
                    terminar(id, ConexionUsbPortatil.ERROR, "Se interrumpió la comunicación USB. Revisa el cable y vuelve a conectar.")
                }
            }).apply { readTimeout = 250 }
            actuales.lector?.start()
            if (!synchronized(monitor) { vigente(id) }) return
            programarEspera(id, hello.secuencia)
            puerto.write(ProtocoloSafeHome.serializar(hello).toByteArray(Charsets.UTF_8), 2_000)
            instalado = true
        } finally {
            if (!instalado || !synchronized(monitor) { vigente(id) }) actuales.cerrar()
        }
    }

    private fun nuevoMensaje(comando: ComandoPortatil): MensajeSafeHome {
        secuencia = if (secuencia >= Long.MAX_VALUE - 1) 1 else secuencia + 1
        return mensajeComandoPortatil(comando, secuencia)
    }

    fun probarComunicacion() = solicitar(ComandoPortatil.PING)
    fun actualizarEstado() = solicitar(ComandoPortatil.STATUS)

    private fun solicitar(comando: ComandoPortatil) {
        synchronized(monitor) {
            if (!habilitado || destruido) return
            val mensaje = nuevoMensaje(comando)
            val siguiente = iniciarComandoPortatil(sesion.estado, comando, mensaje.secuencia)
            if (siguiente.operacion?.secuencia != mensaje.secuencia) return
            val id = sesion.generacion
            publicar(id) { siguiente }
            programarEspera(id, mensaje.secuencia)
            scope.launch {
                io.withLock {
                    val actuales = synchronized(monitor) {
                        if (vigente(id) && sesion.estado.operacion?.secuencia == mensaje.secuencia) recursos else null
                    } ?: return@withLock
                    try { actuales.puerto.write(ProtocoloSafeHome.serializar(mensaje).toByteArray(Charsets.UTF_8), 2_000) }
                    catch (_: Exception) { terminar(id, ConexionUsbPortatil.ERROR, "No se pudo enviar la solicitud USB.") }
                }
            }
        }
    }

    private fun programarEspera(id: Long, secuencia: Long) = synchronized(monitor) {
        if (!vigente(id) || sesion.estado.operacion?.secuencia != secuencia) return
        espera?.cancel()
        espera = scope.launch {
            delay(5_000)
            publicar(id) { expirarComandoPortatil(it, secuencia) }
        }
    }

    private fun recibir(id: Long, mensaje: MensajeSafeHome) {
        if (!vigente(id)) return
        val resultado = procesarMensajePortatil(sesion.estado, mensaje)
        publicar(id) { resultado.estado }
        if (resultado.estado.operacion == null) espera?.cancel()
        if (resultado.estado.conexion == ConexionUsbPortatil.ERROR) terminar(id, ConexionUsbPortatil.ERROR, resultado.estado.error)
        else if (resultado.solicitarEstado) solicitar(ComandoPortatil.STATUS)
    }

    private fun limpiarPermiso() {
        receptorPermiso?.let { runCatching { contexto.unregisterReceiver(it) } }
        receptorPermiso = null
        permisoPendiente?.cancel()
        permisoPendiente = null
    }

    private fun terminar(id: Long, conexion: ConexionUsbPortatil, error: String?) = synchronized(monitor) {
        if (!vigente(id)) return
        desconectarInterno(false)
        sesion.actualizar(sesion.generacion) { it.copy(conexion = conexion, error = error) }
        _estado.value = sesion.estado
    }

    fun desconectar() = synchronized(monitor) { desconectarInterno(false) }

    fun finalizarSesion() = synchronized(monitor) {
        habilitado = false
        dispositivoSeleccionado = null
        desconectarInterno(true)
    }

    private fun desconectarInterno(olvidar: Boolean) {
        if (olvidar) sesion.finalizarSesion() else sesion.reiniciar(invalidarEstadoPortatil(sesion.estado))
        _estado.value = sesion.estado
        tarea?.cancel()
        tarea = null
        espera?.cancel()
        espera = null
        limpiarPermiso()
        val anteriores = recursos
        recursos = null
        anteriores?.lineas?.limpiar()
        val cierreAnterior = cierrePendiente
        cierrePendiente = scope.launch {
            cierreAnterior?.join()
            io.withLock { anteriores?.cerrar() }
        }
    }

    fun cerrar() = synchronized(monitor) {
        if (destruido) return
        finalizarSesion()
        destruido = true
        runCatching { contexto.unregisterReceiver(receptorConexion) }
        val cierre = cierrePendiente
        scope.launch { cierre?.join(); scope.cancel() }
        Unit
    }
}
