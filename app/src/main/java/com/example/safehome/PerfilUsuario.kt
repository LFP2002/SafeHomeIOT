package com.example.safehome

enum class RolUsuario { ESTACION }
data class PerfilUsuario(val rol: RolUsuario, val hogarId: String, val nombreVisible: String)

sealed interface ResultadoPerfil {
    data class Valido(val perfil: PerfilUsuario) : ResultadoPerfil
    data object RolNoAdmitido : ResultadoPerfil
    data object Invalido : ResultadoPerfil
}

fun validarPerfilUsuario(datos: Map<String, Any?>): ResultadoPerfil {
    val rol = datos["rol"] as? String ?: return ResultadoPerfil.Invalido
    if (rol != "estacion") return ResultadoPerfil.RolNoAdmitido
    val hogar = datos["hogarId"] as? String ?: return ResultadoPerfil.Invalido
    val nombre = datos["nombre"] as? String ?: return ResultadoPerfil.Invalido
    if (datos["activo"] != true || !Regex("[A-Za-z0-9_-]{1,64}").matches(hogar) ||
        validarNombreVisible(nombre) != null || nombre != normalizarNombreVisible(nombre)) return ResultadoPerfil.Invalido
    return ResultadoPerfil.Valido(PerfilUsuario(RolUsuario.ESTACION, hogar, nombre))
}
