package com.example.safehome

import com.google.firebase.Timestamp
import org.junit.Test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class PerfilUsuarioTest {
    @Test
    fun aceptaPerfilActivoConDatosExactos() {
        val resultado = validarPerfilUsuario(
            mapOf("rol" to "cuidador", "hogarId" to "hogar_prueba", "activo" to true)
        )

        assertEquals(
            ResultadoPerfil.Valido(PerfilUsuario(RolUsuario.CUIDADOR, "hogar_prueba")),
            resultado
        )
    }

    @Test
    fun rechazaCamposAusentesInactivosYFormatosNoPermitidos() {
        val perfilesInvalidos = listOf(
            emptyMap(),
            mapOf("rol" to "simulador", "hogarId" to "hogar_prueba", "activo" to false),
            mapOf("rol" to "admin", "hogarId" to "hogar_prueba", "activo" to true),
            mapOf("rol" to "simulador", "hogarId" to "hogar prueba", "activo" to true),
            mapOf("rol" to "simulador", "hogarId" to 7, "activo" to true),
            mapOf("rol" to "simulador", "hogarId" to "hogar_prueba", "activo" to "true")
        )

        perfilesInvalidos.forEach { perfil ->
            assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(perfil))
        }
    }

    @Test
    fun descartaRespuestasDeOtroIntentoUidOSesion() {
        assertTrue(respuestaPerfilEsVigente(4, "uid-a", 9, "uid-a", 4, "uid-a", 9))
        assertFalse(respuestaPerfilEsVigente(4, "uid-a", 9, "uid-a", 5, "uid-a", 9))
        assertFalse(respuestaPerfilEsVigente(4, "uid-a", 9, "uid-b", 4, "uid-a", 9))
        assertFalse(respuestaPerfilEsVigente(4, "uid-a", 9, "uid-a", 4, "uid-a", 10))
    }

    @Test
    fun aceptaDocumentoDeEstadoValido() {
        val resultado = validarEstadoHogar(
            mapOf(
                "puertaAbierta" to false,
                "personaPresente" to true,
                "actualizadoEn" to Timestamp(0, 0)
            )
        )

        val estado = (resultado as ResultadoEstadoHogar.Valido).estado
        assertEquals(false, estado.puertaAbierta)
        assertEquals(true, estado.personaPresente)
        assertEquals(NivelEstadoHogar.NORMAL, estado.nivel)
        assertTrue(estado.horaLocal.isNotBlank())
    }

    @Test
    fun rechazaDocumentoDeEstadoConCamposOTiposInvalidos() {
        val documentosInvalidos = listOf(
            emptyMap(),
            mapOf("puertaAbierta" to "false", "personaPresente" to true, "actualizadoEn" to Timestamp(0, 0)),
            mapOf("puertaAbierta" to false, "personaPresente" to 1, "actualizadoEn" to Timestamp(0, 0)),
            mapOf("puertaAbierta" to false, "personaPresente" to true, "actualizadoEn" to 0L)
        )

        documentosInvalidos.forEach { documento ->
            assertSame(ResultadoEstadoHogar.Invalido, validarEstadoHogar(documento))
        }
    }

    @Test
    fun clasificaLosTresEstadosDelHogar() {
        assertEquals(NivelEstadoHogar.NORMAL, clasificarEstadoHogar(true, false))
        assertEquals(NivelEstadoHogar.ADVERTENCIA, clasificarEstadoHogar(false, false))
        assertEquals(NivelEstadoHogar.CRITICO, clasificarEstadoHogar(false, true))
    }

    @Test
    fun descartaCallbackDeOtroUidHogarOSesion() {
        assertTrue(respuestaEstadoEsVigente(7, "uid-a", "hogar-a", 3, "uid-a", 7, "uid-a", "hogar-a", 3))
        assertFalse(respuestaEstadoEsVigente(7, "uid-a", "hogar-a", 3, "uid-b", 7, "uid-a", "hogar-a", 3))
        assertFalse(respuestaEstadoEsVigente(7, "uid-a", "hogar-a", 3, "uid-a", 7, "uid-a", "hogar-b", 3))
        assertFalse(respuestaEstadoEsVigente(7, "uid-a", "hogar-a", 3, "uid-a", 7, "uid-a", "hogar-a", 4))
    }

    @Test
    fun rechazaEstadoInvalidoAntesDeCalcularUnaEscritura() {
        val resultado = validarEstadoHogar(
            mapOf("puertaAbierta" to false, "personaPresente" to "true", "actualizadoEn" to Timestamp(0, 0))
        )

        assertSame(ResultadoEstadoHogar.Invalido, resultado)
    }

    @Test
    fun descartaRespuestaDeEscrituraDeOtroUidHogarOSesion() {
        assertTrue(respuestaEscrituraEsVigente(8, "uid-a", "hogar-a", 2, "uid-a", 8, "uid-a", "hogar-a", 2))
        assertFalse(respuestaEscrituraEsVigente(8, "uid-a", "hogar-a", 2, "uid-a", 9, "uid-a", "hogar-a", 2))
        assertFalse(respuestaEscrituraEsVigente(8, "uid-a", "hogar-a", 2, "uid-b", 8, "uid-a", "hogar-a", 2))
        assertFalse(respuestaEscrituraEsVigente(8, "uid-a", "hogar-a", 2, "uid-a", 8, "uid-a", "hogar-b", 2))
        assertFalse(respuestaEscrituraEsVigente(8, "uid-a", "hogar-a", 2, "uid-a", 8, "uid-a", "hogar-a", 3))
    }

    @Test
    fun aceptaComandoValidoYRechazaTipoOSecuenciaInvalidos() {
        val valido = validarComandoReconocimiento(
            mapOf("tipo" to "RECONOCER_ALERTA", "secuencia" to 0L, "emitidoEn" to Timestamp(0, 0))
        )
        assertEquals(
            ResultadoComandoReconocimiento.Valido(ComandoReconocimiento(0L, (valido as ResultadoComandoReconocimiento.Valido).comando.horaLocal)),
            valido
        )
        val invalidos = listOf(
            mapOf("tipo" to "OTRO", "secuencia" to 0L, "emitidoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "RECONOCER_ALERTA", "secuencia" to -1L, "emitidoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "RECONOCER_ALERTA", "secuencia" to "0", "emitidoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "RECONOCER_ALERTA", "secuencia" to 0L, "emitidoEn" to 0L)
        )
        invalidos.forEach { assertSame(ResultadoComandoReconocimiento.Invalido, validarComandoReconocimiento(it)) }
    }

    @Test
    fun cuidadorSoloEnviaAnteAdvertenciaOCriticoYSinEnvioPendiente() {
        val normal = EstadoHogar(false, true, "10:00", NivelEstadoHogar.NORMAL)
        val advertencia = EstadoHogar(false, false, "10:00", NivelEstadoHogar.ADVERTENCIA)
        val critico = EstadoHogar(true, false, "10:00", NivelEstadoHogar.CRITICO)
        assertFalse(envioReconocimientoHabilitado(RolUsuario.CUIDADOR, normal, false))
        assertTrue(envioReconocimientoHabilitado(RolUsuario.CUIDADOR, advertencia, false))
        assertTrue(envioReconocimientoHabilitado(RolUsuario.CUIDADOR, critico, false))
        assertFalse(envioReconocimientoHabilitado(RolUsuario.SIMULADOR, critico, false))
        assertFalse(envioReconocimientoHabilitado(RolUsuario.CUIDADOR, critico, true))
    }

    @Test
    fun calculaYConfirmaLaSecuenciaEsperada() {
        assertEquals(1L, siguienteSecuenciaComando(0L))
        assertEquals(null, siguienteSecuenciaComando(-1L))
        assertEquals(null, siguienteSecuenciaComando(Long.MAX_VALUE))
        val comando = ComandoReconocimiento(4L, "10:00")
        assertTrue(confirmacionComandoEsEsperada(4L, comando))
        assertFalse(confirmacionComandoEsEsperada(5L, comando))
    }

    @Test
    fun descartaRespuestaDeComandoDeOtroIntentoUidHogarOSesion() {
        assertTrue(respuestaComandoEsVigente(3, "uid-a", "hogar-a", 4, "uid-a", 3, "uid-a", "hogar-a", 4))
        assertFalse(respuestaComandoEsVigente(3, "uid-a", "hogar-a", 4, "uid-a", 4, "uid-a", "hogar-a", 4))
        assertFalse(respuestaComandoEsVigente(3, "uid-a", "hogar-a", 4, "uid-b", 3, "uid-a", "hogar-a", 4))
        assertFalse(respuestaComandoEsVigente(3, "uid-a", "hogar-a", 4, "uid-a", 3, "uid-a", "hogar-b", 4))
        assertFalse(respuestaComandoEsVigente(3, "uid-a", "hogar-a", 4, "uid-a", 3, "uid-a", "hogar-a", 5))
    }

    @Test
    fun politicaDeNotificacionNoAlertaInicialYSoloCambiaEnNivelesConfirmados() {
        val primera = decidirNotificacionHogar(null, NivelEstadoHogar.ADVERTENCIA)
        val repetida = decidirNotificacionHogar(primera.nivelRecordado, NivelEstadoHogar.ADVERTENCIA)
        val critica = decidirNotificacionHogar(repetida.nivelRecordado, NivelEstadoHogar.CRITICO)
        val normal = decidirNotificacionHogar(critica.nivelRecordado, NivelEstadoHogar.NORMAL)
        val cache = decidirNotificacionHogar(critica.nivelRecordado, NivelEstadoHogar.ADVERTENCIA, confirmadoPorServidor = false)
        val nuevaSesion = decidirNotificacionHogar(null, NivelEstadoHogar.CRITICO)
        assertFalse(primera.debeNotificar)
        assertFalse(repetida.debeNotificar)
        assertTrue(critica.debeNotificar)
        assertFalse(normal.debeNotificar)
        assertFalse(cache.debeNotificar)
        assertEquals(critica.nivelRecordado, cache.nivelRecordado)
        assertFalse(nuevaSesion.debeNotificar)
    }

    @Test
    fun validaLosDosTiposDeEventoDeHistorial() {
        val estado = validarEventoHistorial(
            mapOf(
                "tipo" to "ESTADO_ACTUALIZADO",
                "puertaAbierta" to true,
                "personaPresente" to false,
                "creadoEn" to Timestamp(0, 0)
            )
        )
        val reconocimiento = validarEventoHistorial(
            mapOf("tipo" to "RECONOCIMIENTO_SOLICITADO", "secuenciaComando" to 3L, "creadoEn" to Timestamp(0, 0))
        )
        assertTrue(estado is ResultadoEventoHistorial.Valido)
        assertTrue(reconocimiento is ResultadoEventoHistorial.Valido)
    }

    @Test
    fun rechazaEventosDeHistorialInvalidos() {
        val invalidos = listOf(
            mapOf("tipo" to "OTRO", "creadoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "ESTADO_ACTUALIZADO", "puertaAbierta" to "true", "personaPresente" to false, "creadoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "RECONOCIMIENTO_SOLICITADO", "secuenciaComando" to 0L, "creadoEn" to Timestamp(0, 0)),
            mapOf("tipo" to "RECONOCIMIENTO_SOLICITADO", "secuenciaComando" to 1L, "creadoEn" to "ahora")
        )
        invalidos.forEach { assertSame(ResultadoEventoHistorial.Invalido, validarEventoHistorial(it)) }
    }

    @Test
    fun representaEventosDeHistorialDeFormaLegibleYLimitada() {
        val estado = EventoHistorial.EstadoActualizado(true, false, "10:00")
        val reconocimiento = EventoHistorial.ReconocimientoSolicitado(2L, "10:01")
        assertEquals(listOf("Estado actualizado", "Puerta abierta", "Sin señal"), textoEventoHistorial(estado))
        assertEquals(listOf("Solicitud de reconocimiento enviada"), textoEventoHistorial(reconocimiento))
        assertEquals(3, EVENTOS_POR_PAGINA)
        assertEquals(4L, LIMITE_CONSULTA_HISTORIAL)
    }

    @Test
    fun descartaCallbackDeHistorialDeOtroUidHogarOSesion() {
        assertTrue(respuestaHistorialEsVigente(1, "uid-a", "hogar-a", 4, "uid-a", 1, "uid-a", "hogar-a", 4))
        assertFalse(respuestaHistorialEsVigente(1, "uid-a", "hogar-a", 4, "uid-b", 1, "uid-a", "hogar-a", 4))
        assertFalse(respuestaHistorialEsVigente(1, "uid-a", "hogar-a", 4, "uid-a", 1, "uid-a", "hogar-b", 4))
        assertFalse(respuestaHistorialEsVigente(1, "uid-a", "hogar-a", 4, "uid-a", 1, "uid-a", "hogar-a", 5))
    }

    @Test
    fun operacionDeReconocimientoIncluyeEventoDeHistorial() {
        assertTrue(OperacionReconocimientoConHistorial(1L).incluyeEvento)
    }

    @Test
    fun seleccionaTresEventosYDetectaPaginaSiguiente() {
        val eventos = listOf(
            EventoHistorial.ReconocimientoSolicitado(1, "10:00"),
            EventoHistorial.ReconocimientoSolicitado(2, "10:01"),
            EventoHistorial.ReconocimientoSolicitado(3, "10:02"),
            EventoHistorial.ReconocimientoSolicitado(4, "10:03")
        )
        val pagina = seleccionarPaginaHistorial(eventos)
        assertEquals(3, pagina.eventos.size)
        assertTrue(pagina.haySiguiente)
        assertFalse(seleccionarPaginaHistorial(eventos.take(3)).haySiguiente)
    }

    @Test
    fun navegacionDePaginasParteEnPrimeraYSeReiniciaAlActualizar() {
        assertFalse(puedeIrPaginaAnterior(0))
        assertTrue(puedeIrPaginaAnterior(1))
        assertFalse(puedeIrPaginaAnterior(0))
    }

    @Test
    fun descartaResultadoDePaginaDeOtraGeneracionSesionOHogar() {
        assertTrue(resultadoPaginaHistorialEsVigente(2, "uid-a", "hogar-a", 3, 7, "uid-a", 2, "uid-a", "hogar-a", 3, 7))
        assertFalse(resultadoPaginaHistorialEsVigente(2, "uid-a", "hogar-a", 3, 7, "uid-a", 2, "uid-a", "hogar-a", 3, 8))
        assertFalse(resultadoPaginaHistorialEsVigente(2, "uid-a", "hogar-a", 3, 7, "uid-b", 2, "uid-a", "hogar-a", 3, 7))
        assertFalse(resultadoPaginaHistorialEsVigente(2, "uid-a", "hogar-a", 3, 7, "uid-a", 2, "uid-a", "hogar-b", 3, 7))
    }

    @Test
    fun listaVaciaNoTieneEventosNiPaginaSiguiente() {
        val pagina = seleccionarPaginaHistorial(emptyList())
        assertTrue(pagina.eventos.isEmpty())
        assertFalse(pagina.haySiguiente)
    }

    @Test
    fun validaConfiguracionEstricaYPermisosDeEstacion() {
        val valida = validarConfiguracionHogar(mapOf("sistemaActivo" to true, "actualizadoEn" to Timestamp(0, 0)))
        assertEquals(ResultadoConfiguracionHogar.Valida(ConfiguracionHogar(true)), valida)
        assertSame(ResultadoConfiguracionHogar.Invalida, validarConfiguracionHogar(mapOf("sistemaActivo" to "true", "actualizadoEn" to Timestamp(0, 0))))
        assertSame(ResultadoConfiguracionHogar.Invalida, validarConfiguracionHogar(mapOf("sistemaActivo" to true, "actualizadoEn" to 0L)))
        assertSame(ResultadoConfiguracionHogar.Invalida, validarConfiguracionHogar(mapOf("sistemaActivo" to true, "actualizadoEn" to Timestamp(0, 0), "extra" to 1)))
        assertTrue(estacionPuedeCambiarSistema(RolUsuario.SIMULADOR, ConfiguracionHogar(true), false))
        assertFalse(estacionPuedeCambiarSistema(RolUsuario.CUIDADOR, ConfiguracionHogar(true), false))
        assertFalse(estacionPuedeCambiarSistema(RolUsuario.SIMULADOR, null, false))
        assertFalse(estacionPuedeCambiarSistema(RolUsuario.SIMULADOR, ConfiguracionHogar(true), true))
        assertTrue(transicionSistemaEsValida(ConfiguracionHogar(true), false))
        assertFalse(transicionSistemaEsValida(ConfiguracionHogar(true), true))
        assertFalse(transicionSistemaEsValida(null, true))
    }

    @Test
    fun descartaCallbacksDeConfiguracionTardios() {
        assertTrue(respuestaConfiguracionEsVigente(3, "uid-a", "hogar-a", 2, "uid-a", 3, "uid-a", "hogar-a", 2))
        assertFalse(respuestaConfiguracionEsVigente(3, "uid-a", "hogar-a", 2, "uid-a", 4, "uid-a", "hogar-a", 2))
        assertFalse(respuestaConfiguracionEsVigente(3, "uid-a", "hogar-a", 2, "uid-b", 3, "uid-a", "hogar-a", 2))
        assertFalse(respuestaConfiguracionEsVigente(3, "uid-a", "hogar-a", 2, "uid-a", 3, "uid-a", "hogar-b", 2))
    }

    @Test
    fun validaYRepresentaEventosDeSistema() {
        val activado = validarEventoHistorial(mapOf("tipo" to "SISTEMA_ACTIVADO", "sistemaActivo" to true, "creadoEn" to Timestamp(0, 0)))
        val apagado = validarEventoHistorial(mapOf("tipo" to "SISTEMA_DESACTIVADO", "sistemaActivo" to false, "creadoEn" to Timestamp(0, 0)))
        assertTrue(activado is ResultadoEventoHistorial.Valido)
        assertTrue(apagado is ResultadoEventoHistorial.Valido)
        assertSame(ResultadoEventoHistorial.Invalido, validarEventoHistorial(mapOf("tipo" to "SISTEMA_ACTIVADO", "sistemaActivo" to false, "creadoEn" to Timestamp(0, 0))))
        assertSame(ResultadoEventoHistorial.Invalido, validarEventoHistorial(mapOf("tipo" to "SISTEMA_ACTIVADO", "sistemaActivo" to true, "creadoEn" to Timestamp(0, 0), "extra" to 1)))
        assertEquals(listOf("Sistema activado"), textoEventoHistorial(EventoHistorial.SistemaActualizado(true, "10:00")))
    }

    @Test
    fun sistemaApagadoSuprimeAlertasYCorreoRecuperacionSeValidaSinClave() {
        val decision = decidirNotificacionHogar(NivelEstadoHogar.NORMAL, NivelEstadoHogar.CRITICO, confirmadoPorServidor = false)
        assertFalse(decision.debeNotificar)
        assertFalse(notificacionesHabilitadas(ConfiguracionHogar(false)))
        assertFalse(notificacionesHabilitadas(null))
        assertTrue(notificacionesHabilitadas(ConfiguracionHogar(true)))
        assertEquals(null, validarCorreoRecuperacion("usuario@example.com"))
        assertTrue(validarCorreoRecuperacion("correo invalido") != null)
    }

    @Test
    fun validaPresenciaMovilExacta() {
        val resultado = validarPresenciaMovil(mapOf("conectado" to true, "ultimaConexion" to 1L, "ultimoLatido" to 1L))
        assertEquals(ResultadoPresenciaMovil.Valida(PresenciaMovil(true, 1L, 1L)), resultado)
    }

    @Test
    fun rechazaConectadoYTimestampInvalidosOIncompletos() {
        val invalidos = listOf(
            mapOf("conectado" to "true", "ultimaConexion" to 1L, "ultimoLatido" to 1L),
            mapOf("conectado" to true, "ultimaConexion" to null),
            mapOf("conectado" to true, "ultimaConexion" to 0L, "ultimoLatido" to 0L),
            mapOf("conectado" to true, "ultimaConexion" to -1L),
            mapOf("conectado" to true, "ultimaConexion" to 1.0),
            mapOf("conectado" to true),
            mapOf("ultimaConexion" to 1L, "ultimoLatido" to 1L)
        )
        invalidos.forEach { assertSame(ResultadoPresenciaMovil.Invalida, validarPresenciaMovil(it)) }
    }

    @Test
    fun clasificaPresenciaYExcluyeUidNoVinculado() {
        assertEquals(
            EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1L, 1L)),
            clasificarPresenciaMovil(mapOf("conectado" to true, "ultimaConexion" to 1L, "ultimoLatido" to 1L))
        )
        assertSame(EstadoPresenciaMovilUi.NoDisponible, clasificarPresenciaMovil(mapOf("conectado" to "true", "ultimaConexion" to 1L, "ultimoLatido" to 1L)))
        assertTrue(uidEstaVinculado("movil-a", setOf("movil-a")))
        assertFalse(uidEstaVinculado("movil-ajeno", setOf("movil-a")))
    }

    @Test
    fun descartaCallbackDePresenciaFueraDeContexto() {
        assertTrue(respuestaPresenciaEsVigente("movil", "hogar", 3, 4, "movil", "movil", "hogar", 3, 4))
        assertFalse(respuestaPresenciaEsVigente("movil", "hogar", 3, 4, "movil", "movil", "hogar", 3, 5))
        assertFalse(respuestaPresenciaEsVigente("movil", "hogar", 3, 4, "otra", "movil", "hogar", 3, 4))
    }

    @Test
    fun clasificaEstadosVisiblesYRegistraSoloTransicionesReales() {
        assertEquals(EstadoVisualPresencia.ACTIVO, estadoVisualPresencia(EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1, 1))))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(EstadoPresenciaMovilUi.Valida(PresenciaMovil(false, 1, 1))))
        assertEquals(EstadoVisualPresencia.SIN_DATOS, estadoVisualPresencia(null))
        assertEquals(EstadoVisualPresencia.ERROR, estadoVisualPresencia(EstadoPresenciaMovilUi.Error("x")))
        assertEquals(EventoPresencia.MOVIL_CONECTADO, decidirRegistroPresencia(null, PresenciaMovil(true, 1, 1)).evento)
        assertEquals(null, decidirRegistroPresencia("ACTIVO", PresenciaMovil(true, 999, 999)).evento)
        assertEquals(EventoPresencia.MOVIL_DESCONECTADO, decidirRegistroPresencia("ACTIVO", PresenciaMovil(false, 2, 2)).evento)
        assertEquals(null, decidirRegistroPresencia("DESCONECTADO", PresenciaMovil(false, 3, 3)).evento)
    }

    @Test
    fun alertasConexionSoloSeEmittenPorEpisodiosValidos() {
        var seguimiento = SeguimientoAlertaConexion()
        val inicial = decidirAlertaConexion(seguimiento, EstadoVisualPresencia.ACTIVO)
        assertTrue(inicial.esEstadoInicial)
        seguimiento = inicial.seguimiento

        val primeraDesconexion = decidirAlertaConexion(seguimiento, EstadoVisualPresencia.DESCONECTADO)
        assertTrue(primeraDesconexion.debeAlertarDesconexion)
        seguimiento = primeraDesconexion.seguimiento
        val repetida = decidirAlertaConexion(seguimiento, EstadoVisualPresencia.DESCONECTADO)
        assertFalse(repetida.debeAlertarDesconexion)
        assertTrue(repetida.esDuplicada)

        val reconexion = decidirAlertaConexion(repetida.seguimiento, EstadoVisualPresencia.ACTIVO)
        assertTrue(reconexion.debeCancelarPorReconexión)
        assertFalse(reconexion.seguimiento.desconexionAlertada)
        val segundaDesconexion = decidirAlertaConexion(reconexion.seguimiento, EstadoVisualPresencia.DESCONECTADO)
        assertTrue(segundaDesconexion.debeAlertarDesconexion)
    }

    @Test
    fun estadosNoValidosYCallbacksFueraDeContextoNoGeneranAlertasConexion() {
        assertFalse(decidirAlertaConexion(SeguimientoAlertaConexion(), EstadoVisualPresencia.DESCONECTADO).debeAlertarDesconexion)
        assertFalse(decidirAlertaConexion(SeguimientoAlertaConexion(EstadoVisualPresencia.ACTIVO), EstadoVisualPresencia.SIN_DATOS).debeAlertarDesconexion)
        assertFalse(decidirAlertaConexion(SeguimientoAlertaConexion(EstadoVisualPresencia.ACTIVO), EstadoVisualPresencia.ERROR).debeAlertarDesconexion)
        assertFalse(decidirAlertaConexion(SeguimientoAlertaConexion(EstadoVisualPresencia.ACTIVO), EstadoVisualPresencia.DESCONECTADO, contextoVigente = false).debeAlertarDesconexion)
    }

    @Test
    fun movilVibraUnaVezPorCadaEpisodioDeDesconexion() {
        assertFalse(debeVibrarPorDesconexionMovil(null, EstadoPresenciaServicio.SIN_CONEXION))
        assertTrue(debeVibrarPorDesconexionMovil(EstadoPresenciaServicio.ACTIVA, EstadoPresenciaServicio.SIN_CONEXION))
        assertFalse(debeVibrarPorDesconexionMovil(EstadoPresenciaServicio.SIN_CONEXION, EstadoPresenciaServicio.SIN_CONEXION))
        assertTrue(debeVibrarPorDesconexionMovil(EstadoPresenciaServicio.ACTIVA, EstadoPresenciaServicio.SIN_CONEXION))
    }

    @Test
    fun perfilRechazadoNoObtieneAccesoPeroPuedeReintentarlo() {
        val resultado = validarPerfilUsuario(mapOf("rol" to "movil_rechazado", "activo" to false, "hogarId" to "hogar", "nombre" to "Ana"))
        assertTrue(resultado is ResultadoPerfil.Desvinculado)
        val perfil = (resultado as ResultadoPerfil.Desvinculado).perfil
        assertEquals(RolUsuario.MOVIL_RECHAZADO, perfil.rol)
        assertTrue(puedeSolicitarNuevaVinculacion(perfil.rol))
        assertTrue(puedeEliminarCuentaPropia(perfil.rol))
        assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(mapOf("rol" to "movil_rechazado", "activo" to true, "hogarId" to "hogar")))
    }

    @Test
    fun errorDeHistorialConservaContenidoYNoBloqueaControles() {
        val evento = EventoHistorial.ReconocimientoSolicitado(1, "10:00")
        val error = EstadoHistorialUi.Error("No se pudo cargar la actividad del hogar.", true, listOf(evento), 1, true)
        assertEquals(listOf(evento), error.eventosVisibles)
        assertTrue(error.puedeReintentar)
        assertEquals(1, error.pagina)
    }

    @Test
    fun listaMovilesDistingueVacioRealDeError() {
        val vacio = EstadoMovilesUi.Valido(emptyList(), emptyList())
        val error = EstadoMovilesUi.Error("No se pudieron cargar los móviles vinculados.")
        assertTrue(vacio is EstadoMovilesUi.Valido && vacio.vinculados.isEmpty())
        assertTrue(error is EstadoMovilesUi.Error)
    }

    @Test
    fun estadosDeCargaMovilesYSolicitudesSonIndependientesYRecuperables() {
        val lista = EstadoListaMovilesUi.Valido(emptyList())
        val error = EstadoListaMovilesUi.Error("La carga de móviles tardó demasiado. Puedes reintentar.")
        val solicitudes = EstadoSolicitudesUi.Cargando
        assertTrue(lista is EstadoListaMovilesUi.Valido && lista.moviles.isEmpty())
        assertTrue(error is EstadoListaMovilesUi.Error)
        assertTrue(solicitudes is EstadoSolicitudesUi.Cargando)
    }

    @Test
    fun textoDePresenciaNoDuplicaElIndicadorCircular() {
        assertEquals("Activo", textoEstadoVisualPresencia(EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1, 1))))
        assertEquals("Desconectado", textoEstadoVisualPresencia(EstadoPresenciaMovilUi.Valida(PresenciaMovil(false, 1, 1))))
        assertEquals("Sin datos", textoEstadoVisualPresencia(null))
        assertEquals("Error de conexión", textoEstadoVisualPresencia(EstadoPresenciaMovilUi.Error("x")))
    }

    @Test
    fun presenciaConLatidoClasificaActividadYRechazaCamposIncorrectos() {
        val activa = PresenciaMovil(true, 1L, 100_000L)
        assertEquals(EstadoVisualPresencia.ACTIVO, estadoVisualPresencia(EstadoPresenciaMovilUi.Valida(activa), 175_000L))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(EstadoPresenciaMovilUi.Valida(activa), 175_001L))
        assertSame(ResultadoPresenciaMovil.Invalida, validarPresenciaMovil(mapOf("conectado" to true, "ultimaConexion" to 1L)))
    }

    @Test
    fun relojLocalReclasificaSinNuevoDatoRtdb() {
        val presencia = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1L, 10_000L))
        assertEquals(EstadoVisualPresencia.ACTIVO, estadoVisualPresencia(presencia, 85_000L))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(presencia, 85_001L))
        assertEquals(EventoPresencia.MOVIL_DESCONECTADO, decidirRegistroPresencia("ACTIVO", PresenciaMovil(false, 1L, 10_000L)).evento)
    }

    @Test
    fun umbralDeTickerEstacionEsSetentaYCincoSegundos() {
        val presencia = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1L, 1_000L))
        assertEquals(EstadoVisualPresencia.ACTIVO, estadoVisualPresencia(presencia, 75_000L))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(presencia, 77_000L))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(EstadoPresenciaMovilUi.Valida(PresenciaMovil(false, 1L, 1_000L)), 1_001L))
        assertEquals(75_000L, TIEMPO_MAXIMO_SIN_LATIDO_MS)
    }

    @Test
    fun modeloDerivadoDelPanelConservaUnSoloEstadoFinalPorMovil() {
        val ui = listOf(PresenciaMovilUi("interno", "Ana", EstadoVisualPresencia.DESCONECTADO))
        assertEquals(1, ui.size)
        assertEquals(EstadoVisualPresencia.DESCONECTADO, ui.single().estadoFinal)
        assertFalse(ui.single().estadoFinal == EstadoVisualPresencia.ACTIVO)
    }

    @Test
    fun respuestaDePerfilFallidaNoPuedeSerConfirmadaNiDarAcceso() {
        assertFalse(respuestaPerfilEsVigente(1, "u", 2, "otro", 1, "u", 2))
        assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(emptyMap()))
    }

    @Test
    fun convierteDatosDeRealtimeDeFormaSeguraSinExcepciones() {
        assertEquals(
            ResultadoPresenciaMovil.Valida(PresenciaMovil(false, 42L, 42L)),
            convertirPresenciaMovil(mapOf("conectado" to false, "ultimaConexion" to 42L, "ultimoLatido" to 42L))
        )
        assertSame(ResultadoPresenciaMovil.Invalida, convertirPresenciaMovil(null))
        assertSame(ResultadoPresenciaMovil.Invalida, convertirPresenciaMovil(listOf("conectado")))
        assertSame(ResultadoPresenciaMovil.Invalida, convertirPresenciaMovil(mapOf(7 to true, "ultimaConexion" to 1L, "ultimoLatido" to 1L)))
    }

    @Test
    fun validaRegistroDeEstacionYRechazaContrasenasDistintas() {
        assertTrue(validarRegistroEstacion("Lucía", "estacion@example.com", "SeguraClave1", "SeguraClave1").esValido)
        val distinta = validarRegistroEstacion("Lucía", "estacion@example.com", "SeguraClave1", "SeguraClave2")
        assertEquals("Las contraseñas no coinciden.", distinta.confirmacion)
        assertTrue(validarRegistroEstacion("Lucía", "correo invalido", "corta", "corta").correo != null)
        assertTrue(validarRegistroEstacion("Lucía", "estacion@example.com", "corta", "corta").clave != null)
    }

    @Test
    fun validaPoliticaDeContrasenasNuevas() {
        assertTrue(validarContrasenaNueva("Abcdefghij12").isEmpty())
        assertEquals(
            listOf("La contraseña debe tener entre 12 y 32 caracteres."),
            validarContrasenaNueva("Abcdefg1")
        )
        assertTrue(validarContrasenaNueva("Abcdefghij12" + "a".repeat(21)).contains("La contraseña debe tener entre 12 y 32 caracteres."))
        assertTrue(validarContrasenaNueva("abcdefghij12").contains("Incluye al menos una letra mayúscula."))
        assertTrue(validarContrasenaNueva("ABCDEFGHIJ12").contains("Incluye al menos una letra minúscula."))
        assertTrue(validarContrasenaNueva("Abcdefghijkl").contains("Incluye al menos un número."))
        assertTrue(validarContrasenaNueva("Abcdefghij1!").isEmpty())
    }

    @Test
    fun limitaContrasenaYValidaConfirmacionDistinta() {
        val textoLargo = "Abcdefghij12" + "x".repeat(21)
        assertEquals(textoLargo.take(LONGITUD_MAXIMA_CONTRASENA), limitarLongitudContrasena(textoLargo))
        assertEquals(LONGITUD_MAXIMA_CONTRASENA, limitarLongitudContrasena(textoLargo).length)
        assertEquals(
            "Las contraseñas no coinciden.",
            validarRegistroEstacion("Lucía", "estacion@example.com", "SeguraClave1", "SeguraClave2").confirmacion
        )
    }

    @Test
    fun generaCodigoDeVinculacionSinCaracteresAmbiguos() {
        repeat(20) {
            val codigo = generarCodigoVinculacion()
            assertEquals(12, codigo.length)
            assertTrue(codigoVinculacionEsValido(codigo))
            assertFalse(codigo.any { it in "OI01" })
        }
    }

    @Test
    fun aceptaEstacionYMantieneCompatibilidadDePerfilesAntiguos() {
        assertEquals(ResultadoPerfil.Valido(PerfilUsuario(RolUsuario.ESTACION, "uid_estacion", "Ana")), validarPerfilUsuario(mapOf("rol" to "estacion", "hogarId" to "uid_estacion", "activo" to true, "nombre" to "Ana")))
        assertTrue((validarPerfilUsuario(mapOf("rol" to "simulador", "hogarId" to "hogar_antiguo", "activo" to true)) as ResultadoPerfil.Valido).perfil.rol.esEstacion())
        assertTrue((validarPerfilUsuario(mapOf("rol" to "cuidador", "hogarId" to "hogar_antiguo", "activo" to true)) as ResultadoPerfil.Valido).perfil.rol == RolUsuario.CUIDADOR)
        assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(mapOf("rol" to "movil", "hogarId" to "h", "activo" to true)))
        assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(mapOf("rol" to "estacion", "hogarId" to "hogar con espacio", "activo" to true)))
    }

    @Test
    fun validaNombreVisibleYHogarConFallbackSeguro() {
        assertEquals("María José", normalizarNombreVisible("  María   José "))
        assertEquals(null, validarNombreVisible("Ñuñoa O'Neil-Soto"))
        listOf("", "A", "Ana2", "Ana 😀", "<script>", "A".repeat(41)).forEach { assertTrue(validarNombreVisible(it) != null) }
        val hogar = validarHogarVisible(mapOf("nombre" to "Hogar de María", "creadoEn" to Timestamp(0, 0)))
        assertEquals(ResultadoHogarVisible.Valido(HogarVisible("Hogar de María")), hogar)
        assertSame(ResultadoHogarVisible.Invalido, validarHogarVisible(mapOf("nombre" to "Hogar 2", "creadoEn" to Timestamp(0, 0))))
        assertSame(ResultadoHogarVisible.Invalido, validarHogarVisible(mapOf("nombre" to "Hogar de Ana", "creadoEn" to 0L)))
        assertEquals("Mi hogar", nombreHogarSeguro(null))
    }

    @Test
    fun normalizaYValidaCodigoDeSolicitudMovil() {
        val codigo = normalizarCodigoVinculacion(" ab cd-2345 6789 ")
        assertEquals("ABCD-23456789", codigo)
        assertEquals("ABCDEFGHJKLM", normalizarCodigoVinculacion(" abcdefghjklm "))
        assertTrue(codigoVinculacionEsValido("ABCDEFGHJKLM"))
        assertFalse(codigoVinculacionEsValido("ABCDEFGHIJKL"))
    }

    @Test
    fun validaMapaCodigoYSolicitudDeVinculacion() {
        assertTrue(validarCodigoVinculacion(mapOf("hogarId" to "hogar", "propietarioUid" to "estacion", "activo" to true, "creadoEn" to Timestamp(0, 0))) is ResultadoCodigoVinculacion.Valido)
        assertSame(ResultadoCodigoVinculacion.Invalido, validarCodigoVinculacion(mapOf("hogarId" to "hogar", "propietarioUid" to "e", "activo" to false, "creadoEn" to Timestamp(0, 0))))
        val solicitud = validarSolicitudVinculacion(mapOf("uidMovil" to "movil", "nombre" to "Ana", "codigo" to "ABCDEFGHJKLM", "estado" to "PENDIENTE", "creadoEn" to Timestamp(0, 0)))
        assertTrue(solicitud is ResultadoSolicitudVinculacion.Valida)
        assertSame(ResultadoSolicitudVinculacion.Invalida, validarSolicitudVinculacion(mapOf("uidMovil" to "m", "nombre" to "Ana", "codigo" to "INVALIDO", "estado" to "PENDIENTE", "creadoEn" to Timestamp(0, 0))))
    }

    @Test
    fun perfilMovilPendienteNoEsMovilActivoYLaAprobacionConservaCampos() {
        val pendiente = validarPerfilUsuario(mapOf("rol" to "movil_pendiente", "activo" to false, "hogarId" to "hogar", "nombre" to "Ana")) as ResultadoPerfil.Valido
        val activo = validarPerfilUsuario(mapOf("rol" to "movil", "activo" to true, "hogarId" to "hogar", "nombre" to "Ana")) as ResultadoPerfil.Valido
        assertFalse(pendiente.perfil.rol.esMovil())
        assertTrue(activo.perfil.rol.esMovil())
        val antes = SolicitudVinculacion("m", "Ana", "ABCDEFGHJKLM", "PENDIENTE")
        assertTrue(aprobacionConservaInmutables(antes, antes.copy(estado = "APROBADA")))
        assertFalse(aprobacionConservaInmutables(antes, antes.copy(nombre = "Otra", estado = "APROBADA")))
    }

    @Test
    fun confirmacionDeAprobacionExigeLosTresDocumentosExactos() {
        val tiempo = Timestamp(0, 0)
        val perfil = mapOf("activo" to true, "rol" to "movil", "hogarId" to "hogar", "nombre" to "Ana")
        val solicitud = mapOf("uidMovil" to "movil", "nombre" to "Ana", "codigo" to "ABCDEFGHJKLM", "estado" to "APROBADA", "creadoEn" to tiempo, "respondidaEn" to tiempo)
        val movil = mapOf("uidMovil" to "movil", "nombre" to "Ana", "vinculadoEn" to tiempo)
        assertTrue(aprobacionServidorEsValida(perfil, solicitud, movil, "movil", "hogar", "Ana"))
        assertFalse(aprobacionServidorEsValida(perfil, solicitud + ("estado" to "PENDIENTE"), movil, "movil", "hogar", "Ana"))
        assertFalse(aprobacionServidorEsValida(perfil, solicitud, movil + ("extra" to true), "movil", "hogar", "Ana"))
    }

    @Test
    fun recuperacionInformaCorreoInvalidoYEstadosRecuperables() {
        val vacio = validarCorreoRecuperacion("")!!
        val invalido = validarCorreoRecuperacion("correo invalido")!!
        assertEquals(vacio, estadoRecuperacionCorreoInvalido(vacio).texto)
        assertTrue(estadoRecuperacionCorreoInvalido(invalido).esError)
        assertSame(EstadoRecuperacionUi.EnCurso, estadoRecuperacionAlIniciar())
        val exito = estadoRecuperacionFinalizada(true, "error")
        assertEquals("Si existe una cuenta compatible, recibirás instrucciones para restablecer tu contraseña.", exito.texto)
        assertFalse(exito.esError)
        val error = estadoRecuperacionFinalizada(false, "No se pudo procesar la solicitud. Inténtalo más tarde.")
        assertTrue(error.esError)
    }

    @Test
    fun timeoutDeRecuperacionLiberaOperacionYDescartaCallbacksTardios() {
        val timeout = estadoRecuperacionPorTimeout()
        assertEquals("La solicitud tardó demasiado. Revisa tu conexión e inténtalo nuevamente.", timeout.texto)
        assertTrue(timeout.esError)
        assertTrue(recuperacionPuedeIniciarse(null))
        assertFalse(recuperacionPuedeIniciarse(7L))
        assertTrue(callbackRecuperacionEsVigente(8L, 8L))
        assertFalse(callbackRecuperacionEsVigente(7L, 8L))
        assertFalse(callbackRecuperacionEsVigente(7L, null))
    }
}

