package com.example.safehome.presentacion

import com.example.safehome.iot.*

/** Criterio conservador de presentación, no un timeout de red ni una regla de control. */
const val VIGENCIA_VISUAL_MS = 60_000L

fun estadoVigente(panel: PanelMqtt, ahora: Long): Boolean {
    val fecha = panel.estado?.actualizadoEn ?: return false
    return panel.conectado && panel.estacion == Disponibilidad.ONLINE && !panel.retenido &&
        fecha <= ahora && ahora - fecha <= VIGENCIA_VISUAL_MS
}

fun conexionVisual(panel: PanelMqtt): EstadoVisual = when {
    panel.conectado -> EstadoVisual("Conectado", IconoSafeHome.CONECTADO, TonoEstado.POSITIVO)
    panel.conexion.startsWith("Conectando") -> EstadoVisual("Conectando…", IconoSafeHome.ACTUALIZAR, TonoEstado.AVISO)
    else -> EstadoVisual("Desconectado", IconoSafeHome.DESCONECTADO, TonoEstado.NEUTRO)
}

fun disponibilidadVisual(valor: Disponibilidad, conectado: Boolean): EstadoVisual = when {
    !conectado || valor == Disponibilidad.SIN_DATOS -> EstadoVisual("Sin datos", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
    valor == Disponibilidad.ONLINE -> EstadoVisual("En línea", IconoSafeHome.CONECTADO, TonoEstado.POSITIVO)
    else -> EstadoVisual("Desconectado", IconoSafeHome.DESCONECTADO, TonoEstado.CRITICO)
}

fun vigilanciaVisual(panel: PanelMqtt, ahora: Long): EstadoVisual = when {
    panel.estado == null -> EstadoVisual("Sin datos", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
    !estadoVigente(panel, ahora) -> EstadoVisual(if (panel.estado.sistemaActivo) "Activada · anterior" else "Desactivada · anterior",
        IconoSafeHome.SISTEMA, TonoEstado.NEUTRO)
    panel.estado.sistemaActivo -> EstadoVisual("Activada", IconoSafeHome.NORMAL, TonoEstado.POSITIVO)
    else -> EstadoVisual("Desactivada", IconoSafeHome.SISTEMA, TonoEstado.NEUTRO)
}

fun resumenVisual(panel: PanelMqtt, ahora: Long): EstadoVisual = when {
    !panel.conectado -> if (conexionVisual(panel).tono == TonoEstado.AVISO)
        EstadoVisual("Conectando…", IconoSafeHome.ACTUALIZAR, TonoEstado.AVISO)
        else EstadoVisual("Supervisión sin conexión", IconoSafeHome.DESCONECTADO, TonoEstado.NEUTRO)
    panel.estacion == Disponibilidad.OFFLINE -> EstadoVisual("Estación desconectada", IconoSafeHome.DESCONECTADO, TonoEstado.CRITICO)
    panel.estacion != Disponibilidad.ONLINE || panel.estado == null ->
        EstadoVisual("Sin datos suficientes", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
    !estadoVigente(panel, ahora) -> EstadoVisual("Último estado conocido", IconoSafeHome.HISTORIAL, TonoEstado.NEUTRO)
    panel.estado.alarma == Alarma.CRITICA -> EstadoVisual("Alerta crítica activa", IconoSafeHome.ALARMA, TonoEstado.CRITICO)
    panel.estado.alarma == Alarma.ADVERTENCIA -> EstadoVisual("Alerta activa", IconoSafeHome.ADVERTENCIA, TonoEstado.AVISO)
    !panel.estado.sistemaActivo -> EstadoVisual("Vigilancia desactivada", IconoSafeHome.SISTEMA, TonoEstado.NEUTRO)
    panel.estado.alarma == Alarma.NORMAL && panel.estado.puerta != Puerta.SIN_DATOS &&
        panel.estado.portatil == PresenciaPortatil.PRESENTE -> EstadoVisual("Sistema normal", IconoSafeHome.NORMAL, TonoEstado.POSITIVO)
    else -> EstadoVisual("Sin datos suficientes", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
}

/** Traduce resultados existentes: nunca crea una confirmación por interacción de UI. */
fun resultadoVisual(panel: PanelMqtt): EstadoVisual? = when {
    panel.esperando -> EstadoVisual("Esperando respuesta…", IconoSafeHome.ACTUALIZAR, TonoEstado.AVISO)
    panel.comando == null -> null
    panel.comando.startsWith("Confirmado por Estación:") -> EstadoVisual(when {
        panel.comando.endsWith("ALARMA SILENCIADA") -> "Alarma silenciada"
        panel.comando.endsWith("DESACTIVADO") -> "Vigilancia desactivada"
        panel.comando.endsWith("ACTIVADO") -> "Vigilancia activada"
        else -> "Acción confirmada"
    }, IconoSafeHome.NORMAL, TonoEstado.POSITIVO)
    panel.comando.contains("hora", ignoreCase = true) && !panel.comando.contains("desconocido", ignoreCase = true) ->
        EstadoVisual("Revisa la fecha y hora del teléfono.", IconoSafeHome.ADVERTENCIA, TonoEstado.AVISO)
    else -> EstadoVisual(if (panel.comando.contains("interrumpida"))
        "Conexión interrumpida. Resultado desconocido; revisa el estado antes de reintentar."
        else "Sin respuesta. Resultado desconocido; revisa el estado y la hora del teléfono antes de reintentar.",
        IconoSafeHome.ADVERTENCIA, TonoEstado.AVISO)
}

fun tituloEvento(tipo: String): String = when (tipo) {
    "PUERTA_ABIERTA" -> "Puerta abierta"
    "PUERTA_CERRADA" -> "Puerta cerrada"
    "PORTATIL_AUSENTE", "PORTATIL_FUERA_RANGO" -> "Portátil sin señal"
    "PORTATIL_RECUPERADO", "PORTATIL_RECONECTADO" -> "Señal del Portátil recuperada"
    "ALARMA_ADVERTENCIA" -> "Alarma de advertencia"
    "ALARMA_CRITICA" -> "Alarma crítica activada"
    "ALARMA_NORMAL" -> "Alarma normalizada"
    "ALARMA_RECONOCIDA" -> "Alarma silenciada"
    "SISTEMA_ACTIVADO" -> "Vigilancia activada"
    "SISTEMA_DESACTIVADO" -> "Vigilancia desactivada"
    "ESTACION_CONECTADA" -> "Estación conectada"
    "ESTACION_DESCONECTADA" -> "Estación desconectada"
    "PORTATIL_CONECTADO" -> "Portátil conectado"
    "PORTATIL_DESCONECTADO" -> "Portátil desconectado"
    else -> "Actividad registrada"
}

const val EVENTOS_POR_PAGINA = 5
data class PaginaHistorial<T>(val elementos: List<T>, val total: Int, val indice: Int, val paginas: Int) {
    val anterior get() = indice > 0
    val siguiente get() = indice + 1 < paginas
}

/** No muta, deduplica ni reordena la fuente; el DAO ya entrega orden descendente. */
fun <T> paginarHistorial(eventos: List<T>, solicitada: Int): PaginaHistorial<T> {
    val paginas = if (eventos.isEmpty()) 0 else (eventos.size - 1) / EVENTOS_POR_PAGINA + 1
    val indice = solicitada.coerceIn(0, (paginas - 1).coerceAtLeast(0))
    return PaginaHistorial(eventos.drop(indice * EVENTOS_POR_PAGINA).take(EVENTOS_POR_PAGINA), eventos.size, indice, paginas)
}
