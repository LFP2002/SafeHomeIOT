package com.example.safehome

import com.example.safehome.iot.*
import org.junit.Assert.*
import org.junit.Test

private class SupervisionFake : RepositorioSupervision {
    val dispositivos = mutableListOf<(Lectura<EstadoDispositivo>) -> Unit>()
    val fisicos = mutableListOf<(Lectura<EstadoFisico>) -> Unit>()
    val historiales = mutableListOf<(Lectura<HistorialSupervision>) -> Unit>()
    val hogares = mutableListOf<String>()
    var cancelados = 0
    override fun observarDispositivo(hogar: String, dispositivo: Dispositivo, recibir: (Lectura<EstadoDispositivo>) -> Unit): Suscripcion {
        hogares += hogar; dispositivos += recibir
        return Suscripcion { cancelados++ }
    }
    override fun observarEstadoFisico(hogar: String, recibir: (Lectura<EstadoFisico>) -> Unit): Suscripcion {
        hogares += hogar; fisicos += recibir
        return Suscripcion { cancelados++ }
    }
    override fun observarHistorial(hogar: String, recibir: (Lectura<HistorialSupervision>) -> Unit): Suscripcion {
        hogares += hogar; historiales += recibir
        return Suscripcion { cancelados++ }
    }
}

class CoordinadorSupervisionTest {
    private val repo = SupervisionFake()
    private var panel = PanelSupervision()
    private val coordinador = CoordinadorSupervision(repo) { panel = it }
    private val sesion = SesionSupervision("uid-demo", "hogar_demo")
    private val conectado = Lectura.Dato(EstadoDispositivo(ConexionMqtt.CONECTADO, Instante(2000), Instante(1000)))

    @Test fun observaCuatroRecursosDelMismoHogar() {
        coordinador.iniciar(sesion)
        assertEquals(List(4) { "hogar_demo" }, repo.hogares)
        assertSame(Lectura.Cargando, panel.estacion)
        assertSame(Lectura.Cargando, panel.historial)
    }
    @Test fun recursosSeActualizanIndependientemente() {
        coordinador.iniciar(sesion)
        repo.dispositivos[0](conectado)
        repo.dispositivos[1](Lectura.SinDatos)
        assertEquals(conectado, panel.estacion)
        assertSame(Lectura.SinDatos, panel.portatil)
        assertSame(Lectura.Cargando, panel.fisico)
    }
    @Test fun desconexionEsDatoNoErrorNiPresenciaFisica() {
        coordinador.iniciar(sesion)
        val desconectado = conectado.copy(valor = conectado.valor.copy(mqtt = ConexionMqtt.DESCONECTADO))
        repo.dispositivos[1](desconectado)
        assertEquals(desconectado, panel.portatil)
        assertSame(Lectura.Cargando, panel.historial)
    }
    @Test fun erroresNoAfectanOtrosRecursosNiInventanEventos() {
        coordinador.iniciar(sesion)
        repo.dispositivos[0](conectado)
        repo.fisicos[0](Lectura.Error("Permiso denegado"))
        assertEquals(conectado, panel.estacion)
        assertEquals(Lectura.Error("Permiso denegado"), panel.fisico)
        assertSame(Lectura.Cargando, panel.historial)
    }
    @Test fun logoutCancelaCuatroListenersYBorraSoloLaVista() {
        coordinador.iniciar(sesion)
        repo.dispositivos[0](conectado)
        coordinador.cerrar()
        assertEquals(4, repo.cancelados)
        assertEquals(PanelSupervision(), panel)
        repo.dispositivos[0](conectado)
        repo.fisicos[0](Lectura.Error("respuesta tardía"))
        repo.historiales[0](Lectura.Dato(HistorialSupervision(emptyList(), 0, false)))
        assertEquals(PanelSupervision(), panel)
    }
    @Test fun cambioDeHogarDescartaTodosLosCallbacksPrevios() {
        coordinador.iniciar(sesion)
        coordinador.iniciar(sesion.copy(hogar = "otro_hogar"))
        repo.dispositivos[0](conectado)
        assertSame(Lectura.Cargando, panel.estacion)
        repo.dispositivos[2](conectado)
        assertEquals(conectado, panel.estacion)
        assertEquals(4, repo.cancelados)
    }
    @Test fun nuevaSesionDelMismoHogarNoAceptaRespuestaAnterior() {
        coordinador.iniciar(sesion)
        coordinador.iniciar(sesion.copy(uid = "otro_uid"))
        repo.dispositivos[0](conectado)
        assertSame(Lectura.Cargando, panel.estacion)
    }
    @Test fun reintentoMismoUidYHogarInvalidaGeneracionAnterior() {
        coordinador.iniciar(sesion)
        val callbackViejo = repo.dispositivos[0]
        callbackViejo(Lectura.Error("Sin red"))
        coordinador.iniciar(sesion)
        callbackViejo(conectado)
        assertSame(Lectura.Cargando, panel.estacion)
        repo.dispositivos[2](conectado)
        assertEquals(conectado, panel.estacion)
    }
    @Test fun cacheConservaEtiquetaYNoConfirmaPuertaActual() {
        coordinador.iniciar(sesion)
        repo.dispositivos[0](conectado.copy(desdeCache = true))
        assertTrue((panel.estacion as Lectura.Dato).desdeCache)
        assertTrue(puertaPuedeEstarDesactualizada(panel.estacion, panel.fisico))
    }
    @Test fun repetirSnapshotDeDispositivoNoProduceHistorial() {
        coordinador.iniciar(sesion)
        repeat(20) { repo.dispositivos[1](conectado.copy(valor = conectado.valor.copy(ultimoHeartbeatEn = Instante(it.toLong())))) }
        assertSame(Lectura.Cargando, panel.historial)
    }
    @Test fun cerrarDosVecesNoCancelaDosVecesElMismoListener() {
        coordinador.iniciar(sesion)
        coordinador.cerrar()
        coordinador.cerrar()
        assertEquals(4, repo.cancelados)
    }
    @Test fun hogarInvalidoNoAbreListeners() {
        assertThrows(IllegalArgumentException::class.java) { coordinador.iniciar(sesion.copy(hogar = "a/b")) }
        assertTrue(repo.hogares.isEmpty())
    }
}