class ReconexionPresenciaMovilTest {
    @Test
    fun listenerDeInfoSeInstalaUnaSolaVezDuranteRecuperaciones() {
        assertTrue(debeInstalarListenerConexion(contextoVigente = true, listenerYaInstalado = false))
        assertFalse(debeInstalarListenerConexion(contextoVigente = true, listenerYaInstalado = true))
        assertFalse(debeInstalarListenerConexion(contextoVigente = false, listenerYaInstalado = false))
    }

    @Test
    fun watchdogNoInvalidaContextoYNaceSoloUnaVezPorRedValidada() {
        assertTrue(debeEjecutarRecuperacionForzada(true, true, false, false))
        assertFalse(debeEjecutarRecuperacionForzada(true, true, false, true))
        assertFalse(debeEjecutarRecuperacionForzada(false, true, false, false))
        assertFalse(debeEjecutarRecuperacionForzada(true, false, false, false))
        assertFalse(debeEjecutarRecuperacionForzada(true, true, true, false))
    }

    @Test
    fun confirmacionTrueRepublicaUnaVezSinDuplicarHeartbeat() {
        assertTrue(debeRepublicarTrasConfirmacionRtdb(true, true, false, false))
        assertFalse(debeRepublicarTrasConfirmacionRtdb(true, true, true, false))
        assertFalse(debeRepublicarTrasConfirmacionRtdb(true, true, false, true))
        assertFalse(debeRepublicarTrasConfirmacionRtdb(false, true, false, false))
    }

