package com.example.safehome.iot

data class EventoSupervision(val id: String, val tipo: String, val dispositivo: String, val ocurridoEn: Instante,
    val descripcion: String, val anterior: Boolean = false)
data class HistorialSupervision(val eventos: List<EventoSupervision>, val incompatibles: Int, val limiteAlcanzado: Boolean)

private val tiposNuevos = mapOf(
    "ESTACION_CONECTADA" to Dispositivo.ESTACION, "ESTACION_DESCONECTADA" to Dispositivo.ESTACION,
    "PORTATIL_CONECTADO" to Dispositivo.PORTATIL, "PORTATIL_DESCONECTADO" to Dispositivo.PORTATIL,
    "PUERTA_ABIERTA" to Dispositivo.ESTACION, "PUERTA_CERRADA" to Dispositivo.ESTACION)
private val textos = mapOf(
    "ESTACION_CONECTADA" to "Estación conectada a MQTT", "ESTACION_DESCONECTADA" to "Estación desconectada de MQTT",
    "PORTATIL_CONECTADO" to "Portátil conectado a MQTT", "PORTATIL_DESCONECTADO" to "Portátil desconectado de MQTT",
    "PUERTA_ABIERTA" to "Puerta abierta", "PUERTA_CERRADA" to "Puerta cerrada",
    "PORTATIL_FUERA_RANGO" to "Portátil fuera de rango reportado (anterior)",
    "PORTATIL_RECONECTADO" to "Portátil reconectado reportado (anterior)",
    "ALARMA_ADVERTENCIA" to "Alarma: advertencia", "ALARMA_CRITICA" to "Alarma: crítica",
    "ALARMA_RECONOCIDA" to "Alarma reconocida", "SISTEMA_ACTIVADO" to "Sistema activado",
    "SISTEMA_DESACTIVADO" to "Sistema desactivado")

fun parsearEvento(id: String, datos: Map<String, Any?>): EventoSupervision? {
    if (id.isBlank()) return null
    val tipo = datos["tipo"] as? String ?: return null
    val creado = datos["creadoEn"] as? Instante ?: return null
    if ("schemaVersion" in datos) {
        if (datos["schemaVersion"] != 1L) return null
        val dispositivo = tiposNuevos[tipo] ?: return null
        if (datos["dispositivo"] != dispositivo.name.lowercase()) return null
        return EventoSupervision(id, tipo, dispositivo.etiqueta, creado, textos.getValue(tipo))
    }
    if ("ocurridoEnMillis" in datos || "sincronizadoEn" in datos) {
        val ocurrido = datos["ocurridoEnMillis"] as? Long ?: return null
        if (ocurrido !in 1..253402300799999L || datos["sincronizadoEn"] !is Instante || ocurrido != creado.millis ||
            tipo !in textos || tipo in setOf("ESTACION_CONECTADA", "ESTACION_DESCONECTADA", "PORTATIL_CONECTADO", "PORTATIL_DESCONECTADO")) return null
    }
    var dispositivo = "Estación (registro anterior)"
    val descripcion = when (tipo) {
        "SISTEMA_ACTIVADO", "SISTEMA_DESACTIVADO" -> {
            if (datos["sistemaActivo"] != (tipo == "SISTEMA_ACTIVADO")) return null
            textos.getValue(tipo)
        }
        "MOVIL_CONECTADO", "MOVIL_DESCONECTADO" -> {
            if ((datos["uidMovil"] as? String).isNullOrBlank() ||
                (datos["nombreMovil"] as? String)?.let { com.example.safehome.validarNombreVisible(it) == null } != true) return null
            dispositivo = "Teléfono (registro anterior)"
            if (tipo == "MOVIL_CONECTADO") "Teléfono conectado; no es conexión MQTT" else "Teléfono desconectado; no es conexión MQTT"
        }
        "ESTADO_ACTUALIZADO" -> {
            val puerta = datos["puertaAbierta"] as? Boolean ?: return null
            if (datos["personaPresente"] !is Boolean) return null
            "Estado anterior: puerta ${if (puerta) "abierta" else "cerrada"}; presencia antigua no equivale a MQTT"
        }
        "RECONOCIMIENTO_SOLICITADO" -> {
            if ((datos["secuenciaComando"] as? Long)?.let { it > 0 } != true) return null
            "Solicitud de reconocimiento (anterior)"
        }
        "PORTATIL_FUERA_RANGO", "PORTATIL_RECONECTADO" -> {
            dispositivo = "Portátil (registro anterior, reportado por Estación)"
            textos.getValue(tipo)
        }
        else -> if (tipo in setOf("PUERTA_ABIERTA", "PUERTA_CERRADA",
            "ALARMA_ADVERTENCIA", "ALARMA_CRITICA", "ALARMA_RECONOCIDA")) textos.getValue(tipo) else return null
    }
    return EventoSupervision(id, tipo, dispositivo, creado, descripcion, anterior = true)
}

/** Solo deduplica identidad documental. Dos transiciones distintas del mismo tipo se conservan. */
fun prepararHistorial(documentos: List<Pair<String, Map<String, Any?>>>): HistorialSupervision {
    val validos = documentos.mapNotNull { (id, datos) -> parsearEvento(id, datos) }
    val eventos = validos.sortedWith(compareByDescending<EventoSupervision> { it.ocurridoEn.millis }.thenBy { it.id })
        .distinctBy { it.id }
    return HistorialSupervision(eventos, documentos.size - validos.size, documentos.size >= 100)
}
