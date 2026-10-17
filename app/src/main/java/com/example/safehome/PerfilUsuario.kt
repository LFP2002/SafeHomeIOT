package com.example.safehome

private val formatoHogarId = Regex("[A-Za-z0-9_-]{1,64}")

enum class RolUsuario {
    SIMULADOR,
    CUIDADOR,
    ESTACION,
    MOVIL,
    MOVIL_PENDIENTE,
    MOVIL_RECHAZADO,
    MOVIL_DESVINCULADO,
    ESTACION_ELIMINADA
}

fun RolUsuario.esEstacion(): Boolean = this == RolUsuario.SIMULADOR || this == RolUsuario.ESTACION
fun RolUsuario.esMovil(): Boolean = this == RolUsuario.MOVIL
fun RolUsuario.esRemoto(): Boolean = this == RolUsuario.CUIDADOR || this == RolUsuario.MOVIL

data class PerfilUsuario(
    val rol: RolUsuario,
    val hogarId: String,
    val nombreVisible: String = "Remoto"
)

sealed interface ResultadoPerfil {
    data class Valido(val perfil: PerfilUsuario) : ResultadoPerfil
    data class Desvinculado(val perfil: PerfilUsuario) : ResultadoPerfil
    data class EliminacionPendiente(val perfil: PerfilUsuario) : ResultadoPerfil
    data object Invalido : ResultadoPerfil
}

fun validarPerfilUsuario(datos: Map<String, Any?>): ResultadoPerfil {
    if (datos["rol"] == "movil_rechazado" && datos["activo"] == false) {
        val hogarId = datos["hogarId"] as? String ?: return ResultadoPerfil.Invalido
        val nombre = datos["nombre"] as? String ?: return ResultadoPerfil.Invalido
        return if (formatoHogarId.matches(hogarId) && validarNombreVisible(nombre) == null && nombre == normalizarNombreVisible(nombre)) {
            ResultadoPerfil.Desvinculado(PerfilUsuario(RolUsuario.MOVIL_RECHAZADO, hogarId, nombre))
        } else ResultadoPerfil.Invalido
    }
    if (datos["rol"] == "movil_desvinculado" && datos["activo"] == false) {
        val hogarId = datos["hogarId"] as? String ?: return ResultadoPerfil.Invalido
        val nombre = datos["nombre"] as? String ?: return ResultadoPerfil.Invalido
        return if (formatoHogarId.matches(hogarId) && validarNombreVisible(nombre) == null && nombre == normalizarNombreVisible(nombre)) {
            ResultadoPerfil.Desvinculado(PerfilUsuario(RolUsuario.MOVIL_DESVINCULADO, hogarId, nombre))
        } else ResultadoPerfil.Invalido
    }
    if (datos["rol"] == "estacion_eliminada" && datos["activo"] == false) {
        val hogarId = datos["hogarId"] as? String ?: return ResultadoPerfil.Invalido
        val nombre = datos["nombre"] as? String ?: return ResultadoPerfil.Invalido
        return if (formatoHogarId.matches(hogarId) && validarNombreVisible(nombre) == null && nombre == normalizarNombreVisible(nombre)) {
            ResultadoPerfil.EliminacionPendiente(PerfilUsuario(RolUsuario.ESTACION_ELIMINADA, hogarId, nombre))
        } else ResultadoPerfil.Invalido
    }
    val rol = when (datos["rol"]) {
        "simulador" -> RolUsuario.SIMULADOR
        "cuidador" -> RolUsuario.CUIDADOR
        "estacion" -> RolUsuario.ESTACION
        "movil" -> RolUsuario.MOVIL
        "movil_pendiente" -> RolUsuario.MOVIL_PENDIENTE
        else -> return ResultadoPerfil.Invalido
    }
    val hogarId = datos["hogarId"] as? String ?: return ResultadoPerfil.Invalido
    val activoEsperado = rol != RolUsuario.MOVIL_PENDIENTE
    if (!formatoHogarId.matches(hogarId) || datos["activo"] !is Boolean || datos["activo"] != activoEsperado) {
        return ResultadoPerfil.Invalido
    }
    val nombre = datos["nombre"] as? String
    if ((rol == RolUsuario.ESTACION || rol.esMovil() || rol == RolUsuario.MOVIL_PENDIENTE) && (nombre == null || validarNombreVisible(nombre) != null || nombre != normalizarNombreVisible(nombre))) return ResultadoPerfil.Invalido
    return ResultadoPerfil.Valido(PerfilUsuario(rol, hogarId, nombre ?: if (rol.esEstacion()) "Estación" else "Remoto"))
}

fun respuestaPerfilEsVigente(
    idActivo: Long,
    uidActivo: String,
    sesionActiva: Long,
    uidFirebaseActual: String?,
    idRespuesta: Long,
    uidRespuesta: String,
    sesionRespuesta: Long
): Boolean = idActivo == idRespuesta &&
    uidActivo == uidRespuesta &&
    sesionActiva == sesionRespuesta &&
    uidFirebaseActual == uidRespuesta