    @Test
    fun detencionInvalidaListenerWatchdogYRepublicacionPendiente() {
        val contextoDetenido = false
        assertFalse(debeInstalarListenerConexion(contextoDetenido, listenerYaInstalado = false))
        assertFalse(debeEjecutarRecuperacionForzada(contextoDetenido, true, false, false))
        assertFalse(debeRepublicarTrasConfirmacionRtdb(contextoDetenido, true, false, false))
    }
}

class DesvinculacionMovilTest {
    @Test fun eliminacionConfirmadaMuestraSoloLaConfirmacionLimpia() {
        val estado = estadoTrasCuentaEliminada()
        assertSame(EstadoEliminacionCuentaUi.Exitosa, estado.eliminacionCuenta)
        assertFalse(estado.haySesion)
        assertEquals(null, estado.correoCuenta)
        assertSame(EstadoPerfilUi.SinSesion, estado.perfil)
        assertEquals(null, estado.hogar)
    }

    @Test fun descartarConfirmacionDejaElInicioLimpioSinExitoFalso() {
        val estado = estadoTrasDescartarCuentaEliminada()
        assertSame(EstadoEliminacionCuentaUi.Inactiva, estado.eliminacionCuenta)
        assertFalse(estado.haySesion)
        assertEquals(null, estado.correoCuenta)
        assertSame(EstadoPerfilUi.SinSesion, estado.perfil)
    }

