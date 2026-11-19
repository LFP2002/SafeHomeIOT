package com.example.safehome.iot

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class Disponibilidad { ONLINE, OFFLINE, SIN_DATOS }
enum class PresenciaPortatil { PRESENTE, AUSENTE, SIN_DATOS }
data class EstadoMqtt(val sistemaActivo: Boolean, val puerta: Puerta, val portatil: PresenciaPortatil,
    val alarma: Alarma, val actualizadoEn: Long, val bootId: String, val ultimoComandoId: String)
data class EventoMqtt(val id: String, val tipo: String, val origen: String, val ocurridoEn: Long)
data class PanelMqtt(val conexion: String = "Desconectado", val conectado: Boolean = false,
    val estacion: Disponibilidad = Disponibilidad.SIN_DATOS,
    val portatil: Disponibilidad = Disponibilidad.SIN_DATOS,
    val estado: EstadoMqtt? = null, val retenido: Boolean = true,
    val esperando: Boolean = false, val comando: String? = null,
    val almacenamiento: String? = null) {
    val permiteControl get() = conectado && estacion == Disponibilidad.ONLINE && !esperando
    val permiteReconocer get() = permiteControl && estado?.sistemaActivo == true &&
        (estado.alarma == Alarma.ADVERTENCIA || estado.alarma == Alarma.CRITICA)
}

/** Solo estos topics pueden suscribirse/publicarse. Hogar MQTT fijo del prototipo. */
object TopicsMqtt {
    const val ESTACION = "safehome/9f21/estacion/disponibilidad"
    const val PORTATIL = "safehome/9f21/portatil/disponibilidad"
    const val ESTADO = "safehome/9f21/estacion/estado"
    const val EVENTO = "safehome/9f21/estacion/evento"
    const val COMANDO = "safehome/9f21/estacion/comando"
    val suscripciones = listOf(ESTACION, PORTATIL, ESTADO, EVENTO)
}

object MensajesMqtt {
    private val id = Regex("[A-Za-z0-9_-]{1,128}")
    private val comandoId = Regex("[0-9a-fA-F-]{8,40}")
    private val tipos = setOf("PUERTA_ABIERTA", "PUERTA_CERRADA", "PORTATIL_AUSENTE", "PORTATIL_RECUPERADO",
        "ALARMA_ADVERTENCIA", "ALARMA_CRITICA", "ALARMA_NORMAL", "ALARMA_RECONOCIDA", "SISTEMA_ACTIVADO", "SISTEMA_DESACTIVADO")
    private val origenes = setOf("GPIO27", "GPIO26", "GPIO25", "ANDROID", "ESP32")
    fun texto(bytes: ByteArray): String? = runCatching {
        require(bytes.size in 1..4096)
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    private fun JSONObject.cadena(key: String) = (get(key) as? String) ?: error("tipo")
    private fun JSONObject.fecha(key: String): Long {
        val valor = get(key)
        require(valor is Long || valor is Int)
        return (valor as Number).toLong().also { require(it in 1L..253402300799999L) }
    }
    fun estado(texto: String): EstadoMqtt? = runCatching {
        val j = JSONObject(texto)
        require(j.get("schemaVersion") is Int && j.getInt("schemaVersion") == 1)
        val activo = j.get("sistemaActivo") as? Boolean ?: error("tipo")
        val puerta = Puerta.valueOf(j.cadena("puerta")).also { require(it != Puerta.SIN_DATOS) }
        val alarma = Alarma.valueOf(j.cadena("alarma")).also { require(it != Alarma.SIN_DATOS) }
        val boot = j.cadena("bootId").also { require(id.matches(it)) }
        val ultimo = j.cadena("ultimoComandoId").also { require(it.isEmpty() || comandoId.matches(it)) }
        EstadoMqtt(activo, puerta, PresenciaPortatil.valueOf(j.cadena("portatil")), alarma,
            j.fecha("actualizadoEn"), boot, ultimo)
    }.getOrNull()
    fun evento(texto: String): EventoMqtt? = runCatching {
        val j = JSONObject(texto)
        EventoMqtt(j.cadena("id").also { require(id.matches(it)) },
            j.cadena("tipo").also { require(it in tipos) },
            j.cadena("origen").also { require(it in origenes) }, j.fecha("ocurridoEn"))
    }.getOrNull()
}
