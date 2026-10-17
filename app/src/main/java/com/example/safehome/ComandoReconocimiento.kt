package com.example.safehome

import com.google.firebase.Timestamp

private const val TIPO_RECONOCER_ALERTA = "RECONOCER_ALERTA"

data class ComandoReconocimiento(
    val secuencia: Long,
    val horaLocal: String
)

sealed interface ResultadoComandoReconocimiento {
    data class Valido(val comando: ComandoReconocimiento) : ResultadoComandoReconocimiento
    data object Invalido : ResultadoComandoReconocimiento
}

fun validarComandoReconocimiento(datos: Map<String, Any?>): ResultadoComandoReconocimiento {
    if (datos["tipo"] != TIPO_RECONOCER_ALERTA) return ResultadoComandoReconocimiento.Invalido
    val secuencia = datos["secuencia"] as? Long ?: return ResultadoComandoReconocimiento.Invalido
    if (secuencia < 0) return ResultadoComandoReconocimiento.Invalido
    val emitidoEn = datos["emitidoEn"] as? Timestamp ?: return ResultadoComandoReconocimiento.Invalido
    val horaLocal = formatearHoraLocal(emitidoEn) ?: return ResultadoComandoReconocimiento.Invalido
    return ResultadoComandoReconocimiento.Valido(ComandoReconocimiento(secuencia, horaLocal))
}

fun cuidadorPuedeReconocer(rol: RolUsuario, estado: EstadoHogar?): Boolean =
    rol.esRemoto() && estado?.nivel in setOf(
        NivelEstadoHogar.ADVERTENCIA,
        NivelEstadoHogar.CRITICO
    )

fun envioReconocimientoHabilitado(
    rol: RolUsuario,
    estado: EstadoHogar?,
    envioPendiente: Boolean
): Boolean = !envioPendiente && cuidadorPuedeReconocer(rol, estado)

fun siguienteSecuenciaComando(secuencia: Long): Long? =
    if (secuencia < 0 || secuencia == Long.MAX_VALUE) null else secuencia + 1

fun confirmacionComandoEsEsperada(secuenciaEsperada: Long, comando: ComandoReconocimiento): Boolean =
    comando.secuencia == secuenciaEsperada

fun respuestaComandoEsVigente(
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
