package com.example.safehome.iot

import android.content.Context
import com.example.safehome.BuildConfig
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Todas las transiciones y callbacks se serializan en Main. Un solo cliente, sin servicio. */
class SupervisionMqtt(context: Context, private val scope: CoroutineScope,
    private val dao: EventosDao, private val publicar: (PanelSupervision) -> Unit) {
    private var sesion: SesionSupervision? = null
    private var activa = false
    private var generacion = 0L
    private var ocupado = false
    private var conectado = false
    private var reintento: Job? = null
    private var historial: Job? = null
    private var timeout: Job? = null
    private enum class Comando { ACTIVAR, DESACTIVAR, RECONOCER }
    private var pendiente: Pair<String, Comando>? = null
    private var panel = PanelSupervision(mqtt = PanelMqtt())
    private val preferencias = context.getSharedPreferences("mqtt-identidad", Context.MODE_PRIVATE)
    private val clientId = preferencias.getString("clientId", null) ?: ("SafeHome-Android-" + UUID.randomUUID())
        .also { preferencias.edit().putString("clientId", it).commit() }
    private val cliente: Mqtt3AsyncClient = MqttClient.builder().useMqttVersion3()
        .identifier(clientId).serverHost("g80de15c.ala.us-east-1.emqxsl.com").serverPort(8883)
        .sslWithDefaultConfig()
        .addDisconnectedListener { scope.launch { caida() } }
        .buildAsync()

    private fun emitir(cambio: (PanelMqtt) -> PanelMqtt) {
        panel = panel.copy(mqtt = cambio(panel.mqtt ?: PanelMqtt()))
        publicar(panel)
    }
    fun sesion(nueva: SesionSupervision?) {
        if (sesion == nueva) return
        detener()
        sesion = nueva
        historial?.cancel()
        panel = PanelSupervision(historial = Lectura.SinDatos, mqtt = PanelMqtt())
        publicar(panel)
        if (nueva != null) historial = scope.launch {
            try {
                dao.observar(nueva.uid, nueva.hogar).collect { filas ->
                    if (sesion != nueva) return@collect
                    panel = panel.copy(historial = Lectura.Dato(HistorialSupervision(filas.map {
                        EventoSupervision(it.id, it.tipo, "Estación · Origen: ${it.origen}", Instante(it.ocurridoEn),
                            it.tipo.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() })
                    }, 0, false)))
                    publicar(panel)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (sesion == nueva) { panel = panel.copy(historial = Lectura.Error("No se pudo leer Room local.")); publicar(panel) }
            }
        }
        conectar()
    }
    fun primerPlano(valor: Boolean) {
        activa = valor
        if (valor) conectar() else detener()
    }
    fun reintentar() { reintento?.cancel(); conectar() }
    private fun autorizada() = activa && sesion != null
    private fun conectar() {
        if (!autorizada() || ocupado || conectado) return
        if (BuildConfig.MQTT_PASSWORD.isEmpty()) {
            emitir { it.copy(conexion = "Falta configurar MQTT local") }; return
        }
        ocupado = true
        val turno = generacion
        emitir { it.copy(conexion = "Conectando TLS…") }
        cliente.connectWith().cleanSession(true).keepAlive(30)
            .simpleAuth().username("safehome_android").password(BuildConfig.MQTT_PASSWORD.toByteArray(Charsets.UTF_8))
            .applySimpleAuth().send().whenComplete { _, error -> scope.launch {
                if (error != null) {
                    ocupado = false
                    emitir { it.copy(conexion = "No conectado: revisar red, TLS o credenciales") }
                    programar()
                } else if (turno != generacion || !autorizada()) {
                    desconectarCliente()
                } else {
                    // No habilitar comandos hasta recibir todos los SUBACK.
                    try {
                        val subs = TopicsMqtt.suscripciones.map { topic ->
                            cliente.subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE)
                                .callback { mensaje ->
                                    if (mensaje.payload.map { it.remaining() > 4096 }.orElse(false)) return@callback
                                    val bytes = mensaje.payloadAsBytes
                                    scope.launch { recibir(turno, topic, bytes, mensaje.isRetain) }
                                }.send()
                        }
                        java.util.concurrent.CompletableFuture.allOf(*subs.toTypedArray()).whenComplete { _, fallo -> scope.launch {
                            if (turno != generacion || !autorizada() || !cliente.state.isConnected || fallo != null ||
                                subs.any { f -> f.getNow(null)?.returnCodes?.any { it.code == 128 } == true }) {
                                desconectarCliente()
                            } else {
                                ocupado = false; conectado = true
                                emitir { it.copy(conectado = true, conexion = "MQTT/TLS conectado") }
                            }
                        } }
                    } catch (_: Exception) { desconectarCliente() }
                }
            } }
    }
    private fun programar() {
        reintento?.cancel()
        if (autorizada()) reintento = scope.launch { delay(3000); conectar() }
    }
    private fun desconocido() {
        timeout?.cancel()
        if (pendiente != null) emitir { it.copy(esperando = false, comando = "Resultado desconocido: conexión interrumpida. Verifique el estado y reintente manualmente.") }
        pendiente = null
    }
    private fun caida() {
        generacion++
        conectado = false
        desconocido()
        emitir { it.copy(conectado = false, conexion = "MQTT desconectado",
            estacion = Disponibilidad.SIN_DATOS, portatil = Disponibilidad.SIN_DATOS, retenido = true) }
        if (!ocupado) programar()
    }
    private fun detener() {
        generacion++
        reintento?.cancel()
        desconocido()
        val estabaConectado = conectado
        conectado = false
        emitir { it.copy(conectado = false, conexion = "Desconectado", estacion = Disponibilidad.SIN_DATOS,
            portatil = Disponibilidad.SIN_DATOS, retenido = true) }
        // Si connect está en curso, su callback cerrará antes de permitir otro connect.
        if (estabaConectado || (!ocupado && cliente.state.isConnected)) desconectarCliente()
    }
    private fun desconectarCliente() {
        ocupado = true; conectado = false
        cliente.disconnect().whenComplete { _, _ -> scope.launch {
            ocupado = false; programar()
        } }
    }
    private fun recibir(turno: Long, topic: String, bytes: ByteArray, retained: Boolean) {
        val cuenta = sesion ?: return
        if (turno != generacion || !activa) return
        val texto = MensajesMqtt.texto(bytes) ?: return
        when (topic) {
            TopicsMqtt.ESTACION, TopicsMqtt.PORTATIL -> {
                val valor = when (texto) { "ONLINE" -> Disponibilidad.ONLINE; "OFFLINE" -> Disponibilidad.OFFLINE; else -> return }
                if (topic == TopicsMqtt.ESTACION) {
                    if (valor == Disponibilidad.OFFLINE) desconocido()
                    emitir { it.copy(estacion = valor, retenido = if (valor == Disponibilidad.OFFLINE) true else it.retenido) }
                } else emitir { it.copy(portatil = valor) }
            }
            TopicsMqtt.ESTADO -> {
                val estado = MensajesMqtt.estado(texto) ?: return
                emitir { it.copy(estado = estado, retenido = retained) }
                val orden = pendiente
                val confirmado = when (orden?.second) {
                    Comando.ACTIVAR -> estado.sistemaActivo
                    Comando.DESACTIVAR -> !estado.sistemaActivo
                    Comando.RECONOCER -> estado.alarma == Alarma.NORMAL
                    null -> false
                }
                if (orden != null && estado.ultimoComandoId == orden.first && confirmado) {
                    pendiente = null; timeout?.cancel()
                    val resultado = when (orden.second) {
                        Comando.ACTIVAR -> "ACTIVADO"
                        Comando.DESACTIVAR -> "DESACTIVADO"
                        Comando.RECONOCER -> "ALARMA SILENCIADA"
                    }
                    emitir { it.copy(esperando = false, comando = "Confirmado por Estación: $resultado") }
                }
            }
            TopicsMqtt.EVENTO -> {
                if (retained) return
                val evento = MensajesMqtt.evento(texto) ?: return
                scope.launch {
                    try { dao.insertar(EventoLocal(evento.id, cuenta.uid, cuenta.hogar, evento.ocurridoEn, evento.tipo, evento.origen)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (sesion == cuenta) emitir { it.copy(almacenamiento = "Error guardando evento en Room; el historial puede estar incompleto.") } }
                }
            }
        }
    }
    fun enviar(activar: Boolean) {
        enviar(if (activar) Comando.ACTIVAR else Comando.DESACTIVAR)
    }
    fun reconocer() = enviar(Comando.RECONOCER)
    private fun enviar(comando: Comando) {
        val mqtt = panel.mqtt ?: return
        if (!autorizada() || pendiente != null || !mqtt.permiteControl) return
        if (comando == Comando.RECONOCER && !mqtt.permiteReconocer) return
        val ahora = System.currentTimeMillis()
        if (ahora.toString().length != 13) { emitir { it.copy(comando = "Corrija la fecha y hora del teléfono.") }; return }
        val id = UUID.randomUUID().toString()
        pendiente = id to comando
        emitir { it.copy(esperando = true, comando = "Esperando confirmación de Estación…") }
        timeout = scope.launch {
            delay(10_000)
            if (pendiente?.first == id) {
                pendiente = null
                emitir { it.copy(esperando = false, comando = "Sin confirmación tras 10 s. Resultado desconocido; compruebe la hora y reintente manualmente.") }
            }
        }
        val texto = "${comando.name}|$ahora|$id"
        try {
            cliente.publishWith().topic(TopicsMqtt.COMANDO).qos(MqttQos.AT_LEAST_ONCE).retain(false)
                .payload(texto.toByteArray(Charsets.UTF_8)).send().whenComplete { _, error -> scope.launch {
                    if (error != null && pendiente?.first == id) desconocido()
                } }
        } catch (_: Exception) { desconocido() }
    }
    fun cerrar() { activa = false; sesion(null); detener() }
}
