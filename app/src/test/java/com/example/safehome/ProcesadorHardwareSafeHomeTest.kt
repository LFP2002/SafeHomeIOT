package com.example.safehome

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test

class ProcesadorHardwareSafeHomeTest {
    private val verificado = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_VERIFICADO,
        idDispositivo = "SH-E-001", rolDispositivo = "STATION")
    private fun mensaje(dato: String, valor: String, tipo: TipoMensajeSafeHome = TipoMensajeSafeHome.STATE) =
        ProtocoloSafeHome.parsear(ProtocoloSafeHome.serializar(MensajeSafeHome(100, tipo, dato, valor)))
    private fun recibir(estado: EstadoHardwareSafeHome, dato: String, valor: String,
        tipo: TipoMensajeSafeHome = TipoMensajeSafeHome.STATE) = procesarMensajeHardware(estado, mensaje(dato, valor, tipo))
    private fun apagando() = iniciarOperacionHardware(verificado.copy(sistema = SistemaHardware.ENCENDIDO), AccionHardware.APAGAR, 12)

    @Test fun systemOnActualizaEncendido() {
        assertEquals(SistemaHardware.ENCENDIDO, recibir(verificado, "SYSTEM", "ON").estado.sistema)
    }
    @Test fun systemOffActualizaApagado() {
        assertEquals(SistemaHardware.APAGADO, recibir(verificado, "SYSTEM", "OFF").estado.sistema)
    }
    @Test fun doorOpenActualizaAbierta() {
        assertEquals(PuertaHardware.ABIERTA, recibir(verificado, "DOOR", "OPEN").estado.puerta)
    }
    @Test fun doorClosedActualizaCerrada() {
        assertEquals(PuertaHardware.CERRADA, recibir(verificado, "DOOR", "CLOSED").estado.puerta)
    }
    @Test fun portablePresentActualizaPresente() {
        assertEquals(PortatilHardware.PRESENTE, recibir(verificado, "PORTABLE", "PRESENT").estado.portatil)
    }
    @Test fun portableOutActualizaFueraRango() {
        assertEquals(PortatilHardware.FUERA_RANGO, recibir(verificado, "PORTABLE", "OUT").estado.portatil)
    }
    @Test fun portableUnknownInvalidaPresenciaAnterior() {
        assertEquals(PortatilHardware.DESCONOCIDO, recibir(verificado.copy(portatil = PortatilHardware.PRESENTE), "PORTABLE", "UNKNOWN").estado.portatil)
    }
    @Test fun alarmNoneActualizaNinguna() {
        assertEquals(AlarmaHardware.NINGUNA, recibir(verificado, "ALARM", "NONE").estado.alarma)
    }
    @Test fun alarmWarningActualizaAdvertencia() {
        assertEquals(AlarmaHardware.ADVERTENCIA, recibir(verificado, "ALARM", "WARNING").estado.alarma)
    }
    @Test fun alarmCriticalActualizaCritica() {
        assertEquals(AlarmaHardware.CRITICA, recibir(verificado, "ALARM", "CRITICAL").estado.alarma)
    }
    @Test fun ackCorrectoSoloCompletaPrimeraFase() {
        val pendiente = apagando()
        assertEquals(SistemaHardware.ENCENDIDO, pendiente.sistema)
        val ack = recibir(pendiente, "12", "OK", TipoMensajeSafeHome.ACK)
        assertTrue(ack.estado.operacion!!.ackRecibido)
        assertEquals(SistemaHardware.ENCENDIDO, ack.estado.sistema)
        assertNull(ack.evento)
        assertNull(ack.sistemaParaSincronizar)
    }
    @Test fun systemOffRequiereAckYStateOffPosterior() {
        val ack = recibir(apagando(), "12", "OK", TipoMensajeSafeHome.ACK).estado
        val noEsperado = recibir(ack, "SYSTEM", "ON")
        assertNotNull(noEsperado.estado.operacion)
        val final = recibir(noEsperado.estado, "SYSTEM", "OFF")
        assertNull(final.estado.operacion)
        assertEquals(SistemaHardware.APAGADO, final.estado.sistema)
        assertEquals(EventoFisico.SISTEMA_DESACTIVADO, final.evento)
        assertEquals(false, final.sistemaParaSincronizar)
    }
    @Test fun stateAntesDelAckNoCompletaOperacionNiAckConfirmaPorSiSolo() {
        val state = recibir(apagando(), "SYSTEM", "OFF").estado
        assertNotNull(state.operacion)
        val ack = recibir(state, "12", "OK", TipoMensajeSafeHome.ACK).estado
        assertNotNull(ack.operacion)
        assertNull(recibir(ack, "SYSTEM", "OFF").estado.operacion)
    }
    @Test fun ackAjenoNoConfirmaComando() {
        val resultado = recibir(apagando(), "11", "OK", TipoMensajeSafeHome.ACK)
        assertFalse(resultado.estado.operacion!!.ackRecibido)
        assertNotNull(recibir(resultado.estado, "SYSTEM", "OFF").estado.operacion)
    }
    @Test fun errAsociadoLimpiaOperacionSinAlterarEstadoYConsultaStatus() {
        val ajeno = recibir(apagando(), "11", "FAILED", TipoMensajeSafeHome.ERR)
        assertNotNull(ajeno.estado.operacion)
        val error = recibir(ajeno.estado, "12", "FAILED", TipoMensajeSafeHome.ERR)
        assertNull(error.estado.operacion)
        assertNotNull(error.estado.errorOperacion)
        assertEquals(SistemaHardware.ENCENDIDO, error.estado.sistema)
        assertTrue(error.solicitarEstado)
    }
    @Test fun timeoutNoDesconectaNiCambiaEstadoYConsultaStatus() {
        val pendiente = apagando()
        assertEquals(pendiente, expirarOperacionHardware(pendiente, 11).estado)
        val resultado = expirarOperacionHardware(pendiente, 12)
        assertTrue(resultado.estado.verificado)
        assertEquals(SistemaHardware.ENCENDIDO, resultado.estado.sistema)
        assertNull(resultado.estado.operacion)
        assertEquals("Estación no confirmó la acción.", resultado.estado.errorOperacion)
        assertTrue(resultado.solicitarEstado)
    }
    @Test fun desconexionInvalidaTodosLosEstadosYOperacion() {
        val estado = apagando().copy(puerta = PuertaHardware.ABIERTA, portatil = PortatilHardware.PRESENTE, alarma = AlarmaHardware.CRITICA)
        val fuera = invalidarEstadoHardware(estado)
        assertFalse(fuera.conectado)
        assertNull(fuera.operacion)
        assertEquals(SistemaHardware.DESCONOCIDO, fuera.sistema)
        assertEquals(PuertaHardware.DESCONOCIDA, fuera.puerta)
        assertEquals(PortatilHardware.DESCONOCIDO, fuera.portatil)
        assertEquals(AlarmaHardware.DESCONOCIDA, fuera.alarma)
        assertEquals(fuera, recibir(fuera, "SYSTEM", "OFF").estado)
    }
    @Test fun reconexionExigeVerificacionYStatusAllSinRecuperarEstadosViejos() {
        val reinicio = invalidarEstadoHardware(verificado.copy(sistema = SistemaHardware.APAGADO))
            .copy(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO)
        val temprano = recibir(reinicio, "SYSTEM", "ON")
        assertEquals(SistemaHardware.DESCONOCIDO, temprano.estado.sistema)
        val role = recibir(temprano.estado, "ROLE", "STATION")
        assertFalse(role.solicitarEstado)
        val device = recibir(role.estado, "DEVICE", "SH-E-001")
        assertTrue(device.solicitarEstado)
        assertTrue(device.estado.verificado)
        assertEquals(SistemaHardware.DESCONOCIDO, device.estado.sistema)
        assertFalse(recibir(device.estado, "ROLE", "STATION").solicitarEstado)
        var estado = device.estado
        listOf("SYSTEM" to "ON", "DOOR" to "CLOSED", "PORTABLE" to "PRESENT", "ALARM" to "NONE").forEach { (dato, valor) ->
            val resultado = recibir(estado, dato, valor)
            assertNull(resultado.evento) // El snapshot inicial no es una transición.
            estado = resultado.estado
        }
        assertEquals(SistemaHardware.ENCENDIDO, estado.sistema)
        assertEquals(PuertaHardware.CERRADA, estado.puerta)
        assertEquals(PortatilHardware.PRESENTE, estado.portatil)
        assertEquals(AlarmaHardware.NINGUNA, estado.alarma)
    }
    @Test fun eventYStateRepetidosGeneranSoloUnaTransicion() {
        var estado = verificado.copy(puerta = PuertaHardware.CERRADA)
        val eventos = mutableListOf<EventoFisico>()
        repeat(3) {
            val evento = recibir(estado, "DOOR", "OPENED", TipoMensajeSafeHome.EVENT)
            assertNull(evento.evento)
            assertTrue(evento.solicitarEstado)
            val state = recibir(evento.estado, "DOOR", "OPEN")
            state.evento?.let(eventos::add)
            estado = state.estado
        }
        assertEquals(listOf(EventoFisico.PUERTA_ABIERTA), eventos)
        assertEquals(EventoFisico.PUERTA_CERRADA, recibir(estado, "DOOR", "CLOSED").evento)
    }
    @Test fun heartbeatDigitalNoEsEntradaDelProcesadorFisico() {
        var estado = verificado.copy(portatil = PortatilHardware.PRESENTE)
        // El clasificador digital puede pasar a DESCONECTADO sin producir un STATE PORTABLE.
        val digital = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1, 1))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(digital, 100_000))
        estado = recibir(estado, "PING", "OK", TipoMensajeSafeHome.EVENT).estado
        assertEquals(PortatilHardware.PRESENTE, estado.portatil)
        assertEquals(PortatilHardware.FUERA_RANGO, recibir(estado, "PORTABLE", "OUT").estado.portatil)
    }
    @Test fun rolePortableRechazadoInvalidaInclusoEstadoPrevio() {
        val resultado = recibir(verificado.copy(sistema = SistemaHardware.ENCENDIDO), "ROLE", "PORTABLE")
        assertFalse(resultado.estado.verificado)
        assertEquals(ConexionEstacionIoT.ERROR, resultado.estado.conexion)
        assertEquals(SistemaHardware.DESCONOCIDO, resultado.estado.sistema)
    }
    @Test fun reconocerAlarmaRequiereAckYNoneYRegistraUnaVez() {
        val pendiente = iniciarOperacionHardware(verificado.copy(alarma = AlarmaHardware.CRITICA), AccionHardware.RECONOCER_ALARMA, 20)
        val ack = recibir(pendiente, "20", "OK", TipoMensajeSafeHome.ACK).estado
        assertEquals(AlarmaHardware.CRITICA, ack.alarma)
        val fin = recibir(ack, "ALARM", "NONE")
        assertNull(fin.estado.operacion)
        assertEquals(EventoFisico.ALARMA_RECONOCIDA, fin.evento)
        assertNull(recibir(fin.estado, "ALARM", "NONE").evento)
        assertNull(recibir(fin.estado, "ALARM", "ACKNOWLEDGED", TipoMensajeSafeHome.EVENT).evento)
    }
    @Test fun noneSinComandoNoInventaReconocimiento() {
        val resultado = recibir(verificado.copy(alarma = AlarmaHardware.CRITICA), "ALARM", "NONE")
        assertNull(resultado.evento)
    }
    @Test fun transicionesDePortatilYAlarmaNoSeDuplican() {
        val fuera = recibir(verificado.copy(portatil = PortatilHardware.PRESENTE), "PORTABLE", "OUT")
        assertEquals(EventoFisico.PORTATIL_FUERA_RANGO, fuera.evento)
        assertNull(recibir(fuera.estado, "PORTABLE", "OUT").evento)
        assertEquals(EventoFisico.PORTATIL_RECONECTADO, recibir(fuera.estado, "PORTABLE", "PRESENT").evento)
        val warning = recibir(verificado.copy(alarma = AlarmaHardware.NINGUNA), "ALARM", "WARNING")
        assertEquals(EventoFisico.ALARMA_ADVERTENCIA, warning.evento)
        assertNull(recibir(warning.estado, "ALARM", "WARNING").evento)
        assertEquals(EventoFisico.ALARMA_CRITICA, recibir(warning.estado, "ALARM", "CRITICAL").evento)
    }
    @Test fun comandosNoSeAcumulanNiSePermitenSinVerificacion() {
        val primero = apagando()
        assertEquals(primero, iniciarOperacionHardware(primero, AccionHardware.ENCENDER, 13))
        val sinVerificar = verificado.copy(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO)
        assertEquals(sinVerificar, iniciarOperacionHardware(sinVerificar, AccionHardware.APAGAR, 14))
    }
    @Test fun valoresDesconocidosNoAlteranEstadoFisico() {
        val estado = verificado.copy(sistema = SistemaHardware.ENCENDIDO)
        val resultado = recibir(estado, "SYSTEM", "INVALID")
        assertEquals(estado.sistema, resultado.estado.sistema)
        assertNull(resultado.evento)
        assertNull(resultado.sistemaParaSincronizar)
    }
    @Test fun crcCorruptoYTextoAjenoNoLleganAlProcesador() {
        val serializado = ProtocoloSafeHome.serializar(MensajeSafeHome(1, TipoMensajeSafeHome.STATE, "SYSTEM", "ON"))
        listOf(serializado.replace("ON", "OFF"), "SYSTEM OFF\n").forEach { linea ->
            var estado = verificado
            val valido = runCatching { ProtocoloSafeHome.parsear(linea) }.getOrNull()
            if (valido != null) estado = procesarMensajeHardware(estado, valido).estado
            assertEquals(verificado, estado)
        }
    }
    @Test fun historialFisicoConservaFormatoSistemaYValidaNuevosTipos() {
        EventoFisico.entries.forEach { tipo ->
            val datos = datosEventoFisico(tipo, Timestamp(0, 0))
            val evento = (validarEventoHistorial(datos) as ResultadoEventoHistorial.Valido).evento
            assertEquals(listOf(tipo.texto), textoEventoHistorial(evento))
            assertEquals(if (tipo in setOf(EventoFisico.SISTEMA_ACTIVADO, EventoFisico.SISTEMA_DESACTIVADO)) 3 else 2, datos.size)
            assertEquals(ResultadoEventoHistorial.Invalido, validarEventoHistorial(datos + ("extra" to true)))
        }
    }
    @Test fun systemOnConfirmaSinEstadoOptimista() {
        val pendiente = iniciarOperacionHardware(verificado.copy(sistema = SistemaHardware.APAGADO), AccionHardware.ENCENDER, 5)
        assertEquals(SistemaHardware.APAGADO, pendiente.sistema)
        val ack = recibir(pendiente, "5", "OK", TipoMensajeSafeHome.ACK).estado
        val confirmado = recibir(ack, "SYSTEM", "ON")
        assertNull(confirmado.estado.operacion)
        assertEquals(EventoFisico.SISTEMA_ACTIVADO, confirmado.evento)
    }
}
