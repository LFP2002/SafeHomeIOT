package com.example.safehome

import org.junit.Assert.*
import org.junit.Test

class PortatilUsbTest {
    private val abierto = puertoPortatilAbierto(EstadoHardwarePortatil(), 1)
    private val verificado = EstadoHardwarePortatil(conexion = ConexionUsbPortatil.CONECTADO_VERIFICADO,
        idSafeHome = "SH-M-001", rolSafeHome = "PORTABLE")
    private fun mensaje(dato: String, valor: String, tipo: TipoMensajeSafeHome = TipoMensajeSafeHome.STATE) =
        MensajeSafeHome(200, tipo, dato, valor)
    private fun recibir(estado: EstadoHardwarePortatil, dato: String, valor: String, tipo: TipoMensajeSafeHome = TipoMensajeSafeHome.STATE): ResultadoPortatil {
        val validado = ProtocoloSafeHome.parsear(ProtocoloSafeHome.serializar(mensaje(dato, valor, tipo)))
        return procesarMensajePortatil(estado, validado)
    }
    private fun bytes(dato: String, valor: String) = ProtocoloSafeHome.serializar(mensaje(dato, valor)).toByteArray(Charsets.UTF_8)
    private fun ping() = iniciarComandoPortatil(verificado, ComandoPortatil.PING, 42)

