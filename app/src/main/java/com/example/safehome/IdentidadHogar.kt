package com.example.safehome

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
