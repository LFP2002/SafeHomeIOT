package com.example.safehome.iot

/** Tipo de dominio: el adaptador acepta exclusivamente Firestore Timestamp, no números. */
data class Instante(val millis: Long) {
    init { require(millis in 0..253402300799999L) }
}

enum class Dispositivo(val etiqueta: String) { ESTACION("ESP32 Estación"), PORTATIL("ESP32 Portátil") }
enum class ConexionMqtt { CONECTADO, DESCONECTADO, DESCONOCIDO }
enum class Puerta { ABIERTA, CERRADA, SIN_DATOS }
enum class Alarma { NORMAL, ADVERTENCIA, CRITICA, SIN_DATOS }

data class EstadoDispositivo(val mqtt: ConexionMqtt, val actualizadoEn: Instante, val ultimoHeartbeatEn: Instante?)
data class EstadoFisico(val puerta: Puerta, val alarma: Alarma, val actualizadoEn: Instante, val puertaCambiadaEn: Instante?)

sealed interface Lectura<out T> {
    data object Cargando : Lectura<Nothing>
    data object SinDatos : Lectura<Nothing>
    data object Invalida : Lectura<Nothing>
    data class Dato<T>(val valor: T, val desdeCache: Boolean = false) : Lectura<T>
    data class Error(val mensaje: String) : Lectura<Nothing>
}

/** Solo recibe el código, nunca el mensaje arbitrario de una excepción SDK. */
fun mensajeErrorConsulta(codigo: String?): String = when (codigo) {
    "PERMISSION_DENIED" -> "Firebase no autoriza la lectura de este hogar."
    "UNAUTHENTICATED" -> "La sesión ya no está autenticada."
    "UNAVAILABLE" -> "Firestore no está disponible. Revisa tu conexión y reintenta."
    else -> "No se pudo consultar Firestore. Reintenta la lectura."
}

fun parsearDispositivo(datos: Map<String, Any?>): EstadoDispositivo? {
    if (datos["schemaVersion"] != 1L) return null
    val mqtt = ConexionMqtt.entries.find { it.name == datos["mqtt"] } ?: return null
    val actualizado = datos["actualizadoEn"] as? Instante ?: return null
    val heartbeat = datos["ultimoHeartbeatEn"]
    if (heartbeat != null && (heartbeat !is Instante || heartbeat.millis > actualizado.millis)) return null
    return EstadoDispositivo(mqtt, actualizado, heartbeat as? Instante)
}

fun parsearEstadoFisico(datos: Map<String, Any?>): EstadoFisico? {
    if (datos["schemaVersion"] != 1L) return null
    val actualizado = datos["actualizadoEn"] as? Instante ?: return null
    val puerta = if (datos["puerta"] == null) Puerta.SIN_DATOS else
        Puerta.entries.find { it.name == datos["puerta"] } ?: return null
    val alarma = if (datos["alarma"] == null) Alarma.SIN_DATOS else
        Alarma.entries.find { it.name == datos["alarma"] } ?: return null
    val cambio = datos["puertaCambiadaEn"]
    if (cambio != null && (cambio !is Instante || cambio.millis > actualizado.millis || puerta == Puerta.SIN_DATOS)) return null
    return EstadoFisico(puerta, alarma, actualizado, cambio as? Instante)
}

fun puertaPuedeEstarDesactualizada(estacion: Lectura<EstadoDispositivo>, fisico: Lectura<EstadoFisico>): Boolean =
    estacion !is Lectura.Dato || estacion.desdeCache || estacion.valor.mqtt != ConexionMqtt.CONECTADO ||
        fisico !is Lectura.Dato || fisico.desdeCache
