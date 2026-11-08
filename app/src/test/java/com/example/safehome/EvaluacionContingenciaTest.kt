package com.example.safehome

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Contratos Android, no simulación ni validación del firmware ESP32. */
class EvaluacionContingenciaTest {
    private fun fuente(nombre: String): String {
        val raiz = listOf(File("src/main/java/com/example/safehome"), File("app/src/main/java/com/example/safehome")).first { it.isDirectory }
        return File(raiz, nombre).readText()
    }

    @Test fun presenciaDigitalYTransporteUsbNoEscribenEstadoEstacion() {
        // Contrato estructural: complementa los reducers sin fingir ejecutar servicios Android.
        for (archivo in listOf("ServicioPresenciaMovil.kt", "ServicioSupervisionEstacion.kt", "EnlaceUsbPortatil.kt")) {
            val codigo = fuente(archivo)
            for (dependencia in listOf("EnlaceBluetoothEstacion", "procesarMensajeHardware", "EstadoHardwareSafeHome")) {
                assertFalse("$archivo depende de $dependencia", codigo.contains(dependencia))
            }
        }
        val fisico = fuente("ProcesadorHardwareSafeHome.kt") + fuente("ProcesadorPortatilSafeHome.kt")
        for (dependencia in listOf("FirebaseDatabase", "ServicioPresenciaMovil", "EstadoPresenciaMovilUi")) {
            assertFalse(fisico.contains(dependencia))
        }
    }

