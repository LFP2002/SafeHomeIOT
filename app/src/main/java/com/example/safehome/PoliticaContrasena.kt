package com.example.safehome

const val LONGITUD_MINIMA_CONTRASENA = 12
const val LONGITUD_MAXIMA_CONTRASENA = 32

fun limitarLongitudContrasena(clave: String): String =
    clave.take(LONGITUD_MAXIMA_CONTRASENA)

fun validarContrasenaNueva(clave: String): List<String> = buildList {
    if (clave.length !in LONGITUD_MINIMA_CONTRASENA..LONGITUD_MAXIMA_CONTRASENA) {
        add("La contraseña debe tener entre 12 y 32 caracteres.")
    }
    if (clave.none(Char::isUpperCase)) add("Incluye al menos una letra mayúscula.")
    if (clave.none(Char::isLowerCase)) add("Incluye al menos una letra minúscula.")
    if (clave.none(Char::isDigit)) add("Incluye al menos un número.")
}
