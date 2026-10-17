package com.example.safehome

import com.google.firebase.Timestamp

private val formatoNombreVisible = Regex("^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ' -]{2,40}$")

fun normalizarNombreVisible(nombre: String): String = nombre.trim().replace(Regex("\\s+"), " ")

fun validarNombreVisible(nombre: String): String? {
    val normalizado = normalizarNombreVisible(nombre)
    return when {
        normalizado.length !in 2..40 -> "El nombre debe tener entre 2 y 40 caracteres."
        !formatoNombreVisible.matches(normalizado) -> "Usa solo letras, espacios, apóstrofo o guion."
        else -> null
    }
}

data class HogarVisible(val nombre: String)
sealed interface ResultadoHogarVisible { data class Valido(val hogar: HogarVisible) : ResultadoHogarVisible; data object Invalido : ResultadoHogarVisible }

fun validarHogarVisible(datos: Map<String, Any?>): ResultadoHogarVisible {
    if (datos.keys != setOf("nombre", "creadoEn")) return ResultadoHogarVisible.Invalido
    val nombre = datos["nombre"] as? String ?: return ResultadoHogarVisible.Invalido
    if (validarNombreVisible(nombre) != null || nombre != normalizarNombreVisible(nombre) || datos["creadoEn"] !is Timestamp) return ResultadoHogarVisible.Invalido
    return ResultadoHogarVisible.Valido(HogarVisible(nombre))
}

fun nombreHogarSeguro(hogar: HogarVisible?): String = hogar?.nombre ?: "Mi hogar"
