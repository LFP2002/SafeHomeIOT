package com.example.safehome

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

/** Acumula bytes, no callbacks. Memoria acotada incluso sin LF; UTF-8 puede llegar fragmentado. */
class ReceptorLineasSafeHome {
    private val buffer = ByteArray(ProtocoloSafeHome.MAXIMO_CARACTERES * 4 + 1)
    private var usados = 0
    private var descartando = false

    fun limpiar() { usados = 0; descartando = false }

    fun recibir(bytes: ByteArray, mensaje: (MensajeSafeHome) -> Unit, invalido: () -> Unit = {}) {
        bytes.forEach { byte ->
            if (byte == 10.toByte()) {
                if (!descartando) {
                    val resultado = try {
                        val longitud = if (usados > 0 && buffer[usados - 1] == 13.toByte()) usados - 1 else usados
                        val linea = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, longitud)).toString()
                        ProtocoloSafeHome.parsear(linea)
                    } catch (_: IllegalArgumentException) { null }
                    catch (_: CharacterCodingException) { null }
                    if (resultado != null) mensaje(resultado) else invalido()
                }
                limpiar()
            } else if (!descartando) {
                if (usados == buffer.size) { descartando = true; invalido() }
                else buffer[usados++] = byte
            }
        }
    }
}
