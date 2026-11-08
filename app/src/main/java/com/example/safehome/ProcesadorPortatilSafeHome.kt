package com.example.safehome

private const val ERROR_IDENTIDAD_PORTATIL = "El dispositivo conectado no corresponde a un dispositivo portátil SafeHome."
enum class IdentidadPortatil { PENDIENTE, VERIFICADA, RECHAZADA }

fun validarIdentidadPortatil(id: String?, rol: String?): IdentidadPortatil = when {
    rol != null && rol != "PORTABLE" -> IdentidadPortatil.RECHAZADA
    id != null && !Regex("SH-M-[A-Za-z0-9_-]{1,64}").matches(id) -> IdentidadPortatil.RECHAZADA
    id != null && rol == "PORTABLE" -> IdentidadPortatil.VERIFICADA
    else -> IdentidadPortatil.PENDIENTE
}

data class ResultadoPortatil(val estado: EstadoHardwarePortatil, val solicitarEstado: Boolean = false)

fun mensajeComandoPortatil(comando: ComandoPortatil, secuencia: Long) =
    MensajeSafeHome(secuencia, TipoMensajeSafeHome.CMD, comando.name, comando.valor)

fun iniciarComandoPortatil(estado: EstadoHardwarePortatil, comando: ComandoPortatil, secuencia: Long): EstadoHardwarePortatil {
    if (!estado.conectado || estado.operacion != null || (comando == ComandoPortatil.STATUS && !estado.verificado)) return estado
    return estado.copy(operacion = OperacionPortatil(comando, secuencia), error = null,
        prueba = if (comando == ComandoPortatil.PING) PruebaPortatil.ESPERANDO else estado.prueba)
}

fun puertoPortatilAbierto(estado: EstadoHardwarePortatil, secuenciaHello: Long): EstadoHardwarePortatil =
    iniciarComandoPortatil(estado.copy(conexion = ConexionUsbPortatil.CONECTADO_NO_VERIFICADO,
        idSafeHome = null, rolSafeHome = null, wifi = ConexionRedPortatil.DESCONOCIDA, rssi = null,
        mqtt = ConexionRedPortatil.DESCONOCIDA, heartbeat = HeartbeatPortatil.DESCONOCIDO,
        ultimoMensajeValido = null, operacion = null, prueba = PruebaPortatil.SIN_PROBAR, error = null,
        solicitandoPermiso = false), ComandoPortatil.HELLO, secuenciaHello)

fun expirarComandoPortatil(estado: EstadoHardwarePortatil, secuencia: Long): EstadoHardwarePortatil =
    if (estado.operacion?.secuencia != secuencia) estado else estado.copy(operacion = null,
        error = "El dispositivo portátil no confirmó la solicitud.",
        prueba = if (estado.operacion.comando == ComandoPortatil.PING) PruebaPortatil.SIN_RESPUESTA else estado.prueba)

