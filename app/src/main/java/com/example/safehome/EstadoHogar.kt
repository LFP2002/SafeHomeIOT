package com.example.safehome

import com.google.firebase.Timestamp
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class NivelEstadoHogar {
    NORMAL,
    ADVERTENCIA,
    CRITICO
}

data class EstadoHogar(
    val puertaAbierta: Boolean,
    val personaPresente: Boolean,
    val horaLocal: String,
    val nivel: NivelEstadoHogar
)

sealed interface ResultadoEstadoHogar {
    data class Valido(val estado: EstadoHogar) : ResultadoEstadoHogar
    data object Invalido : ResultadoEstadoHogar
}

fun clasificarEstadoHogar(personaPresente: Boolean, puertaAbierta: Boolean): NivelEstadoHogar = when {
    personaPresente -> NivelEstadoHogar.NORMAL
    puertaAbierta -> NivelEstadoHogar.CRITICO
    else -> NivelEstadoHogar.ADVERTENCIA
}

fun validarEstadoHogar(datos: Map<String, Any?>): ResultadoEstadoHogar {
    val puertaAbierta = datos["puertaAbierta"] as? Boolean ?: return ResultadoEstadoHogar.Invalido
    val personaPresente = datos["personaPresente"] as? Boolean ?: return ResultadoEstadoHogar.Invalido
    val actualizadoEn = datos["actualizadoEn"] as? Timestamp ?: return ResultadoEstadoHogar.Invalido
    val horaLocal = formatearHoraLocal(actualizadoEn) ?: return ResultadoEstadoHogar.Invalido
    return ResultadoEstadoHogar.Valido(
        EstadoHogar(
            puertaAbierta = puertaAbierta,
            personaPresente = personaPresente,
            horaLocal = horaLocal,
            nivel = clasificarEstadoHogar(personaPresente, puertaAbierta)
        )
    )
}

fun formatearHoraLocal(timestamp: Timestamp): String? = try {
    val instante = Instant.ofEpochSecond(timestamp.seconds, timestamp.nanoseconds.toLong())
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instante)
} catch (_: DateTimeException) {
    null
}

fun respuestaEstadoEsVigente(
    idActivo: Long,
    uidActivo: String,
    hogarActivo: String,
    sesionActiva: Long,
    uidFirebaseActual: String?,
    idRespuesta: Long,
    uidRespuesta: String,
    hogarRespuesta: String,
    sesionRespuesta: Long
): Boolean = idActivo == idRespuesta &&
    uidActivo == uidRespuesta &&
    hogarActivo == hogarRespuesta &&
    sesionActiva == sesionRespuesta &&
    uidFirebaseActual == uidRespuesta

fun respuestaEscrituraEsVigente(
    idActivo: Long,
    uidActivo: String,
    hogarActivo: String,
    sesionActiva: Long,
    uidFirebaseActual: String?,
    idRespuesta: Long,
    uidRespuesta: String,
    hogarRespuesta: String,
    sesionRespuesta: Long
): Boolean = respuestaEstadoEsVigente(
    idActivo,
    uidActivo,
    hogarActivo,
    sesionActiva,
    uidFirebaseActual,
    idRespuesta,
    uidRespuesta,
    hogarRespuesta,
    sesionRespuesta
)
