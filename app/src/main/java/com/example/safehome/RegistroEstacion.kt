package com.example.safehome

data class ErroresFormulario(val correo: String? = null, val clave: String? = null) {
    val esValido: Boolean get() = correo == null && clave == null
}

fun normalizarCorreo(correo: String): String = correo.trim { it.isWhitespace() && !it.isISOControl() }

fun validarFormularioInicio(correo: String, clave: String): ErroresFormulario {
    val normalizado = normalizarCorreo(correo)
    val error = when {
        normalizado.isEmpty() -> "Ingresa tu correo electrónico."
        normalizado.length > 254 -> "El correo es demasiado largo."
        normalizado.any { it.isWhitespace() || it.isISOControl() } -> "El correo no puede contener espacios ni controles."
        !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalizado) -> "Ingresa un correo válido."
        else -> null
    }
    return ErroresFormulario(error, if (clave.isEmpty()) "Ingresa tu contraseña." else null)
}

fun validarCorreoRecuperacion(correo: String): String? = validarFormularioInicio(correo, "x").correo

data class ErroresRegistroEstacion(val nombre: String? = null, val correo: String? = null,
    val clave: String? = null, val confirmacion: String? = null) {
    val esValido: Boolean get() = listOf(nombre, correo, clave, confirmacion).all { it == null }
}

fun validarRegistroEstacion(nombre: String, correo: String, clave: String, confirmacion: String) =
    ErroresRegistroEstacion(validarNombreVisible(nombre), validarCorreoRecuperacion(correo),
        validarContrasenaNueva(clave).firstOrNull(), when {
            confirmacion.isEmpty() -> "Confirma tu contraseña."
            clave != confirmacion -> "Las contraseñas no coinciden."
            else -> null
        })
