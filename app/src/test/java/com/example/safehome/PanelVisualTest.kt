package com.example.safehome

import com.example.safehome.iot.*
import com.example.safehome.presentacion.*
import org.junit.Assert.*
import org.junit.Test

class PanelVisualTest {
    private val ahora = 1_795_000_000_000L
    private val estado = EstadoMqtt(true, Puerta.CERRADA, PresenciaPortatil.PRESENTE, Alarma.NORMAL, ahora, "boot", "")
    private val panel = PanelMqtt(conectado = true, estacion = Disponibilidad.ONLINE, portatil = Disponibilidad.ONLINE,
        estado = estado, retenido = false)

    @Test fun normalExigeConexionEstacionYEstadoActualSuficiente() {
        assertEquals("Sistema normal", resumenVisual(panel, ahora).texto)
        assertEquals("Supervisión sin conexión", resumenVisual(panel.copy(conectado = false), ahora).texto)
        assertEquals("Sin datos suficientes", resumenVisual(panel.copy(estacion = Disponibilidad.SIN_DATOS), ahora).texto)
        assertEquals(TonoEstado.CRITICO, resumenVisual(panel.copy(estacion = Disponibilidad.OFFLINE), ahora).tono)
        assertEquals("Sin datos suficientes", resumenVisual(panel.copy(estado = null), ahora).texto)
        assertEquals("Sin datos suficientes", resumenVisual(panel.copy(estado = estado.copy(portatil = PresenciaPortatil.SIN_DATOS)), ahora).texto)
    }
    @Test fun retenidoAntiguoYFechaFuturaNoSonActuales() {
        assertFalse(estadoVigente(panel.copy(retenido = true), ahora))
        assertFalse(estadoVigente(panel, ahora + VIGENCIA_VISUAL_MS + 1))
        assertFalse(estadoVigente(panel, ahora - 1))
        assertEquals("Último estado conocido", resumenVisual(panel.copy(retenido = true), ahora).texto)
        assertEquals(TonoEstado.NEUTRO, resumenVisual(panel.copy(retenido = true, estado = estado.copy(alarma = Alarma.CRITICA)), ahora).tono)
    }
    @Test fun criticidadTienePrioridadSobreVigilanciaYAdvertencia() {
        assertEquals(TonoEstado.CRITICO, resumenVisual(panel.copy(estado = estado.copy(alarma = Alarma.CRITICA)), ahora).tono)
        assertEquals(TonoEstado.AVISO, resumenVisual(panel.copy(estado = estado.copy(alarma = Alarma.ADVERTENCIA)), ahora).tono)
        assertEquals("Vigilancia desactivada", resumenVisual(panel.copy(estado = estado.copy(sistemaActivo = false)), ahora).texto)
        assertEquals("Activada", vigilanciaVisual(panel, ahora).texto)
        assertEquals("Desactivada", vigilanciaVisual(panel.copy(estado = estado.copy(sistemaActivo = false)), ahora).texto)
    }
    @Test fun conectandoNoSeConfundeConConectadoNiDesconectado() {
        val conectando = panel.copy(conectado = false, conexion = "Conectando TLS…")
        assertEquals("Conectando…", conexionVisual(conectando).texto)
        assertEquals("Conectando…", resumenVisual(conectando, ahora).texto)
        assertEquals("Conectado", conexionVisual(panel).texto)
        assertEquals("Desconectado", conexionVisual(PanelMqtt()).texto)
    }
    @Test fun disponibilidadSinDatosYOfflineSonDistintosYSinConexionNoSeConfirmaOnline() {
        assertEquals("Sin datos", disponibilidadVisual(Disponibilidad.ONLINE, false).texto)
        assertEquals(TonoEstado.NEUTRO, disponibilidadVisual(Disponibilidad.SIN_DATOS, true).tono)
        assertEquals(TonoEstado.CRITICO, disponibilidadVisual(Disponibilidad.OFFLINE, true).tono)
        assertEquals(TonoEstado.POSITIVO, disponibilidadVisual(Disponibilidad.ONLINE, true).tono)
    }
    @Test fun confirmacionesDependenDelResultadoExistenteNoDelClick() {
        assertNull(resultadoVisual(panel))
        assertEquals("Esperando respuesta…", resultadoVisual(panel.copy(esperando = true))?.texto)
        assertEquals("Vigilancia activada", resultadoVisual(panel.copy(comando = "Confirmado por Estación: ACTIVADO"))?.texto)
        assertEquals("Vigilancia desactivada", resultadoVisual(panel.copy(comando = "Confirmado por Estación: DESACTIVADO"))?.texto)
        assertEquals("Alarma silenciada", resultadoVisual(panel.copy(comando = "Confirmado por Estación: ALARMA SILENCIADA"))?.texto)
        assertEquals(TonoEstado.AVISO, resultadoVisual(panel.copy(comando = "Sin confirmación tras 10 s. Resultado desconocido"))?.tono)
        assertTrue(resultadoVisual(panel.copy(comando = "Resultado desconocido: conexión interrumpida"))!!.texto.contains("desconocido"))
    }
    @Test fun presentacionNoCambiaLosPermisosDelDominio() {
        assertTrue(panel.permiteControl)
        assertFalse(panel.permiteReconocer)
        assertTrue(panel.copy(estado = estado.copy(alarma = Alarma.CRITICA)).permiteReconocer)
        assertFalse(panel.copy(esperando = true).permiteControl)
        assertFalse(panel.copy(esperando = true).permiteReconocer)
        assertFalse(panel.copy(conectado = false).permiteControl)
        assertFalse(panel.copy(estacion = Disponibilidad.OFFLINE).permiteControl)
        assertTrue(panel.copy(retenido = true).permiteControl)
    }
    @Test fun titulosNoExponenCodigosTecnicos() {
        assertEquals("Alarma silenciada", tituloEvento("ALARMA_RECONOCIDA"))
        assertEquals("Portátil sin señal", tituloEvento("PORTATIL_AUSENTE"))
        assertEquals("Actividad registrada", tituloEvento("FUTURO"))
    }
}
