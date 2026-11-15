package com.example.safehome

import com.example.safehome.iot.*
import com.example.safehome.presentacion.*
import org.junit.Assert.*
import org.junit.Test

class PresentacionVisualTest {
    @Test fun conexionTieneEstadosDistintosSinInferirPresencia() {
        val estados = ConexionMqtt.entries.map(::visualConexion)
        assertEquals(3, estados.map { it.icono }.distinct().size)
        assertEquals(3, estados.map { it.tono }.distinct().size)
        assertTrue(estados.none { it.texto.contains("presencia", ignoreCase = true) })
        assertEquals(TonoEstado.NEUTRO, visualConexion(ConexionMqtt.DESCONOCIDO).tono)
    }

    @Test fun puertaDesconocidaNoPareceCerrada() {
        assertEquals(IconoSafeHome.DESCONOCIDO, visualPuerta(Puerta.SIN_DATOS).icono)
        assertNotEquals(visualPuerta(Puerta.ABIERTA).icono, visualPuerta(Puerta.CERRADA).icono)
    }

    @Test fun alarmaDistingueLosCuatroNiveles() {
        assertEquals(4, Alarma.entries.map { visualAlarma(it).icono }.distinct().size)
        assertEquals(TonoEstado.CRITICO, visualAlarma(Alarma.CRITICA).tono)
        assertEquals(TonoEstado.NEUTRO, visualAlarma(Alarma.SIN_DATOS).tono)
    }

    @Test fun cadaTipoDeEventoAdmitidoTieneUnIconoEspecifico() {
        val tipos = listOf("ESTACION_CONECTADA", "ESTACION_DESCONECTADA", "PORTATIL_CONECTADO",
            "PORTATIL_DESCONECTADO", "PUERTA_ABIERTA", "PUERTA_CERRADA", "PORTATIL_FUERA_RANGO",
            "PORTATIL_RECONECTADO", "ALARMA_ADVERTENCIA", "ALARMA_CRITICA", "ALARMA_RECONOCIDA",
            "SISTEMA_ACTIVADO", "SISTEMA_DESACTIVADO", "MOVIL_CONECTADO", "MOVIL_DESCONECTADO",
            "ESTADO_ACTUALIZADO", "RECONOCIMIENTO_SOLICITADO")
        tipos.forEach { assertNotEquals(it, IconoSafeHome.INFO, visualEvento(it).first) }
    }

    @Test fun eventosAnterioresNoSePresentanComoConexionMqttActual() {
        assertEquals(TonoEstado.NEUTRO, visualEvento("MOVIL_CONECTADO").second)
        assertEquals(TonoEstado.NEUTRO, visualEvento("PORTATIL_RECONECTADO").second)
        assertEquals(TonoEstado.INFORMATIVO, visualEvento("ALARMA_RECONOCIDA").second)
    }

    @Test fun tipoNoReconocidoTienePresentacionNeutra() {
        assertEquals(IconoSafeHome.INFO to TonoEstado.NEUTRO, visualEvento("TIPO_FUTURO"))
    }
}
