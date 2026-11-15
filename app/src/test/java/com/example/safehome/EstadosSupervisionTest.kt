package com.example.safehome

import com.example.safehome.iot.*
import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test

class EstadosSupervisionTest {
    private val dispositivo = mapOf("schemaVersion" to 1L, "mqtt" to "CONECTADO", "actualizadoEn" to Instante(2000))
    private val fisico = mapOf("schemaVersion" to 1L, "actualizadoEn" to Instante(2000), "puerta" to "ABIERTA", "alarma" to "CRITICA")

    @Test fun parseaLosTresEstadosMqttSinInventarPresencia() {
        ConexionMqtt.entries.forEach {
            assertEquals(it, parsearDispositivo(dispositivo + ("mqtt" to it.name))!!.mqtt)
        }
    }
    @Test fun ausenciaDeDocumentoOCamposNoSignificaConexion() {
        assertNull(parsearDispositivo(emptyMap()))
        assertNull(parsearDispositivo(dispositivo - "mqtt"))
        assertNull(parsearDispositivo(dispositivo - "actualizadoEn"))
        assertNull(parsearDispositivo(dispositivo - "schemaVersion"))
    }
    @Test fun rechazaVersionesYTiposIncorrectosDeDispositivo() {
        listOf(dispositivo + ("schemaVersion" to 2L), dispositivo + ("schemaVersion" to 1.0),
            dispositivo + ("mqtt" to true), dispositivo + ("mqtt" to "connected"),
            dispositivo + ("actualizadoEn" to 2000L)).forEach { assertNull(parsearDispositivo(it)) }
    }
    @Test fun heartbeatEsOpcionalYNoImplicaEstadoMqtt() {
        assertNull(parsearDispositivo(dispositivo)!!.ultimoHeartbeatEn)
        val desconectado = parsearDispositivo(dispositivo + mapOf("mqtt" to "DESCONECTADO", "ultimoHeartbeatEn" to Instante(1000)))!!
        assertEquals(ConexionMqtt.DESCONECTADO, desconectado.mqtt)
        assertEquals(Instante(1000), desconectado.ultimoHeartbeatEn)
    }
    @Test fun heartbeatInvalidoOFuturoNoSeAcepta() {
        listOf("ahora", 1000L, Instante(3000)).forEach { assertNull(parsearDispositivo(dispositivo + ("ultimoHeartbeatEn" to it))) }
    }
    @Test fun parseaPuertaYAlarmaIndependientes() {
        Puerta.entries.forEach { puerta -> Alarma.entries.forEach { alarma ->
            val resultado = parsearEstadoFisico(fisico + mapOf("puerta" to puerta.name, "alarma" to alarma.name))!!
            assertEquals(puerta, resultado.puerta)
            assertEquals(alarma, resultado.alarma)
        } }
    }
    @Test fun camposFisicosAusentesSonSinDatosNuncaNormalOCerrada() {
        val estado = parsearEstadoFisico(fisico - setOf("puerta", "alarma"))!!
        assertEquals(Puerta.SIN_DATOS, estado.puerta)
        assertEquals(Alarma.SIN_DATOS, estado.alarma)
        assertNull(estado.puertaCambiadaEn)
    }
    @Test fun rechazaValoresFisicosDesconocidos() {
        listOf(fisico + ("puerta" to true), fisico + ("alarma" to "OK"), fisico + ("schemaVersion" to 2L),
            fisico - "actualizadoEn", fisico + ("actualizadoEn" to 1000L)).forEach { assertNull(parsearEstadoFisico(it)) }
    }
    @Test fun fechaDeCambioConfirmadoNoSeReemplazaConActualizacion() {
        val estado = parsearEstadoFisico(fisico + ("puertaCambiadaEn" to Instante(1000)))!!
        assertEquals(Instante(1000), estado.puertaCambiadaEn)
        assertEquals(Instante(2000), estado.actualizadoEn)
    }
    @Test fun rechazaCambioDePuertaFuturoOConPuertaSinDatos() {
        assertNull(parsearEstadoFisico(fisico + ("puertaCambiadaEn" to Instante(3000))))
        assertNull(parsearEstadoFisico(fisico + mapOf("puerta" to "SIN_DATOS", "puertaCambiadaEn" to Instante(1000))))
    }
    @Test fun desconexionAdvierteSinBorrarPuertaNiAlarmaConocidas() {
        val estacion = Lectura.Dato(parsearDispositivo(dispositivo + ("mqtt" to "DESCONECTADO"))!!)
        val estado = Lectura.Dato(parsearEstadoFisico(fisico)!!)
        assertTrue(puertaPuedeEstarDesactualizada(estacion, estado))
        assertEquals(Puerta.ABIERTA, estado.valor.puerta)
        assertEquals(Alarma.CRITICA, estado.valor.alarma)
    }
    @Test fun cacheErrorYDesconocidoNoConfirmanVigenciaFisica() {
        val dato = Lectura.Dato(parsearDispositivo(dispositivo)!!)
        val estado = Lectura.Dato(parsearEstadoFisico(fisico)!!)
        assertFalse(puertaPuedeEstarDesactualizada(dato, estado))
        assertTrue(puertaPuedeEstarDesactualizada(dato.copy(desdeCache = true), estado))
        assertTrue(puertaPuedeEstarDesactualizada(dato, estado.copy(desdeCache = true)))
        listOf(Lectura.Cargando, Lectura.SinDatos, Lectura.Invalida, Lectura.Error("sin red"),
            Lectura.Dato(dato.valor.copy(mqtt = ConexionMqtt.DESCONOCIDO))).forEach {
            assertTrue(puertaPuedeEstarDesactualizada(it, estado))
        }
    }
    @Test fun adaptadorSoloConvierteTimestampNoNumeroNiTexto() {
        val convertido = adaptarTiempos(mapOf("fecha" to Timestamp(2, 0), "numero" to 2000L, "texto" to "2000"))
        assertEquals(Instante(2000), convertido["fecha"])
        assertEquals(2000L, convertido["numero"])
        assertEquals("2000", convertido["texto"])
    }
    @Test fun tiemposFueraDeRangoSonRechazados() {
        assertThrows(IllegalArgumentException::class.java) { Instante(-1) }
        assertThrows(IllegalArgumentException::class.java) { Instante(253402300800000L) }
        assertNull(parsearDispositivo(adaptarTiempos(dispositivo + ("actualizadoEn" to Timestamp(-1, 0)))))
    }
    @Test fun erroresFirestoreNoExponenContenidoArbitrario() {
        val secretoFicticio = "dato-privado-de-prueba"
        listOf(null, "PERMISSION_DENIED", "UNAUTHENTICATED", "UNAVAILABLE", "INTERNAL", secretoFicticio).forEach { codigo ->
            val mensaje = mensajeErrorConsulta(codigo)
            assertFalse(mensaje.contains(secretoFicticio))
            assertTrue(mensaje.isNotBlank())
        }
        assertTrue(mensajeErrorConsulta("PERMISSION_DENIED").contains("no autoriza"))
        assertTrue(mensajeErrorConsulta("UNAUTHENTICATED").contains("no está autenticada"))
        assertTrue(mensajeErrorConsulta("UNAVAILABLE").contains("no está disponible"))
    }
}