    @Test fun portableConIdValidoVerificaEnAmbosOrdenes() {
        listOf(listOf("ROLE" to "PORTABLE", "DEVICE" to "SH-M-001"), listOf("DEVICE" to "SH-M-001", "ROLE" to "PORTABLE")).forEach { campos ->
            val primero = recibir(abierto, campos[0].first, campos[0].second)
            assertFalse(primero.estado.verificado)
            val segundo = recibir(primero.estado, campos[1].first, campos[1].second)
            assertTrue(segundo.estado.verificado)
            assertTrue(segundo.solicitarEstado)
            assertNull(segundo.estado.operacion)
            assertFalse(recibir(segundo.estado, "ROLE", "PORTABLE").solicitarEstado)
        }
    }
    @Test fun roleStationRechazaDispositivo() {
        val resultado = recibir(abierto, "ROLE", "STATION")
        assertEquals(ConexionUsbPortatil.ERROR, resultado.estado.conexion)
        assertEquals("El dispositivo conectado no corresponde a un dispositivo portátil SafeHome.", resultado.estado.error)
        assertNull(resultado.estado.operacion)
    }
    @Test fun idSinPrefijoPortatilOIncompletoSeRechaza() {
        listOf("SH-E-001", "SH-M-", "CP2102", "SH-M-uno dos").forEach {
            assertEquals(ConexionUsbPortatil.ERROR, recibir(abierto, "DEVICE", it).estado.conexion)
        }
    }
    @Test fun wifiConnectedActualiza() {
        assertEquals(ConexionRedPortatil.CONECTADO, recibir(verificado, "WIFI", "CONNECTED").estado.wifi)
    }
    @Test fun wifiDisconnectedActualiza() {
        assertEquals(ConexionRedPortatil.DESCONECTADO, recibir(verificado, "WIFI", "DISCONNECTED").estado.wifi)
    }
    @Test fun rssiNumericoSeAlmacena() {
        assertEquals(-63, recibir(verificado, "RSSI", "-63").estado.rssi)
    }
    @Test fun rssiUnknownEliminaValorAnterior() {
        assertNull(recibir(verificado.copy(rssi = -63), "RSSI", "UNKNOWN").estado.rssi)
    }
    @Test fun mqttConnectedActualiza() {
        assertEquals(ConexionRedPortatil.CONECTADO, recibir(verificado, "MQTT", "CONNECTED").estado.mqtt)
    }
    @Test fun mqttDisconnectedActualiza() {
        assertEquals(ConexionRedPortatil.DESCONECTADO, recibir(verificado, "MQTT", "DISCONNECTED").estado.mqtt)
    }
    @Test fun heartbeatActiveActualiza() {
        assertEquals(HeartbeatPortatil.ACTIVO, recibir(verificado, "HEARTBEAT", "ACTIVE").estado.heartbeat)
    }
    @Test fun heartbeatInactiveActualiza() {
        assertEquals(HeartbeatPortatil.INACTIVO, recibir(verificado, "HEARTBEAT", "INACTIVE").estado.heartbeat)
    }
    @Test fun ackPingCorrelacionadoCompletaPrueba() {
        val pendiente = ping()
        assertEquals(PruebaPortatil.ESPERANDO, pendiente.prueba)
        val resultado = recibir(pendiente, "42", "OK", TipoMensajeSafeHome.ACK).estado
        assertEquals(PruebaPortatil.RESPUESTA_VALIDA, resultado.prueba)
        assertNull(resultado.operacion)
    }
    @Test fun ackIncorrectoNoCompletaComando() {
        val resultado = recibir(ping(), "41", "OK", TipoMensajeSafeHome.ACK).estado
        assertEquals(PruebaPortatil.ESPERANDO, resultado.prueba)
        assertEquals(42L, resultado.operacion?.secuencia)
        val status = iniciarComandoPortatil(verificado, ComandoPortatil.STATUS, 50)
        val ackStatus = recibir(status, "50", "OK", TipoMensajeSafeHome.ACK).estado
        assertNotNull(ackStatus.operacion)
        assertEquals(PruebaPortatil.SIN_PROBAR, ackStatus.prueba)
    }
    @Test fun errAsociadoLimpiaOperacionPeroErrAjenoNo() {
        val ajeno = recibir(ping(), "41", "FAILED", TipoMensajeSafeHome.ERR).estado
        assertNotNull(ajeno.operacion)
        val asociado = recibir(ajeno, "42", "FAILED", TipoMensajeSafeHome.ERR).estado
        assertNull(asociado.operacion)
        assertNotNull(asociado.error)
        assertEquals(PruebaPortatil.SIN_RESPUESTA, asociado.prueba)
    }
    @Test fun desconexionInvalidaIdentidadDiagnosticoYOperacion() {
        val previo = ping().copy(wifi = ConexionRedPortatil.CONECTADO, rssi = -63,
            mqtt = ConexionRedPortatil.CONECTADO, heartbeat = HeartbeatPortatil.ACTIVO)
        val fuera = invalidarEstadoPortatil(previo)
        assertEquals(ConexionUsbPortatil.DESCONECTADO, fuera.conexion)
        assertNull(fuera.idSafeHome)
        assertNull(fuera.rolSafeHome)
        assertNull(fuera.rssi)
        assertNull(fuera.operacion)
        assertNull(fuera.ultimoMensajeValido)
        assertEquals(ConexionRedPortatil.DESCONOCIDA, fuera.wifi)
        assertEquals(ConexionRedPortatil.DESCONOCIDA, fuera.mqtt)
        assertEquals(HeartbeatPortatil.DESCONOCIDO, fuera.heartbeat)
        assertEquals(fuera, recibir(fuera, "42", "OK", TipoMensajeSafeHome.ACK).estado)
    }
    @Test fun reconexionRequiereHelloYNoReutilizaDiagnosticos() {
        val reinicio = puertoPortatilAbierto(verificado.copy(heartbeat = HeartbeatPortatil.ACTIVO, rssi = -55), 80)
        assertFalse(reinicio.verificado)
        assertNull(reinicio.idSafeHome)
        assertNull(reinicio.rssi)
        assertEquals(HeartbeatPortatil.DESCONOCIDO, reinicio.heartbeat)
        assertEquals(ComandoPortatil.HELLO, reinicio.operacion?.comando)
        val hello = mensajeComandoPortatil(reinicio.operacion!!.comando, reinicio.operacion.secuencia)
        assertEquals("HELLO", hello.dato)
        assertEquals("APP_MOBILE", hello.valor)
        assertEquals(80L, hello.secuencia)
        assertFalse(recibir(reinicio, "ROLE", "PORTABLE").estado.verificado)
    }
    @Test fun fragmentosArbitrariosReconstruyenUnaLinea() {
        val receptor = ReceptorLineasSafeHome()
        val mensajes = mutableListOf<MensajeSafeHome>()
        val datos = bytes("DEVICE", "SH-M-001")
        datos.dropLast(1).forEach { receptor.recibir(byteArrayOf(it), mensajes::add) }
        assertTrue(mensajes.isEmpty())
        receptor.recibir(byteArrayOf(datos.last()), mensajes::add)
        assertEquals(listOf(mensaje("DEVICE", "SH-M-001")), mensajes)
    }
    @Test fun multiplesLineasEnUnaLecturaSeSeparan() {
        val mensajes = mutableListOf<MensajeSafeHome>()
        ReceptorLineasSafeHome().recibir(bytes("WIFI", "CONNECTED") + bytes("RSSI", "-63") + bytes("MQTT", "CONNECTED"), mensajes::add)
        assertEquals(listOf("WIFI", "RSSI", "MQTT"), mensajes.map { it.dato })
    }
    @Test fun crLfSeparadoEntreLecturasEsAceptado() {
        val mensajes = mutableListOf<MensajeSafeHome>()
        val receptor = ReceptorLineasSafeHome()
        val datos = ProtocoloSafeHome.serializar(mensaje("WIFI", "CONNECTED")).dropLast(1).toByteArray()
        receptor.recibir(datos + byteArrayOf(13), mensajes::add)
        assertTrue(mensajes.isEmpty())
        receptor.recibir(byteArrayOf(10), mensajes::add)
        assertEquals(listOf(mensaje("WIFI", "CONNECTED")), mensajes)
    }
    @Test fun crcInvalidoNoModificaEstado() {
        var estado = verificado
        var invalidos = 0
        val linea = ProtocoloSafeHome.serializar(mensaje("WIFI", "CONNECTED")).replace("CONNECTED", "DISCONNECTED")
        ReceptorLineasSafeHome().recibir(linea.toByteArray(), { estado = procesarMensajePortatil(estado, it).estado }, { invalidos++ })
        assertEquals(verificado, estado)
        assertEquals(1, invalidos)
    }
    @Test fun lineaLargaEsRechazadaYSeRecuperaSiguienteLinea() {
        val mensajes = mutableListOf<MensajeSafeHome>()
        var invalidos = 0
        val receptor = ReceptorLineasSafeHome()
        receptor.recibir(ByteArray(100_000) { 65 }, mensajes::add, { invalidos++ })
        receptor.recibir(byteArrayOf(10) + bytes("RSSI", "-63"), mensajes::add, { invalidos++ })
        assertEquals(1, invalidos)
        assertEquals(listOf(mensaje("RSSI", "-63")), mensajes)
    }
    @Test fun heartbeatAndroidNoModificaHeartbeatEsp32() {
        val estado = verificado.copy(heartbeat = HeartbeatPortatil.ACTIVO)
        val presencia = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 1, 1))
        assertEquals(EstadoVisualPresencia.DESCONECTADO, estadoVisualPresencia(presencia, 100_000))
        assertEquals(HeartbeatPortatil.ACTIVO, estado.heartbeat)
        assertEquals(HeartbeatPortatil.ACTIVO, recibir(estado, "RSSI", "-63").estado.heartbeat)
    }
    @Test fun heartbeatEsp32NoModificaPresenciaAndroid() {
        val presencia = EstadoPresenciaMovilUi.Valida(PresenciaMovil(true, 10_000, 10_000))
        val estado = recibir(verificado, "HEARTBEAT", "INACTIVE").estado
        assertEquals(HeartbeatPortatil.INACTIVO, estado.heartbeat)
        assertEquals(EstadoVisualPresencia.ACTIVO, estadoVisualPresencia(presencia, 10_001))
        assertEquals(PresenciaMovil(true, 10_000, 10_000), presencia.presencia)
    }
    @Test fun comandosPermitidosExcluyenActuadores() {
        val comandos = ComandoPortatil.entries.map { mensajeComandoPortatil(it, 1) }
        assertEquals(setOf("HELLO", "PING", "STATUS"), comandos.map { it.dato }.toSet())
        assertEquals(listOf("APP_MOBILE", "1", "ALL"), comandos.map { it.valor })
        assertFalse(comandos.any { it.dato == "SYSTEM" || it.dato == "ALARM" })
    }
    @Test fun cerrarSesionInvalidaCallbacksDeConexionAnterior() {
        val sesion = SesionUsbPortatil()
        val anterior = sesion.reiniciar(verificado)
        sesion.finalizarSesion()
        assertFalse(sesion.actualizar(anterior) { verificado.copy(heartbeat = HeartbeatPortatil.ACTIVO) })
        assertEquals(EstadoHardwarePortatil(), sesion.estado)
        val nueva = sesion.reiniciar(abierto)
        assertFalse(sesion.actualizar(anterior) { verificado })
        assertTrue(sesion.actualizar(nueva) { recibir(it, "ROLE", "PORTABLE").estado })
        assertFalse(sesion.estado.verificado)
    }
    @Test fun diagnosticoAnteriorAVerificacionNoSeAcepta() {
        assertEquals(ConexionRedPortatil.DESCONOCIDA, recibir(abierto, "WIFI", "CONNECTED").estado.wifi)
        assertEquals(HeartbeatPortatil.DESCONOCIDO, recibir(abierto, "HEARTBEAT", "ACTIVE").estado.heartbeat)
    }
    @Test fun rssiInvalidoNoSobrescribeValor() {
        listOf("-999", "1", "abc", "-63.5", "999999999999").forEach {
            assertEquals(-63, recibir(verificado.copy(rssi = -63), "RSSI", it).estado.rssi)
        }
    }
    @Test fun statusSeCompletaSoloConLosCuatroCamposValidos() {
        var estado = iniciarComandoPortatil(verificado, ComandoPortatil.STATUS, 60)
        listOf("WIFI" to "CONNECTED", "RSSI" to "UNKNOWN", "MQTT" to "DISCONNECTED").forEach { (dato, valor) ->
            estado = recibir(estado, dato, valor).estado
            assertNotNull(estado.operacion)
        }
        estado = recibir(estado, "HEARTBEAT", "INACTIVE").estado
        assertNull(estado.operacion)
        assertNull(estado.rssi)
        assertEquals(HeartbeatPortatil.INACTIVO, estado.heartbeat)
    }
    @Test fun timeoutNoInventaRespuestaNiDesconecta() {
        val pendiente = ping()
        assertEquals(pendiente, expirarComandoPortatil(pendiente, 41))
        val vencido = expirarComandoPortatil(pendiente, 42)
        assertTrue(vencido.conectado)
        assertEquals(PruebaPortatil.SIN_RESPUESTA, vencido.prueba)
        assertNull(vencido.operacion)
        assertEquals(PruebaPortatil.SIN_RESPUESTA, recibir(vencido, "42", "OK", TipoMensajeSafeHome.ACK).estado.prueba)
    }
    @Test fun errPermissionNoAllowedEsSeguroYSinExito() {
        val estado = recibir(ping(), "PERMISSION", "NOT_ALLOWED", TipoMensajeSafeHome.ERR).estado
        assertNull(estado.operacion)
        assertEquals(PruebaPortatil.SIN_RESPUESTA, estado.prueba)
        assertNotNull(estado.error)
    }
    @Test fun limpiezaDeBufferEvitaMezclarConexiones() {
        val receptor = ReceptorLineasSafeHome()
        val mensajes = mutableListOf<MensajeSafeHome>()
        receptor.recibir(bytes("DEVICE", "SH-M-001").take(12).toByteArray(), mensajes::add)
        receptor.limpiar()
        receptor.recibir(bytes("ROLE", "PORTABLE"), mensajes::add)
        assertEquals(listOf(mensaje("ROLE", "PORTABLE")), mensajes)
    }
    @Test fun lineaEntre256YLimiteDeBytesTambienSeRechaza() {
        val contenido = "SH1|1|STATE|DEVICE|${"A".repeat(260)}"
        val linea = "$contenido|${ProtocoloSafeHome.calcularCrc(contenido)}\n"
        var invalidos = 0
        val mensajes = mutableListOf<MensajeSafeHome>()
        ReceptorLineasSafeHome().recibir(linea.toByteArray(), mensajes::add, { invalidos++ })
        assertTrue(mensajes.isEmpty())
        assertEquals(1, invalidos)
    }
    @Test fun utf8FragmentadoSeDecodificaAlCompletarLinea() {
        val esperado = mensaje("DEVICE", "SH-M-á") // SH1 válido; la identidad específica lo rechazará después.
        val mensajes = mutableListOf<MensajeSafeHome>()
        val receptor = ReceptorLineasSafeHome()
        ProtocoloSafeHome.serializar(esperado).toByteArray().forEach { receptor.recibir(byteArrayOf(it), mensajes::add) }
        assertEquals(listOf(esperado), mensajes)
    }
    @Test fun eventSoloSolicitaEstadoSinInventarDiagnostico() {
        val resultado = recibir(verificado, "WIFI", "RECONNECTING", TipoMensajeSafeHome.EVENT)
        assertTrue(resultado.solicitarEstado)
        assertEquals(ConexionRedPortatil.DESCONOCIDA, resultado.estado.wifi)
    }
    @Test fun nombreUsbYVendorNoSustituyenHandshake() {
        val estado = abierto.copy(nombreUsb = "ESP32 / CP210x", vendorId = 0x10C4, productId = 0xEA60)
        assertFalse(estado.verificado)
        assertFalse(recibir(estado, "DEVICE", "SH-M-001").estado.verificado)
    }
    @Test fun noPermiteOperacionesSimultaneas() {
        val pendiente = ping()
        assertEquals(pendiente, iniciarComandoPortatil(pendiente, ComandoPortatil.STATUS, 43))
        assertEquals(abierto, iniciarComandoPortatil(abierto, ComandoPortatil.PING, 44))
    }
}
