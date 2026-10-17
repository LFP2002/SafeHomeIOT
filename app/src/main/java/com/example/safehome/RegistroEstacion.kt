package com.example.safehome

import java.security.SecureRandom

private const val LONGITUD_CODIGO_VINCULACION = 12
private const val ALFABETO_CODIGO_VINCULACION = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

data class ErroresRegistroEstacion(val nombre: String? = null, val correo: String? = null, val clave: String? = null, val confirmacion: String? = null) {
    val esValido: Boolean get() = nombre == null && correo == null && clave == null && confirmacion == null
}

fun validarRegistroEstacion(nombre: String, correo: String, clave: String, confirmacion: String): ErroresRegistroEstacion {
    val errorClave = validarContrasenaNueva(clave).firstOrNull()
    return ErroresRegistroEstacion(
        nombre = validarNombreVisible(nombre),
        correo = validarCorreoRecuperacion(correo),
        clave = errorClave,
        confirmacion = when {
            confirmacion.isEmpty() -> "Confirma tu contraseña."
            clave != confirmacion -> "Las contraseñas no coinciden."
            else -> null
        }
    )
}

data class ErroresRegistroMovil(val base: ErroresRegistroEstacion, val codigo: String? = null) {
    val esValido: Boolean get() = base.esValido && codigo == null
}

fun validarRegistroMovil(nombre: String, correo: String, clave: String, confirmacion: String, codigo: String): ErroresRegistroMovil {
    val normalizado = normalizarCodigoVinculacion(codigo)
    return ErroresRegistroMovil(validarRegistroEstacion(nombre, correo, clave, confirmacion), if (codigoVinculacionEsValido(normalizado)) null else "Ingresa un código de 12 caracteres válido.")
}

fun generarCodigoVinculacion(aleatorio: SecureRandom = SecureRandom()): String = buildString(LONGITUD_CODIGO_VINCULACION) {
    repeat(LONGITUD_CODIGO_VINCULACION) { append(ALFABETO_CODIGO_VINCULACION[aleatorio.nextInt(ALFABETO_CODIGO_VINCULACION.length)]) }
}

fun codigoVinculacionEsValido(codigo: String): Boolean =
    codigo.length == LONGITUD_CODIGO_VINCULACION && codigo.all { it in ALFABETO_CODIGO_VINCULACION }

fun normalizarCodigoVinculacion(codigo: String): String = codigo.filterNot(Char::isWhitespace).uppercase()
