package com.example.safehome

import com.google.firebase.Timestamp

data class ConfiguracionHogar(val sistemaActivo: Boolean)

sealed interface ObjetivoEscritura {
    data class Sistema(val sistemaActivo: Boolean) : ObjetivoEscritura
}

sealed interface ResultadoConfiguracionHogar {
    data class Valida(val configuracion: ConfiguracionHogar) : ResultadoConfiguracionHogar
    data object Invalida : ResultadoConfiguracionHogar
}

fun validarConfiguracionHogar(datos: Map<String, Any?>): ResultadoConfiguracionHogar {
    if (datos.keys != setOf("sistemaActivo", "actualizadoEn")) return ResultadoConfiguracionHogar.Invalida
    val sistemaActivo = datos["sistemaActivo"] as? Boolean ?: return ResultadoConfiguracionHogar.Invalida
    if (datos["actualizadoEn"] !is Timestamp) return ResultadoConfiguracionHogar.Invalida
    return ResultadoConfiguracionHogar.Valida(ConfiguracionHogar(sistemaActivo))
}

fun estacionPuedeCambiarSistema(rol: RolUsuario, configuracion: ConfiguracionHogar?, escrituraPendiente: Boolean): Boolean =
    rol.esEstacion() && configuracion != null && !escrituraPendiente

fun notificacionesHabilitadas(configuracion: ConfiguracionHogar?): Boolean = configuracion?.sistemaActivo == true

fun transicionSistemaEsValida(configuracion: ConfiguracionHogar?, sistemaActivoObjetivo: Boolean): Boolean =
    configuracion != null && configuracion.sistemaActivo != sistemaActivoObjetivo

fun respuestaConfiguracionEsVigente(
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
    idActivo, uidActivo, hogarActivo, sesionActiva, uidFirebaseActual,
    idRespuesta, uidRespuesta, hogarRespuesta, sesionRespuesta
)
