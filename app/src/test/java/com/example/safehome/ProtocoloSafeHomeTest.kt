package com.example.safehome

import org.junit.Assert.*
import org.junit.Test

class ProtocoloSafeHomeTest {
    private val mensaje = MensajeSafeHome(25, TipoMensajeSafeHome.CMD, "SYSTEM", "OFF")
    private fun linea(contenido: String) = "$contenido|${ProtocoloSafeHome.calcularCrc(contenido)}\n"
    private fun rechaza(accion: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { accion() }
    }

    @Test fun serializacionConservaMensaje() {
        val serializado = ProtocoloSafeHome.serializar(mensaje)
        assertTrue(serializado.endsWith('\n'))
        assertEquals(mensaje, ProtocoloSafeHome.parsear(serializado))
        assertEquals(mensaje, ProtocoloSafeHome.parsear(serializado.dropLast(1)))
    }

    @Test fun crcDeterministaYVectorOficialCcittFalse() {
        assertEquals("29B1", ProtocoloSafeHome.calcularCrc("123456789"))
        assertEquals(ProtocoloSafeHome.calcularCrc("SH1|25|CMD|SYSTEM|OFF"),
            ProtocoloSafeHome.calcularCrc("SH1|25|CMD|SYSTEM|OFF"))
        assertTrue(ProtocoloSafeHome.verificarCrc("123456789", "29B1"))
        assertFalse(ProtocoloSafeHome.verificarCrc("123456789", "29b1"))
    }

    @Test fun caracterAlteradoInvalidaCrc() {
        rechaza { ProtocoloSafeHome.parsear(ProtocoloSafeHome.serializar(mensaje).replace("OFF", "ONF")) }
    }

    @Test fun rechazaOtraVersionAunqueCrcSeaValido() {
        rechaza { ProtocoloSafeHome.parsear(linea("SH2|25|CMD|SYSTEM|OFF")) }
    }

    @Test fun rechazaTipoDesconocido() {
        rechaza { ProtocoloSafeHome.parsear(linea("SH1|25|UNKNOWN|SYSTEM|OFF")) }
    }

    @Test fun rechazaSecuenciaNegativa() {
        rechaza { ProtocoloSafeHome.parsear(linea("SH1|-1|CMD|SYSTEM|OFF")) }
        rechaza { ProtocoloSafeHome.serializar(mensaje.copy(secuencia = -1)) }
    }

    @Test fun rechazaSecuenciaNoNumericaODesbordada() {
        listOf("abc", "+1", "9223372036854775808").forEach {
            rechaza { ProtocoloSafeHome.parsear(linea("SH1|$it|CMD|SYSTEM|OFF")) }
        }
    }

    @Test fun rechazaLineaDemasiadoLarga() {
        rechaza { ProtocoloSafeHome.parsear(linea("SH1|1|STATE|DEVICE|${"A".repeat(256)}")) }
        rechaza { ProtocoloSafeHome.serializar(mensaje.copy(valor = "A".repeat(256))) }
    }

    @Test fun rechazaCaracteresProhibidosAlSerializarYParsear() {
        listOf("A|B", "A\nB", "A\rB").forEach { campo ->
            rechaza { ProtocoloSafeHome.serializar(mensaje.copy(dato = campo)) }
            rechaza { ProtocoloSafeHome.serializar(mensaje.copy(valor = campo)) }
            rechaza { ProtocoloSafeHome.parsear(linea("SH1|1|STATE|DEVICE|$campo")) }
        }
    }

    @Test fun reconoceAckYLaSecuenciaConfirmada() {
        val ack = ProtocoloSafeHome.parsear(linea("SH1|200|ACK|101|OK"))
        assertEquals(TipoMensajeSafeHome.ACK, ack.tipo)
        assertEquals(200L, ack.secuencia)
        assertEquals("101", ack.dato)
        assertEquals("OK", ack.valor)
        assertEquals(ack, ProtocoloSafeHome.parsear(ProtocoloSafeHome.serializar(ack)))
    }

    @Test fun rechazaAckSinSecuenciaValida() {
        listOf("-1", "comando", "9223372036854775808").forEach {
            rechaza { ProtocoloSafeHome.parsear(linea("SH1|200|ACK|$it|OK")) }
        }
    }

    @Test fun handshakeAceptaSoloEstacionConIdValido() {
        listOf("SH-E-001", "SH-E-xxx", "SH-E-A", "SH-E-aZ09_-", "SH-E-${"A".repeat(64)}").forEach { id ->
            assertEquals(id, ValidacionEstacion.VERIFICADA, validarHandshakeEstacion(id, "STATION"))
            assertEquals(id, ValidacionEstacion.PENDIENTE, validarHandshakeEstacion(id, null))
        }
    }

    @Test fun handshakeRechazaRolesIncorrectosInclusoSinDevice() {
        listOf("PORTABLE", "MOBILE", "UNKNOWN", "station", "STATION ", " STATION", "", " ").forEach { rol ->
            listOf(null, "SH-E-001").forEach { id ->
                assertEquals("id=$id rol=$rol", ValidacionEstacion.RECHAZADA, validarHandshakeEstacion(id, rol))
            }
        }
    }

    @Test fun handshakePendienteSoloConCamposAusentesYValoresPresentesValidos() {
        assertEquals(ValidacionEstacion.PENDIENTE, validarHandshakeEstacion(null, null))
        assertEquals(ValidacionEstacion.PENDIENTE, validarHandshakeEstacion("SH-E-001", null))
        assertEquals(ValidacionEstacion.PENDIENTE, validarHandshakeEstacion(null, "STATION"))
    }

    @Test fun handshakeRechazaDeviceInvalidoInclusoSinRole() {
        listOf("", " ", "SH-E-", "SH-P-001", "SH-M-001", "sh-e-001", "SH-E-uno dos",
            " SH-E-001", "SH-E-001 ", "SH-E-á", "SH-E-a.b", "SH-E-a/b", "SH-E-a|b",
            "SH-E-a\n", "SH-E-a\r", "SH-E-${"A".repeat(65)}").forEach { id ->
            listOf(null, "STATION", "PORTABLE").forEach { rol ->
                assertEquals("id=$id rol=$rol", ValidacionEstacion.RECHAZADA, validarHandshakeEstacion(id, rol))
            }
        }
    }

    @Test fun aceptaCrLfPeroNoCrIncrustado() {
        assertEquals(mensaje, ProtocoloSafeHome.parsear(ProtocoloSafeHome.serializar(mensaje).replace("\n", "\r\n")))
        rechaza { ProtocoloSafeHome.parsear(linea("SH1|25|CMD|SYS\rTEM|OFF")) }
    }

    @Test fun rechazaCamposVaciosOCantidadIncorrecta() {
        listOf("SH1|1|CMD||OFF", "SH1|1|CMD|SYSTEM|", "SH1|1|CMD|SYSTEM", "SH1|1|CMD|SYSTEM|OFF|extra").forEach {
            rechaza { ProtocoloSafeHome.parsear(linea(it)) }
        }
    }

    @Test fun estadosFisicosInicialesNoInventanPresencia() {
        val estado = EstadoHardwareSafeHome()
        assertFalse(estado.conectado)
        assertEquals(SistemaHardware.DESCONOCIDO, estado.sistema)
        assertEquals(PuertaHardware.DESCONOCIDA, estado.puerta)
        assertEquals(PortatilHardware.DESCONOCIDO, estado.portatil)
        assertEquals(AlarmaHardware.DESCONOCIDA, estado.alarma)
    }
}
