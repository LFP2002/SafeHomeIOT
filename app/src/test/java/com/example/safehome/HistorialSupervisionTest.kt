package com.example.safehome

import com.example.safehome.iot.*
import org.junit.Assert.*
import org.junit.Test

class HistorialSupervisionTest {
    private fun nuevo(tipo: String = "PUERTA_ABIERTA", dispositivo: String = "estacion", fecha: Long = 2000) =
        mapOf("schemaVersion" to 1L, "tipo" to tipo, "dispositivo" to dispositivo, "creadoEn" to Instante(fecha))
    private fun antiguo(tipo: String) = mapOf("tipo" to tipo, "creadoEn" to Instante(2000))

    @Test fun admiteLosSeisTiposNuevosConDispositivoYFecha() {
        listOf("ESTACION_CONECTADA", "ESTACION_DESCONECTADA", "PUERTA_ABIERTA", "PUERTA_CERRADA").forEach {
            val evento = parsearEvento("evento", nuevo(it))!!
            assertEquals(it, evento.tipo)
            assertEquals("ESP32 Estación", evento.dispositivo)
            assertEquals(Instante(2000), evento.ocurridoEn)
            assertFalse(evento.anterior)
        }
        listOf("PORTATIL_CONECTADO", "PORTATIL_DESCONECTADO").forEach {
            assertEquals("ESP32 Portátil", parsearEvento("evento", nuevo(it, "portatil"))!!.dispositivo)
        }
    }
    @Test fun rechazaDispositivoQueNoCorrespondeAlTipo() {
        assertNull(parsearEvento("e", nuevo("ESTACION_CONECTADA", "portatil")))
        assertNull(parsearEvento("e", nuevo("PORTATIL_CONECTADO", "estacion")))
        assertNull(parsearEvento("e", nuevo(dispositivo = "movil")))
    }
    @Test fun rechazaTipoVersionIdOTimestampInvalidos() {
        assertNull(parsearEvento("", nuevo()))
        assertNull(parsearEvento("e", nuevo("HEARTBEAT")))
        assertNull(parsearEvento("e", nuevo() + ("schemaVersion" to 2L)))
        assertNull(parsearEvento("e", nuevo() + ("creadoEn" to 2000L)))
        assertNull(parsearEvento("e", nuevo() - "dispositivo"))
        assertNull(parsearEvento("e", nuevo() - "creadoEn"))
    }
    @Test fun mantienePuertaYAlarmasHistoricas() {
        listOf("PUERTA_ABIERTA", "PUERTA_CERRADA", "ALARMA_ADVERTENCIA", "ALARMA_CRITICA", "ALARMA_RECONOCIDA").forEach {
            val evento = parsearEvento("e", antiguo(it))!!
            assertTrue(evento.anterior)
            assertEquals(it, evento.tipo)
        }
    }
    @Test fun portatilHistoricoNoSeReinterpretaComoConexionMqtt() {
        listOf("PORTATIL_FUERA_RANGO", "PORTATIL_RECONECTADO").forEach {
            val evento = parsearEvento("e", antiguo(it))!!
            assertEquals(it, evento.tipo)
            assertFalse(evento.descripcion.contains("MQTT"))
            assertTrue(evento.anterior)
            assertTrue(evento.dispositivo.startsWith("Portátil (registro anterior"))
        }
    }
    @Test fun telefonoHistoricoNoSeConfundeConEsp32() {
        listOf("MOVIL_CONECTADO", "MOVIL_DESCONECTADO").forEach {
            val evento = parsearEvento("e", antiguo(it) + mapOf("uidMovil" to "uid-demo", "nombreMovil" to "Usuario Demo"))!!
            assertEquals("Teléfono (registro anterior)", evento.dispositivo)
            assertTrue(evento.descripcion.contains("no es conexión MQTT"))
        }
    }
    @Test fun rechazaTelefonoHistoricoSinIdentidadValida() {
        assertNull(parsearEvento("e", antiguo("MOVIL_CONECTADO")))
        assertNull(parsearEvento("e", antiguo("MOVIL_CONECTADO") + mapOf("uidMovil" to "", "nombreMovil" to "Usuario Demo")))
        assertNull(parsearEvento("e", antiguo("MOVIL_CONECTADO") + mapOf("uidMovil" to "uid-demo", "nombreMovil" to 5)))
    }
    @Test fun sistemaHistoricoExigeBooleanoConsistente() {
        assertNotNull(parsearEvento("e", antiguo("SISTEMA_ACTIVADO") + ("sistemaActivo" to true)))
        assertNotNull(parsearEvento("e", antiguo("SISTEMA_DESACTIVADO") + ("sistemaActivo" to false)))
        assertNull(parsearEvento("e", antiguo("SISTEMA_ACTIVADO") + ("sistemaActivo" to false)))
        assertNull(parsearEvento("e", antiguo("SISTEMA_ACTIVADO")))
    }
    @Test fun estadoHistoricoMantieneSemanticaAnterior() {
        val datos = antiguo("ESTADO_ACTUALIZADO") + mapOf("puertaAbierta" to true, "personaPresente" to false)
        val evento = parsearEvento("e", datos)!!
        assertTrue(evento.anterior)
        assertTrue(evento.descripcion.contains("no equivale a MQTT"))
        assertNull(parsearEvento("e", datos - "personaPresente"))
    }
    @Test fun reconocimientoHistoricoExigeSecuenciaPositiva() {
        assertNotNull(parsearEvento("e", antiguo("RECONOCIMIENTO_SOLICITADO") + ("secuenciaComando" to 1L)))
        assertNull(parsearEvento("e", antiguo("RECONOCIMIENTO_SOLICITADO") + ("secuenciaComando" to 0L)))
        assertNull(parsearEvento("e", antiguo("RECONOCIMIENTO_SOLICITADO") + ("secuenciaComando" to "1")))
    }
    @Test fun conservaFechaDeOcurrenciaOfflineAnterior() {
        val datos = antiguo("PUERTA_ABIERTA") + mapOf("ocurridoEnMillis" to 2000L, "sincronizadoEn" to Instante(9000))
        assertEquals(Instante(2000), parsearEvento("e", datos)!!.ocurridoEn)
        assertNull(parsearEvento("e", datos + ("ocurridoEnMillis" to 1000L)))
        assertNull(parsearEvento("e", datos - "sincronizadoEn"))
        assertNull(parsearEvento("e", datos + ("sincronizadoEn" to 9000L)))
    }
    @Test fun unEventoNuevoNoAceptaFormatoAntiguoSinVersion() {
        assertNull(parsearEvento("e", antiguo("ESTACION_CONECTADA")))
        assertNull(parsearEvento("e", antiguo("PORTATIL_DESCONECTADO")))
    }
    @Test fun heartbeatNoGeneraHistorialNiAunqueSeRepita() {
        val historial = prepararHistorial(List(20) { "hb-$it" to nuevo("HEARTBEAT", "portatil") })
        assertTrue(historial.eventos.isEmpty())
        assertEquals(20, historial.incompatibles)
    }
    @Test fun snapshotsDuplicadosNoDuplicanIdentidad() {
        val evento = "id-estable" to nuevo()
        assertEquals(1, prepararHistorial(listOf(evento, evento, evento)).eventos.size)
    }
    @Test fun transicionesRealesDistintasSeConservanAunqueTipoYFechaCoincidan() {
        val historial = prepararHistorial(listOf("id-a" to nuevo(), "id-b" to nuevo()))
        assertEquals(2, historial.eventos.size)
    }
    @Test fun ordenaPorOcurrenciaYDesempataPorId() {
        val historial = prepararHistorial(listOf("c" to nuevo(fecha = 1000), "b" to nuevo(), "a" to nuevo()))
        assertEquals(listOf("a", "b", "c"), historial.eventos.map { it.id })
    }
    @Test fun vacioYDocumentosIncompatiblesSonDistintos() {
        val vacio = prepararHistorial(emptyList())
        assertTrue(vacio.eventos.isEmpty())
        assertEquals(0, vacio.incompatibles)
        val mixto = prepararHistorial(listOf("ok" to nuevo(), "mal" to emptyMap()))
        assertEquals(1, mixto.eventos.size)
        assertEquals(1, mixto.incompatibles)
    }
    @Test fun limiteDeConsultaSeInformaSinInventarPaginaSiguiente() {
        assertFalse(prepararHistorial(List(99) { "$it" to nuevo() }).limiteAlcanzado)
        assertTrue(prepararHistorial(List(100) { "$it" to nuevo() }).limiteAlcanzado)
    }
}
