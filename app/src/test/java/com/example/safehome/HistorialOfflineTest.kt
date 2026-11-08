package com.example.safehome

import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistorialOfflineTest {
    private class Almacen : AlmacenEventosHardware {
        val datos = mutableMapOf<String, Any>()
        val orden = mutableListOf<String>()
        var falla = false
        override fun leer(): Map<String, *> = datos.toMap()
        override fun escribir(clave: String, valor: String?): Boolean {
            orden.add("local")
            if (falla) return false
            if (valor == null) datos.remove(clave) else datos[clave] = valor
            return true
        }
    }
    private class Destino : DestinoHistorialHardware {
        val intentados = mutableListOf<EventoHardwarePendiente>()
        val documentos = mutableMapOf<Pair<String, String>, EventoHardwarePendiente>()
        var resultado = ResultadoDestinoHardware.EXITO
        var antes: suspend (EventoHardwarePendiente) -> Unit = {}
        override suspend fun guardar(evento: EventoHardwarePendiente, vigente: () -> Boolean): ResultadoDestinoHardware {
            intentados.add(evento)
            antes(evento)
            if (!vigente()) return ResultadoDestinoHardware.TRANSITORIO
            if (resultado == ResultadoDestinoHardware.EXITO) documentos.putIfAbsent(evento.hogarId to evento.idEvento, evento)
            return resultado
        }
    }
    private class Entorno(limite: Int = 1000) {
        val almacen = Almacen()
        val cola = ColaEventosHardwareLocal(almacen, limite)
        val destino = Destino()
        val motor = SincronizadorEventosHardware(cola, destino) { 20_000L }
        init { motor.establecerHogar("hogar-a") }
        suspend fun agregar(tipo: EventoFisico = EventoFisico.PUERTA_ABIERTA, hogar: String = "hogar-a") = motor.registrar(hogar, tipo, 10_000L)
    }

    @Test fun persisteAntesDeIntentarFirebase() = runBlocking {
        val e = Entorno()
        e.destino.antes = { evento ->
            assertEquals(evento.idEvento, e.cola.leer().eventos.single().idEvento)
            assertTrue(e.almacen.orden.isNotEmpty())
            e.almacen.orden.add("remoto")
        }
        assertTrue(e.agregar())
        assertTrue(e.destino.intentados.isEmpty())
        e.motor.sincronizar()
        assertEquals("local", e.almacen.orden.first())
        assertTrue(e.almacen.orden.indexOf("remoto") > 0)
    }
    @Test fun exitoEliminaDeCola() = runBlocking {
        val e = Entorno(); e.agregar(); e.motor.sincronizar()
        assertTrue(e.cola.leer().eventos.isEmpty())
        assertEquals(1, e.destino.documentos.size)
    }
    @Test fun falloPermaneceLocal() = runBlocking {
        val e = Entorno(); e.destino.resultado = ResultadoDestinoHardware.TRANSITORIO
        e.agregar(); e.motor.sincronizar()
        assertEquals(1, e.cola.leer().eventos.size)
    }
    @Test fun reintentoConservaId() = runBlocking {
        val e = Entorno(); e.destino.resultado = ResultadoDestinoHardware.TRANSITORIO
        e.agregar(); e.motor.sincronizar(); e.motor.sincronizar()
        assertEquals(2, e.destino.intentados.size)
        assertEquals(1, e.destino.intentados.map { it.idEvento }.distinct().size)
    }
    @Test fun tresIntentosNoCreanTresIds() = runBlocking {
        val e = Entorno(); e.agregar()
        e.destino.antes = { evento -> e.destino.documentos.putIfAbsent(evento.hogarId to evento.idEvento, evento) }
        e.destino.resultado = ResultadoDestinoHardware.TRANSITORIO // Servidor recibió; respuesta perdida.
        repeat(2) { e.motor.sincronizar() }
        e.destino.resultado = ResultadoDestinoHardware.EXITO; e.motor.sincronizar()
        assertEquals(3, e.destino.intentados.size)
        assertEquals(1, e.destino.documentos.size)
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
    @Test fun hogaresNoSeMezclan() = runBlocking {
        val e = Entorno(); e.agregar(); e.agregar(hogar = "hogar-b"); e.motor.sincronizar()
        assertEquals(listOf("hogar-a"), e.destino.intentados.map { it.hogarId })
        assertEquals("hogar-b", e.cola.leer().eventos.single().hogarId)
    }
    @Test fun sesionBNoSubeEventosA() = runBlocking {
        val e = Entorno(); e.agregar(); e.motor.establecerHogar("hogar-b"); e.motor.sincronizar()
        assertTrue(e.destino.intentados.isEmpty())
        assertEquals(1, e.cola.leer().eventos.size)
        assertEquals(0, e.motor.estado.value.cantidadEventosPendientes)
    }
    @Test fun logoutConservaCola() = runBlocking {
        val e = Entorno(); e.agregar(); e.motor.establecerHogar(null); e.motor.sincronizar()
        assertEquals(1, e.cola.leer().eventos.size)
        assertTrue(e.destino.intentados.isEmpty())
    }
    @Test fun nuevaSesionValidaReintenta() = runBlocking {
        val e = Entorno(); e.agregar(); e.motor.establecerHogar(null)
        e.motor.establecerHogar("hogar-a"); e.motor.sincronizar()
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
    @Test fun sincronizacionesConcurrentesNoDuplicanEnvio() = runBlocking {
        val e = Entorno(); e.agregar()
        val entro = CompletableDeferred<Unit>(); val liberar = CompletableDeferred<Unit>()
        e.destino.antes = { entro.complete(Unit); liberar.await() }
        val primero = launch { e.motor.sincronizar() }
        entro.await()
        e.motor.sincronizar()
        assertEquals(1, e.destino.intentados.size)
        liberar.complete(Unit); primero.join()
        assertEquals(1, e.destino.documentos.size)
    }
    @Test fun transicionRepetidaNoSeEncolaOtraVez() = runBlocking {
        val e = Entorno()
        var estado = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_VERIFICADO, puerta = PuertaHardware.CERRADA)
        repeat(3) {
            val resultado = procesarMensajeHardware(estado, MensajeSafeHome(10, TipoMensajeSafeHome.STATE, "DOOR", "OPEN"))
            resultado.evento?.let { e.agregar(it) }
            estado = resultado.estado
        }
        assertEquals(1, e.cola.leer().eventos.size)
    }
    private suspend fun noEncola(dato: String, valor: String, tipo: TipoMensajeSafeHome) {
        val e = Entorno()
        val estado = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_VERIFICADO)
        val resultado = procesarMensajeHardware(estado, MensajeSafeHome(10, tipo, dato, valor))
        resultado.evento?.let { e.agregar(it) }
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
    @Test fun heartbeatNoSeGuarda() = runBlocking { noEncola("HEARTBEAT", "ACTIVE", TipoMensajeSafeHome.STATE) }
    @Test fun rssiNoSeGuarda() = runBlocking { noEncola("RSSI", "-63", TipoMensajeSafeHome.STATE) }
    @Test fun pingNoSeGuarda() = runBlocking { noEncola("PING", "1", TipoMensajeSafeHome.CMD) }
    @Test fun statusAllNoSeGuarda() = runBlocking { noEncola("STATUS", "ALL", TipoMensajeSafeHome.CMD) }
    private suspend fun guarda(tipo: EventoFisico) {
        val e = Entorno(); assertTrue(e.agregar(tipo))
        assertEquals(tipo, e.cola.leer().eventos.single().tipo)
    }
    @Test fun guardaPuertaAbierta() = runBlocking { guarda(EventoFisico.PUERTA_ABIERTA) }
    @Test fun guardaPortatilFueraRango() = runBlocking { guarda(EventoFisico.PORTATIL_FUERA_RANGO) }
    @Test fun guardaAlarmaCritica() = runBlocking { guarda(EventoFisico.ALARMA_CRITICA) }
    @Test fun guardaReconocimientoConfirmado() = runBlocking { guarda(EventoFisico.ALARMA_RECONOCIDA) }
    @Test fun excepcionDeRedConservaEvento() = runBlocking {
        val e = Entorno(); e.agregar(); e.destino.antes = { throw java.io.IOException() }
        assertEquals(ResultadoDestinoHardware.TRANSITORIO, e.motor.sincronizar())
        assertEquals(1, e.cola.leer().eventos.size)
    }
    @Test fun permisoDenegadoBloqueaAutomaticosSinBorrar() = runBlocking {
        val e = Entorno(); e.agregar(); e.destino.resultado = ResultadoDestinoHardware.NO_AUTORIZADO
        e.motor.sincronizar(); e.motor.sincronizar()
        assertTrue(e.motor.estado.value.noTransitorio)
        assertEquals(1, e.destino.intentados.size)
        assertEquals(1, e.cola.leer().eventos.size)
        e.motor.sincronizar(manual = true)
        assertEquals(2, e.destino.intentados.size)
    }
    @Test fun colaVaciaNoEscribe() = runBlocking {
        val e = Entorno(); e.motor.sincronizar()
        assertTrue(e.destino.intentados.isEmpty())
        assertEquals(FaseHistorialHardware.SINCRONIZADO, e.motor.estado.value.fase)
    }
    @Test fun registroCorruptoConservaLoRecuperable() = runBlocking {
        val e = Entorno(); e.agregar()
        e.almacen.datos["evento.roto"] = "{ JSON corrupto"
        val lectura = e.cola.leer()
        assertEquals(ErrorColaHardware.CORRUPTA, lectura.error)
        assertEquals(1, lectura.eventos.size)
        e.motor.sincronizar()
        assertEquals(1, e.destino.documentos.size)
        assertTrue(e.almacen.datos.containsKey("evento.roto"))
        assertEquals(ErrorColaHardware.CORRUPTA, e.motor.estado.value.errorLocal)
    }
    @Test fun destinoRecibeElMismoIdPersistido() = runBlocking {
        val e = Entorno(); e.agregar()
        val local = e.cola.leer().eventos.single()
        e.motor.sincronizar()
        assertTrue(e.destino.documentos.containsKey(local.hogarId to local.idEvento))
    }
    @Test fun contadorSeActualizaAlAgregarYEliminar() = runBlocking {
        val e = Entorno(); e.motor.refrescar(); assertEquals(0, e.motor.estado.value.cantidadEventosPendientes)
        e.agregar(); assertEquals(1, e.motor.estado.value.cantidadEventosPendientes)
        e.agregar(EventoFisico.PUERTA_CERRADA); assertEquals(2, e.motor.estado.value.cantidadEventosPendientes)
        e.motor.sincronizar(); assertEquals(0, e.motor.estado.value.cantidadEventosPendientes)
    }
    @Test fun logoutCancelaVaciadoActivoSinBorrar() = runBlocking {
        val e = Entorno(); e.agregar()
        val entro = CompletableDeferred<Unit>(); val detenido = CompletableDeferred<Unit>()
        e.destino.antes = { entro.complete(Unit); try { CompletableDeferred<Unit>().await() } finally { detenido.complete(Unit) } }
        val job = launch { e.motor.sincronizar() }
        entro.await(); e.motor.establecerHogar(null); detenido.await(); job.join()
        assertEquals(1, e.cola.leer().eventos.size)
        assertTrue(e.destino.documentos.isEmpty())
    }
    @Test fun historialAntiguoSigueValido() {
        EventoFisico.entries.forEach {
            assertTrue(validarEventoHistorial(datosEventoFisico(it, Timestamp(10, 0))) is ResultadoEventoHistorial.Valido)
        }
    }
    @Test fun timestampsOfflineMantienenOcurrencia() {
        val evento = EventoHardwarePendiente.crear("hogar-a", EventoFisico.PUERTA_ABIERTA, 10_000)
        val datos = datosEventoPendiente(evento, Timestamp(500, 0))
        assertEquals(Timestamp(10, 0), datos["creadoEn"])
        assertEquals(10_000L, datos["ocurridoEnMillis"])
        assertEquals(Timestamp(500, 0), datos["sincronizadoEn"])
        val interpretado = (validarEventoHistorial(datos) as ResultadoEventoHistorial.Valido).evento
        assertEquals(formatearHoraLocal(Timestamp(10, 0)), interpretado.horaLocal)
    }
    @Test fun subirHistorialNoAlteraEstadoFisico() = runBlocking {
        val hardware = EstadoHardwareSafeHome(sistema = SistemaHardware.ENCENDIDO, alarma = AlarmaHardware.CRITICA)
        val e = Entorno(); e.agregar(EventoFisico.SISTEMA_DESACTIVADO); e.motor.sincronizar()
        assertEquals(SistemaHardware.ENCENDIDO, hardware.sistema)
        assertEquals(AlarmaHardware.CRITICA, hardware.alarma)
    }
    @Test fun heartbeatAndroidNoEntraEnCola() = runBlocking {
        val e = Entorno()
        val presencia = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1, 1))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(presencia, 100_000))
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
    @Test fun saturacionNoBorraEventosCriticos() = runBlocking {
        val e = Entorno(2); e.agregar(EventoFisico.ALARMA_CRITICA); e.agregar(EventoFisico.PORTATIL_FUERA_RANGO)
        assertFalse(e.agregar(EventoFisico.PUERTA_ABIERTA))
        assertEquals(2, e.cola.leer().eventos.size)
        assertEquals(ErrorColaHardware.SATURADA, e.motor.estado.value.errorLocal)
    }
    @Test fun falloDeCommitImpideEnvio() = runBlocking {
        val e = Entorno(); e.almacen.falla = true
        assertFalse(e.agregar()); e.motor.sincronizar()
        assertTrue(e.destino.intentados.isEmpty())
        assertEquals(ErrorColaHardware.ESCRITURA, e.motor.estado.value.errorLocal)
    }
    @Test fun falloAlEliminarConservaIdParaReintento() = runBlocking {
        val e = Entorno(); e.agregar()
        e.destino.antes = { e.almacen.falla = true }
        e.motor.sincronizar()
        assertEquals(1, e.cola.leer().eventos.size)
        e.almacen.falla = false; e.destino.antes = {}; e.motor.sincronizar()
        assertEquals(1, e.destino.documentos.size)
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
    @Test fun recrearColaRecuperaEventosPersistidos() = runBlocking {
        val e = Entorno(); e.agregar()
        val antes = e.cola.leer().eventos.single()
        val otra = ColaEventosHardwareLocal(e.almacen)
        assertEquals(antes, otra.leer().eventos.single())
    }
    @Test fun registraIntentosYUltimaFechaSinCambiarOcurrencia() = runBlocking {
        val e = Entorno(); e.agregar(); e.destino.resultado = ResultadoDestinoHardware.TRANSITORIO
        repeat(3) { e.motor.sincronizar() }
        val evento = e.cola.leer().eventos.single()
        assertEquals(3, evento.intentos)
        assertEquals(20_000L, evento.ultimoIntentoMillis)
        assertEquals(10_000L, evento.ocurridoEnMillis)
    }
    @Test fun eventosNuevosDuranteVaciadoNoSePierden() = runBlocking {
        val e = Entorno(); e.agregar()
        val entro = CompletableDeferred<Unit>(); val liberar = CompletableDeferred<Unit>()
        e.destino.antes = { entro.complete(Unit); liberar.await() }
        val job = launch { e.motor.sincronizar() }; entro.await()
        e.agregar(EventoFisico.ALARMA_CRITICA)
        liberar.complete(Unit); job.join()
        assertEquals(1, e.cola.leer().eventos.size)
        e.motor.sincronizar()
        assertEquals(2, e.destino.documentos.size)
    }
    @Test fun codecValidaVersionCamposYTipos() {
        val evento = EventoHardwarePendiente.crear("hogar-a", EventoFisico.ALARMA_CRITICA, 10_000)
        assertEquals(evento, CodecEventoHardware.leer(CodecEventoHardware.serializar(evento)))
        listOf(null, 5, "", "[]", "{mal", CodecEventoHardware.serializar(evento).replace("1|", "9|"),
            CodecEventoHardware.serializar(evento).replace("ALARMA_CRITICA", "PING")).forEach { assertNull(CodecEventoHardware.leer(it)) }
    }
    @Test fun confirmacionRemotaNoReescribeTimestampAnterior() {
        val evento = EventoHardwarePendiente.crear("hogar-a", EventoFisico.SISTEMA_ACTIVADO, 10_000)
        val datos = datosEventoPendiente(evento, Timestamp(Date(30_000)))
        assertTrue(documentoCorrespondeAlEvento(datos, evento.copy(intentos = 4)))
        assertFalse(documentoCorrespondeAlEvento(datos, evento.copy(ocurridoEnMillis = 11_000)))
        assertFalse(documentoCorrespondeAlEvento(datos, evento.copy(tipo = EventoFisico.SISTEMA_DESACTIVADO)))
    }
    @Test fun timestampNuevoInvalidoNoSeInterpretaComoAntiguo() {
        val datos = datosEventoFisico(EventoFisico.PUERTA_ABIERTA, Timestamp(10, 0))
        assertEquals(ResultadoEventoHistorial.Invalido, validarEventoHistorial(datos + ("ocurridoEnMillis" to -1L)))
        assertEquals(ResultadoEventoHistorial.Invalido, validarEventoHistorial(datos + ("sincronizadoEn" to Timestamp(20, 0))))
    }
    @Test fun permisoDenegadoPuedeRecuperarseEnNuevaSesion() = runBlocking {
        val e = Entorno(); e.agregar(); e.destino.resultado = ResultadoDestinoHardware.NO_AUTORIZADO
        e.motor.sincronizar(); e.motor.establecerHogar(null); e.motor.establecerHogar("hogar-a")
        e.destino.resultado = ResultadoDestinoHardware.EXITO; e.motor.sincronizar()
        assertTrue(e.cola.leer().eventos.isEmpty())
    }
}