/** Solo mensajes SH1 previamente parseados. Sin Firebase ni presencia digital Android. */
fun procesarMensajePortatil(previo: EstadoHardwarePortatil, mensaje: MensajeSafeHome): ResultadoPortatil {
    if (!previo.conectado) return ResultadoPortatil(previo)
    var estado = previo.copy(ultimoMensajeValido = mensaje)
    if (mensaje.tipo == TipoMensajeSafeHome.STATE && mensaje.dato in setOf("DEVICE", "ROLE")) {
        estado = if (mensaje.dato == "DEVICE") estado.copy(idSafeHome = mensaje.valor) else estado.copy(rolSafeHome = mensaje.valor)
        when (validarIdentidadPortatil(estado.idSafeHome, estado.rolSafeHome)) {
            IdentidadPortatil.RECHAZADA -> return ResultadoPortatil(invalidarEstadoPortatil(estado).copy(
                conexion = ConexionUsbPortatil.ERROR, error = ERROR_IDENTIDAD_PORTATIL))
            IdentidadPortatil.VERIFICADA -> {
                val identidadCambio = previo.verificado && previo.idSafeHome != estado.idSafeHome
                if (identidadCambio) return ResultadoPortatil(invalidarEstadoPortatil(estado).copy(
                    conexion = ConexionUsbPortatil.ERROR, error = ERROR_IDENTIDAD_PORTATIL))
                return ResultadoPortatil(estado.copy(conexion = ConexionUsbPortatil.CONECTADO_VERIFICADO,
                    operacion = if (estado.operacion?.comando == ComandoPortatil.HELLO) null else estado.operacion), !previo.verificado)
            }
            IdentidadPortatil.PENDIENTE -> return ResultadoPortatil(estado)
        }
    }
    val pendiente = estado.operacion
    if (mensaje.tipo == TipoMensajeSafeHome.ERR) {
        val asociado = pendiente != null && (mensaje.dato.toLongOrNull() == pendiente.secuencia || mensaje.dato == pendiente.comando.name)
        // El firmware también define este error sin secuencia: no puede confirmar ninguna operación.
        val prohibido = mensaje.dato == "PERMISSION" && mensaje.valor == "NOT_ALLOWED"
        return ResultadoPortatil(if (asociado || prohibido) estado.copy(operacion = null,
            prueba = if (pendiente?.comando == ComandoPortatil.PING) PruebaPortatil.SIN_RESPUESTA else estado.prueba,
            error = "El dispositivo portátil rechazó la solicitud.") else estado)
    }
    if (mensaje.tipo == TipoMensajeSafeHome.ACK && pendiente?.comando == ComandoPortatil.PING &&
        mensaje.dato.toLongOrNull() == pendiente.secuencia) {
        return ResultadoPortatil(estado.copy(operacion = null,
            prueba = if (mensaje.valor == "OK") PruebaPortatil.RESPUESTA_VALIDA else PruebaPortatil.SIN_RESPUESTA,
            error = if (mensaje.valor == "OK") null else "El dispositivo portátil rechazó la prueba."))
    }
    if (!estado.verificado) return ResultadoPortatil(estado)
    if (mensaje.tipo == TipoMensajeSafeHome.EVENT) return ResultadoPortatil(estado,
        solicitarEstado = pendiente == null && when (mensaje.dato) {
            "WIFI" -> mensaje.valor in setOf("CONNECTED", "DISCONNECTED", "RECONNECTING")
            "MQTT" -> mensaje.valor in setOf("CONNECTED", "DISCONNECTED")
            else -> false
        })
    if (mensaje.tipo != TipoMensajeSafeHome.STATE) return ResultadoPortatil(estado)
    val campo = CampoDiagnosticoPortatil.entries.find { it.name == mensaje.dato } ?: return ResultadoPortatil(estado)
    when (campo) {
        CampoDiagnosticoPortatil.WIFI, CampoDiagnosticoPortatil.MQTT -> {
            val red = when (mensaje.valor) {
                "CONNECTED" -> ConexionRedPortatil.CONECTADO
                "DISCONNECTED" -> ConexionRedPortatil.DESCONECTADO
                else -> return ResultadoPortatil(estado)
            }
            estado = if (campo == CampoDiagnosticoPortatil.WIFI) estado.copy(wifi = red) else estado.copy(mqtt = red)
        }
        CampoDiagnosticoPortatil.RSSI -> {
            val rssi = if (mensaje.valor == "UNKNOWN") null else mensaje.valor.toIntOrNull()?.takeIf { it in -127..0 }
                ?: return ResultadoPortatil(estado)
            estado = estado.copy(rssi = rssi)
        }
        CampoDiagnosticoPortatil.HEARTBEAT -> estado = estado.copy(heartbeat = when (mensaje.valor) {
            "ACTIVE" -> HeartbeatPortatil.ACTIVO
            "INACTIVE" -> HeartbeatPortatil.INACTIVO
            else -> return ResultadoPortatil(estado)
        })
    }
    if (pendiente?.comando == ComandoPortatil.STATUS) {
        val campos = pendiente.camposRecibidos + campo
        estado = estado.copy(operacion = if (campos.size == CampoDiagnosticoPortatil.entries.size) null else pendiente.copy(camposRecibidos = campos))
    }
    return ResultadoPortatil(estado)
}