    @Test fun unErrorDeEliminacionNoActivaLaConfirmacionDeExito() {
        val estadoTrasError = estadoTrasErrorEliminacion("No se pudo completar la eliminación.")
        assertTrue(estadoTrasError.eliminacionCuenta is EstadoEliminacionCuentaUi.Error)
        assertFalse(estadoTrasError.eliminacionCuenta is EstadoEliminacionCuentaUi.Exitosa)
        assertFalse(estadoTrasError.operacionMovil is EstadoOperacionMovilUi.EnCurso)
    }

    @Test fun respuestasTerminalesDeVinculacionRecarganElPerfil() {
        assertTrue(debeRecargarPerfilTrasRespuestaVinculacion("APROBADA"))
        assertTrue(debeRecargarPerfilTrasRespuestaVinculacion("RECHAZADA"))
        assertFalse(debeRecargarPerfilTrasRespuestaVinculacion("PENDIENTE"))
    }

    @Test fun perfilDesvinculadoNoObtieneAccesoAlHogar() {
        val resultado = validarPerfilUsuario(mapOf("rol" to "movil_desvinculado", "activo" to false, "hogarId" to "hogar", "nombre" to "Ana"))
        assertTrue(resultado is ResultadoPerfil.Desvinculado)
        assertFalse((resultado as ResultadoPerfil.Desvinculado).perfil.rol.esMovil())
    }

