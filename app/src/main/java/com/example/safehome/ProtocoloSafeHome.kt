package com.example.safehome

enum class TipoMensajeSafeHome { CMD, STATE, EVENT, ACK, ERR }

data class MensajeSafeHome(
    val secuencia: Long,
    val tipo: TipoMensajeSafeHome,
    val dato: String,
    val valor: String
)

/** SH1 usa UTF-8. El límite excluye el terminador LF o CRLF.
 * CRC detecta corrupción accidental; no es autenticación ni cifrado. */
object ProtocoloSafeHome {
    const val MAXIMO_CARACTERES = 256

    fun calcularCrc(contenido: String): String {
        var crc = 0xFFFF
        contenido.toByteArray(Charsets.UTF_8).forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 0xFFFF
            }
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }

    fun verificarCrc(contenido: String, crc: String): Boolean =
        Regex("[0-9A-F]{4}").matches(crc) && calcularCrc(contenido) == crc

    private fun validar(mensaje: MensajeSafeHome) {
        require(mensaje.secuencia >= 0) { "Secuencia inválida" }
        listOf(mensaje.dato, mensaje.valor).forEach { campo ->
            require(campo.isNotBlank() && campo.none { it == '|' || it.isISOControl() }) { "Campo inválido" }
        }
        if (mensaje.tipo == TipoMensajeSafeHome.ACK) {
            require(Regex("[0-9]+").matches(mensaje.dato) && mensaje.dato.toLongOrNull() != null) { "ACK inválido" }
        }
    }

    fun serializar(mensaje: MensajeSafeHome): String {
        validar(mensaje)
        val contenido = "SH1|${mensaje.secuencia}|${mensaje.tipo}|${mensaje.dato}|${mensaje.valor}"
        val linea = "$contenido|${calcularCrc(contenido)}"
        require(linea.length <= MAXIMO_CARACTERES) { "Mensaje demasiado largo" }
        return "$linea\n"
    }

    /** Acepta una línea sin terminador, con LF o con CRLF. */
    fun parsear(entrada: String): MensajeSafeHome {
        val linea = if (entrada.endsWith("\r\n")) entrada.dropLast(2)
            else if (entrada.endsWith('\n')) entrada.dropLast(1) else entrada
        require(linea.length <= MAXIMO_CARACTERES && '\n' !in linea && '\r' !in linea) { "Línea inválida" }
        val campos = linea.split('|')
        require(campos.size == 6 && campos[0] == "SH1") { "Formato o versión inválidos" }
        require(Regex("[0-9]+").matches(campos[1])) { "Secuencia inválida" }
        val secuencia = requireNotNull(campos[1].toLongOrNull()) { "Secuencia inválida" }
        val tipo = requireNotNull(TipoMensajeSafeHome.entries.find { it.name == campos[2] }) { "Tipo desconocido" }
        require(verificarCrc(linea.substringBeforeLast('|'), campos[5])) { "CRC inválido" }
        return MensajeSafeHome(secuencia, tipo, campos[3], campos[4]).also(::validar)
    }
}

enum class ValidacionEstacion { PENDIENTE, VERIFICADA, RECHAZADA }

fun validarHandshakeEstacion(id: String?, rol: String?): ValidacionEstacion = when {
    id != null && !Regex("SH-E-[A-Za-z0-9_-]{1,64}").matches(id) -> ValidacionEstacion.RECHAZADA
    rol != null && rol != "STATION" -> ValidacionEstacion.RECHAZADA
    id == null || rol == null -> ValidacionEstacion.PENDIENTE
    else -> ValidacionEstacion.VERIFICADA
}