    @Test fun comandosDeActuadoresExigenGuardiaDeRolEstacion() {
        val codigo = fuente("ControlSesion.kt")
        val guardia = codigo.substringAfter("private fun puedeUsarBluetoothEstacion()").substringBefore("fun obtenerDispositivosBluetoothVinculados")
        assertTrue(guardia.contains("auth.currentUser != null"))
        assertTrue(guardia.contains("rol?.esEstacion() == true"))
        for (funcion in listOf("encenderSistemaIoT", "apagarSistemaIoT", "reconocerAlarmaIoT")) {
            val cuerpo = codigo.substringAfter("fun $funcion() {").substringBefore("\n    }")
            assertTrue(cuerpo.contains("if (puedeUsarBluetoothEstacion() &&"))
        }
    }
    private fun mensaje(dato: String, valor: String) = MensajeSafeHome(1, TipoMensajeSafeHome.STATE, dato, valor)
    private fun verificado() = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_VERIFICADO,
        sistema = SistemaHardware.ENCENDIDO, puerta = PuertaHardware.CERRADA,
        portatil = PortatilHardware.PRESENTE, alarma = AlarmaHardware.NINGUNA)

    @Test fun desconexionInvalidaLaRepresentacionAndroid() {
        val estado = invalidarEstadoHardware(verificado())
        assertFalse(estado.verificado)
        assertEquals(SistemaHardware.DESCONOCIDO, estado.sistema)
        assertEquals(AlarmaHardware.DESCONOCIDA, estado.alarma)
        assertNull(estado.operacion)
    }

    @Test fun reconexionNoAceptaEstadoAntesDeIdentidadCompleta() {
        var estado = invalidarEstadoHardware(verificado()).copy(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO)
        estado = procesarMensajeHardware(estado, mensaje("DEVICE", "SH-E-001")).estado
        val prematuro = procesarMensajeHardware(estado, mensaje("SYSTEM", "ON"))
        assertEquals(SistemaHardware.DESCONOCIDO, prematuro.estado.sistema)
        assertFalse(prematuro.estado.verificado)
        val identidad = procesarMensajeHardware(prematuro.estado, mensaje("ROLE", "STATION"))
        assertTrue(identidad.estado.verificado)
        assertTrue(identidad.solicitarEstado)
    }

    @Test fun respuestasStatusReconstruyenSinInventarHistorial() {
        var estado = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO)
        estado = procesarMensajeHardware(estado, mensaje("DEVICE", "SH-E-001")).estado
        estado = procesarMensajeHardware(estado, mensaje("ROLE", "STATION")).estado
        for ((dato, valor) in listOf("SYSTEM" to "ON", "DOOR" to "OPEN", "PORTABLE" to "OUT", "ALARM" to "CRITICAL")) {
            val resultado = procesarMensajeHardware(estado, mensaje(dato, valor))
            assertNull(resultado.evento)
            estado = resultado.estado
        }
        assertEquals(SistemaHardware.ENCENDIDO, estado.sistema)
        assertEquals(PuertaHardware.ABIERTA, estado.puerta)
        assertEquals(PortatilHardware.FUERA_RANGO, estado.portatil)
        assertEquals(AlarmaHardware.CRITICA, estado.alarma)
    }

    @Test fun unknownNoEsSalidaNiAlarmaNueva() {
        val resultado = procesarMensajeHardware(verificado(), mensaje("PORTABLE", "UNKNOWN"))
        assertEquals(PortatilHardware.DESCONOCIDO, resultado.estado.portatil)
        assertEquals(AlarmaHardware.NINGUNA, resultado.estado.alarma)
        assertNull(resultado.evento)
    }

    @Test fun diagnosticoDeInfraestructuraNoInfiereOut() {
        var estado = verificado()
        for (dato in listOf("WIFI", "MQTT")) {
            val resultado = procesarMensajeHardware(estado, mensaje(dato, "DISCONNECTED"))
            assertNull(resultado.evento)
            estado = resultado.estado
        }
        assertEquals(PortatilHardware.PRESENTE, estado.portatil)
        // Android necesita un STATE UNKNOWN del firmware: no tiene observador MQTT propio.
        assertEquals(PortatilHardware.DESCONOCIDO,
            procesarMensajeHardware(estado, mensaje("PORTABLE", "UNKNOWN")).estado.portatil)
    }

    @Test fun unknownNoBorraUnaAlarmaCriticaConfirmada() {
        val resultado = procesarMensajeHardware(verificado().copy(alarma = AlarmaHardware.CRITICA), mensaje("PORTABLE", "UNKNOWN"))
        assertEquals(AlarmaHardware.CRITICA, resultado.estado.alarma)
        assertNull(resultado.evento)
    }

    private class Almacen : AlmacenEventosHardware {
        private val datos = mutableMapOf<String, String>()
        override fun leer() = datos.toMap()
        override fun escribir(clave: String, valor: String?): Boolean {
            if (valor == null) datos.remove(clave) else datos[clave] = valor
            return true
        }
    }

    @Test fun alarmasSeRepresentanYRecuperanTrasRecrearMotorSinFirebase() = runBlocking {
        val almacen = Almacen()
        val documentos = mutableSetOf<String>()
        var disponible = false
        val destino = object : DestinoHistorialHardware {
            override suspend fun guardar(evento: EventoHardwarePendiente, vigente: () -> Boolean): ResultadoDestinoHardware {
                if (!disponible) return ResultadoDestinoHardware.TRANSITORIO
                documentos.add(evento.idEvento)
                return ResultadoDestinoHardware.EXITO
            }
        }
        val motor = SincronizadorEventosHardware(ColaEventosHardwareLocal(almacen), destino)
        motor.establecerHogar("hogar-a")
        var estado = verificado()
        for (alarma in listOf("WARNING", "CRITICAL")) {
            val resultado = procesarMensajeHardware(estado, mensaje("ALARM", alarma))
            assertNotNull(resultado.evento)
            assertTrue(motor.registrar("hogar-a", resultado.evento!!))
            motor.sincronizar()
            estado = resultado.estado
        }
        assertEquals(AlarmaHardware.CRITICA, estado.alarma)
        motor.establecerHogar(null)
        val recreado = SincronizadorEventosHardware(ColaEventosHardwareLocal(almacen), destino)
        recreado.establecerHogar("hogar-b")
        disponible = true
        recreado.sincronizar()
        assertTrue(documentos.isEmpty())
        assertEquals(2, ColaEventosHardwareLocal(almacen).leer().eventos.size)
        recreado.establecerHogar("hogar-a")
        recreado.sincronizar()
        assertEquals(2, documentos.size)
        assertTrue(ColaEventosHardwareLocal(almacen).leer().eventos.isEmpty())
    }

    @Test fun heartbeatNoEsUnEstadoPortableNiEventoFisico() {
        for (valor in listOf("PRESENTE", "ACTIVE", "INACTIVE")) {
            val resultado = procesarMensajeHardware(verificado(), mensaje("HEARTBEAT", valor))
            assertEquals(PortatilHardware.PRESENTE, resultado.estado.portatil)
            assertNull(resultado.evento)
        }
    }

    @Test fun movilSoloGeneraComandosDeDiagnostico() {
        val mensajes = ComandoPortatil.entries.map { mensajeComandoPortatil(it, 1) }
        assertEquals(setOf("HELLO", "PING", "STATUS"), mensajes.map { it.dato }.toSet())
        assertFalse(mensajes.any { it.dato == "SYSTEM" || it.dato == "ALARM" })
    }

    @Test fun rolPortableRechazadoEnHandshakeEstacion() {
        val estado = EstadoHardwareSafeHome(conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO)
        val identidad = procesarMensajeHardware(estado, mensaje("DEVICE", "SH-E-001")).estado
        assertEquals(ConexionEstacionIoT.ERROR, procesarMensajeHardware(identidad, mensaje("ROLE", "PORTABLE")).estado.conexion)
    }

    @Test fun rolStationRechazadoEnHandshakePortatil() {
        val estado = puertoPortatilAbierto(EstadoHardwarePortatil(), 1)
        val identidad = procesarMensajePortatil(estado, mensaje("DEVICE", "SH-M-001")).estado
        assertEquals(ConexionUsbPortatil.ERROR, procesarMensajePortatil(identidad, mensaje("ROLE", "STATION")).estado.conexion)
    }

    @Test fun reconexionUsbDescartaDiagnosticoEIdentidad() {
        val estado = EstadoHardwarePortatil(conexion = ConexionUsbPortatil.CONECTADO_VERIFICADO,
            idSafeHome = "SH-M-001", rolSafeHome = "PORTABLE", heartbeat = HeartbeatPortatil.ACTIVO)
        val nuevo = puertoPortatilAbierto(invalidarEstadoPortatil(estado), 2)
        assertFalse(nuevo.verificado)
        assertNull(nuevo.idSafeHome)
        assertEquals(HeartbeatPortatil.DESCONOCIDO, nuevo.heartbeat)
        assertEquals(ComandoPortatil.HELLO, nuevo.operacion?.comando)
    }

    @Test fun logsNoIncluyenExcepcionesCrudas() {
        val raiz = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val inseguros = raiz.walkTopDown().filter { it.extension == "kt" }.flatMap { archivo ->
            archivo.readLines().filter { it.contains(Regex("Log\\.[diwev]\\(")) &&
                (it.contains("error?.message") || it.contains("error.toException()") || it.contains(", error)")) }
                .map { archivo.name + ": " + it.trim() }
        }.toList()
        assertTrue("Logs con excepciones crudas: $inseguros", inseguros.isEmpty())
    }
}