    @Test fun estacionRetiradaSoloPuedeCompletarElBorradoDeSuPropiaCuenta() {
        val resultado = validarPerfilUsuario(
            mapOf("rol" to "estacion_eliminada", "activo" to false, "hogarId" to "hogar", "nombre" to "Lucía")
        )

        assertTrue(resultado is ResultadoPerfil.EliminacionPendiente)
        val perfil = (resultado as ResultadoPerfil.EliminacionPendiente).perfil
        assertEquals(RolUsuario.ESTACION_ELIMINADA, perfil.rol)
        assertFalse(perfil.rol.esEstacion())
    }

    @Test fun transicionDeDesvinculacionExigeMovilYAprobacion() {
        val solicitud = SolicitudVinculacion("m", "Ana", "ABCDEFGHJKLM", "APROBADA")
        assertTrue(desvinculacionEsValida(PerfilUsuario(RolUsuario.MOVIL, "h", "Ana"), solicitud, "m"))
        assertFalse(desvinculacionEsValida(PerfilUsuario(RolUsuario.MOVIL_PENDIENTE, "h", "Ana"), solicitud, "m"))
    }

    @Test fun movilActivoODesvinculadoPuedeEliminarSuCuenta() {
        assertTrue(puedeSolicitarNuevaVinculacion(RolUsuario.MOVIL_DESVINCULADO))
        assertTrue(puedeEliminarCuentaPropia(RolUsuario.MOVIL_DESVINCULADO))
        assertTrue(puedeEliminarCuentaPropia(RolUsuario.MOVIL))
        assertTrue(puedeEliminarCuentaPropia(RolUsuario.MOVIL_RECHAZADO))
        assertTrue(puedeSolicitarNuevaVinculacion(RolUsuario.MOVIL_RECHAZADO))
        listOf(RolUsuario.ESTACION, RolUsuario.MOVIL_PENDIENTE).forEach {
            assertFalse(puedeSolicitarNuevaVinculacion(it)); assertFalse(puedeEliminarCuentaPropia(it))
        }
    }

