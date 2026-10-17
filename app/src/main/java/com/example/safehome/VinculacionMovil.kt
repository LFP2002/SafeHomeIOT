package com.example.safehome

import com.google.firebase.Timestamp

data class CodigoVinculacion(val hogarId: String, val propietarioUid: String)
sealed interface ResultadoCodigoVinculacion { data class Valido(val codigo: CodigoVinculacion) : ResultadoCodigoVinculacion; data object Invalido : ResultadoCodigoVinculacion }

fun validarCodigoVinculacion(datos: Map<String, Any?>): ResultadoCodigoVinculacion {
    if (datos.keys != setOf("hogarId", "propietarioUid", "activo", "creadoEn")) return ResultadoCodigoVinculacion.Invalido
    val hogarId = datos["hogarId"] as? String ?: return ResultadoCodigoVinculacion.Invalido
    val propietarioUid = datos["propietarioUid"] as? String ?: return ResultadoCodigoVinculacion.Invalido
    if (hogarId.isBlank() || propietarioUid.isBlank() || datos["activo"] != true || datos["creadoEn"] !is Timestamp) return ResultadoCodigoVinculacion.Invalido
    return ResultadoCodigoVinculacion.Valido(CodigoVinculacion(hogarId, propietarioUid))
}

data class SolicitudVinculacion(val uidMovil: String, val nombre: String, val codigo: String, val estado: String)
sealed interface ResultadoSolicitudVinculacion { data class Valida(val solicitud: SolicitudVinculacion) : ResultadoSolicitudVinculacion; data object Invalida : ResultadoSolicitudVinculacion }

fun validarSolicitudVinculacion(datos: Map<String, Any?>): ResultadoSolicitudVinculacion {
    val uid = datos["uidMovil"] as? String ?: return ResultadoSolicitudVinculacion.Invalida
    val nombre = datos["nombre"] as? String ?: return ResultadoSolicitudVinculacion.Invalida
    val codigo = datos["codigo"] as? String ?: return ResultadoSolicitudVinculacion.Invalida
    val estado = datos["estado"] as? String ?: return ResultadoSolicitudVinculacion.Invalida
    if (uid.isBlank() || validarNombreVisible(nombre) != null || !codigoVinculacionEsValido(codigo) || estado !in setOf("PENDIENTE", "APROBADA", "RECHAZADA", "DESVINCULADA") || datos["creadoEn"] !is Timestamp) return ResultadoSolicitudVinculacion.Invalida
    return ResultadoSolicitudVinculacion.Valida(SolicitudVinculacion(uid, nombre, codigo, estado))
}

fun aprobacionConservaInmutables(anterior: SolicitudVinculacion, posterior: SolicitudVinculacion): Boolean =
    anterior.uidMovil == posterior.uidMovil && anterior.nombre == posterior.nombre && anterior.codigo == posterior.codigo && anterior.estado == "PENDIENTE" && posterior.estado == "APROBADA"

fun desvinculacionEsValida(perfil: PerfilUsuario, solicitud: SolicitudVinculacion, movilUid: String): Boolean =
    perfil.rol == RolUsuario.MOVIL && solicitud.uidMovil == movilUid && solicitud.estado == "APROBADA"

fun puedeEliminarCuentaPropia(rol: RolUsuario): Boolean = rol in setOf(RolUsuario.MOVIL, RolUsuario.MOVIL_DESVINCULADO, RolUsuario.MOVIL_RECHAZADO)
fun puedeSolicitarNuevaVinculacion(rol: RolUsuario): Boolean = rol in setOf(RolUsuario.MOVIL_DESVINCULADO, RolUsuario.MOVIL_RECHAZADO)
fun estacionPuedePrepararEliminacion(movilesAprobados: Int, solicitudesPendientes: Int): Boolean = movilesAprobados == 0 && solicitudesPendientes == 0

fun transicionSolicitudEstacionEsValida(anterior: String, posterior: String): Boolean =
    (anterior == "PENDIENTE" && posterior in setOf("APROBADA", "RECHAZADA")) ||
        (anterior == "APROBADA" && posterior == "DESVINCULADA")

fun retiroEstacionEsValido(rolAnterior: String, activoAnterior: Boolean, rolPosterior: String, activoPosterior: Boolean): Boolean =
    rolAnterior in setOf("estacion", "simulador") && activoAnterior && rolPosterior == "estacion_eliminada" && !activoPosterior

fun eliminacionMovilPropiaEsValida(uidSolicitante: String, uidObjetivo: String, rol: RolUsuario): Boolean =
    uidSolicitante == uidObjetivo && puedeEliminarCuentaPropia(rol)

fun aprobacionServidorEsValida(
    perfil: Map<String, Any?>,
    solicitud: Map<String, Any?>,
    movil: Map<String, Any?>,
    uidMovil: String,
    hogarId: String,
    nombre: String
): Boolean = perfil == mapOf("activo" to true, "rol" to "movil", "hogarId" to hogarId, "nombre" to nombre) &&
    solicitud["uidMovil"] == uidMovil && solicitud["nombre"] == nombre && solicitud["estado"] == "APROBADA" &&
    solicitud["codigo"] is String && solicitud["creadoEn"] is Timestamp && solicitud["respondidaEn"] is Timestamp &&
    movil == mapOf("uidMovil" to uidMovil, "nombre" to nombre, "vinculadoEn" to movil["vinculadoEn"]) && movil["vinculadoEn"] is Timestamp