    @Test fun aprobadaADesvinculadaEsUnaTransicionExclusivaDeEstacion() {
        assertTrue(transicionSolicitudEstacionEsValida("APROBADA", "DESVINCULADA"))
        assertFalse(transicionSolicitudEstacionEsValida("PENDIENTE", "DESVINCULADA"))
        assertFalse(transicionSolicitudEstacionEsValida("APROBADA", "APROBADA"))
    }

    @Test fun unMovilNoPuedePrepararLaEliminacionDeOtroMovil() {
        assertTrue(eliminacionMovilPropiaEsValida("mismo", "mismo", RolUsuario.MOVIL))
        assertFalse(eliminacionMovilPropiaEsValida("mismo", "otro", RolUsuario.MOVIL))
    }

    @Test fun retiroDeEstacionNoPermiteRolesAjenosNiReactivacion() {
        assertTrue(retiroEstacionEsValido("estacion", true, "estacion_eliminada", false))
        assertTrue(retiroEstacionEsValido("simulador", true, "estacion_eliminada", false))
        assertFalse(retiroEstacionEsValido("movil", true, "estacion_eliminada", false))
        assertFalse(retiroEstacionEsValido("estacion", true, "estacion", true))
    }

    @Test fun actividadRecienteYEstadosVisualesPermanecenAcotados() {
        val eventos = List(3) { EventoHistorial.PresenciaMovilActualizada("Ana", it % 2 == 0, "10:0$it") }
        assertEquals(2, eventos.take(2).size)
        assertEquals("Activo", textoEstadoVisualPresencia(EstadoVisualPresencia.ACTIVO))
        assertEquals("Desconectado", textoEstadoVisualPresencia(EstadoVisualPresencia.DESCONECTADO))
        assertEquals("Sin datos", textoEstadoVisualPresencia(EstadoVisualPresencia.SIN_DATOS))
    }

    @Test fun estacionSoloPuedePrepararEliminacionSinVinculosOPendientes() {
        assertTrue(estacionPuedePrepararEliminacion(0, 0))
        assertFalse(estacionPuedePrepararEliminacion(1, 0))
        assertFalse(estacionPuedePrepararEliminacion(0, 1))
    }

    @Test fun desvinculacionNoEliminaLaCuentaAuthDelMovil() {
        val resultado = validarPerfilUsuario(mapOf("rol" to "movil_desvinculado", "activo" to false, "hogarId" to "hogar", "nombre" to "Ana"))
        assertTrue(resultado is ResultadoPerfil.Desvinculado)
        assertTrue(puedeSolicitarNuevaVinculacion((resultado as ResultadoPerfil.Desvinculado).perfil.rol))
    }
}


