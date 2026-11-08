package com.example.safehome

enum class EventoFisico(val texto: String) {
    SISTEMA_ACTIVADO("Sistema activado"), SISTEMA_DESACTIVADO("Sistema desactivado"),
    PUERTA_ABIERTA("Puerta abierta"), PUERTA_CERRADA("Puerta cerrada"),
    PORTATIL_FUERA_RANGO("Dispositivo portátil fuera de rango"), PORTATIL_RECONECTADO("Dispositivo portátil nuevamente dentro de rango"),
    ALARMA_ADVERTENCIA("Alarma: advertencia"), ALARMA_CRITICA("Alarma: alerta crítica"),
    ALARMA_RECONOCIDA("Alarma reconocida")
}

data class ResultadoHardware(
    val estado: EstadoHardwareSafeHome,
    val solicitarEstado: Boolean = false,
    val evento: EventoFisico? = null,
    val sistemaParaSincronizar: Boolean? = null
)

fun iniciarOperacionHardware(estado: EstadoHardwareSafeHome, accion: AccionHardware, secuencia: Long): EstadoHardwareSafeHome {
    if (!estado.verificado || estado.operacion != null) return estado
    val permitida = when (accion) {
        AccionHardware.ENCENDER -> estado.sistema == SistemaHardware.APAGADO
        AccionHardware.APAGAR -> estado.sistema == SistemaHardware.ENCENDIDO
        AccionHardware.RECONOCER_ALARMA -> estado.alarma in setOf(AlarmaHardware.ADVERTENCIA, AlarmaHardware.CRITICA)
    }
    return if (permitida) estado.copy(operacion = OperacionHardware(secuencia, accion), errorOperacion = null) else estado
}

fun expirarOperacionHardware(estado: EstadoHardwareSafeHome, secuencia: Long): ResultadoHardware =
    if (estado.operacion?.secuencia == secuencia) ResultadoHardware(
        estado.copy(operacion = null, errorOperacion = "Estación no confirmó la acción."), solicitarEstado = estado.verificado
    ) else ResultadoHardware(estado)

/** Función pura. Solo recibe mensajes ya validados por ProtocoloSafeHome.
 * EVENT nunca es fuente de estado ni de historial; la primera lectura es una base, no una transición.
 * No recibe presencia digital Android ni calcula un timeout del portátil. */
fun procesarMensajeHardware(previo: EstadoHardwareSafeHome, mensaje: MensajeSafeHome): ResultadoHardware {
    if (!previo.conectado) return ResultadoHardware(previo)
    var siguiente = previo.copy(ultimoMensajeValido = mensaje)
    if (mensaje.tipo == TipoMensajeSafeHome.STATE && mensaje.dato in setOf("DEVICE", "ROLE")) {
        siguiente = if (mensaje.dato == "DEVICE") siguiente.copy(idDispositivo = mensaje.valor)
            else siguiente.copy(rolDispositivo = mensaje.valor)
        return when (validarHandshakeEstacion(siguiente.idDispositivo, siguiente.rolDispositivo)) {
            ValidacionEstacion.RECHAZADA -> ResultadoHardware(invalidarEstadoHardware(siguiente).copy(
                conexion = ConexionEstacionIoT.ERROR, error = "El dispositivo conectado no corresponde a una Estación SafeHome."))
            ValidacionEstacion.PENDIENTE -> ResultadoHardware(invalidarEstadoHardware(siguiente).copy(
                conexion = ConexionEstacionIoT.CONECTADO_NO_VERIFICADO,
                idDispositivo = siguiente.idDispositivo, rolDispositivo = siguiente.rolDispositivo))
            ValidacionEstacion.VERIFICADA -> ResultadoHardware(
                siguiente.copy(conexion = ConexionEstacionIoT.CONECTADO_VERIFICADO), solicitarEstado = !previo.verificado)
        }
    }
    if (!previo.verificado) return ResultadoHardware(siguiente)
    val operacion = previo.operacion
    if (operacion != null && mensaje.dato.toLongOrNull() == operacion.secuencia) {
        if (mensaje.tipo == TipoMensajeSafeHome.ERR || (mensaje.tipo == TipoMensajeSafeHome.ACK && mensaje.valor != "OK")) {
            return ResultadoHardware(siguiente.copy(operacion = null,
                errorOperacion = "La Estación rechazó la acción."), solicitarEstado = true)
        }
        if (mensaje.tipo == TipoMensajeSafeHome.ACK) {
            return ResultadoHardware(siguiente.copy(operacion = operacion.copy(ackRecibido = true)))
        }
    }
    if (mensaje.tipo != TipoMensajeSafeHome.STATE) return ResultadoHardware(siguiente,
        solicitarEstado = mensaje.tipo == TipoMensajeSafeHome.EVENT && when (mensaje.dato) {
            "SYSTEM" -> mensaje.valor in setOf("ON", "OFF")
            "DOOR" -> mensaje.valor in setOf("OPENED", "CLOSED")
            "PORTABLE" -> mensaje.valor in setOf("PRESENT", "OUT", "RETURNED")
            "ALARM" -> mensaje.valor in setOf("WARNING", "CRITICAL", "ACKNOWLEDGED")
            else -> false
        })
    siguiente = when (mensaje.dato) {
        "SYSTEM" -> when (mensaje.valor) {
            "ON" -> siguiente.copy(sistema = SistemaHardware.ENCENDIDO)
            "OFF" -> siguiente.copy(sistema = SistemaHardware.APAGADO)
            else -> siguiente
        }
        "DOOR" -> when (mensaje.valor) {
            "OPEN" -> siguiente.copy(puerta = PuertaHardware.ABIERTA)
            "CLOSED" -> siguiente.copy(puerta = PuertaHardware.CERRADA)
            else -> siguiente
        }
        "PORTABLE" -> when (mensaje.valor) {
            "PRESENT" -> siguiente.copy(portatil = PortatilHardware.PRESENTE)
            "OUT" -> siguiente.copy(portatil = PortatilHardware.FUERA_RANGO)
            "UNKNOWN" -> siguiente.copy(portatil = PortatilHardware.DESCONOCIDO)
            else -> siguiente
        }
        "ALARM" -> when (mensaje.valor) {
            "NONE" -> siguiente.copy(alarma = AlarmaHardware.NINGUNA)
            "WARNING" -> siguiente.copy(alarma = AlarmaHardware.ADVERTENCIA)
            "CRITICAL" -> siguiente.copy(alarma = AlarmaHardware.CRITICA)
            else -> siguiente
        }
        else -> siguiente
    }
    val confirmada = operacion?.ackRecibido == true && when (operacion.accion) {
        AccionHardware.ENCENDER -> mensaje.dato == "SYSTEM" && mensaje.valor == "ON"
        AccionHardware.APAGAR -> mensaje.dato == "SYSTEM" && mensaje.valor == "OFF"
        AccionHardware.RECONOCER_ALARMA -> mensaje.dato == "ALARM" && mensaje.valor == "NONE"
    }
    if (confirmada) siguiente = siguiente.copy(operacion = null, errorOperacion = null)
    val evento = when {
        confirmada && operacion?.accion == AccionHardware.RECONOCER_ALARMA -> EventoFisico.ALARMA_RECONOCIDA
        previo.sistema != SistemaHardware.DESCONOCIDO && previo.sistema != siguiente.sistema ->
            if (siguiente.sistema == SistemaHardware.ENCENDIDO) EventoFisico.SISTEMA_ACTIVADO else EventoFisico.SISTEMA_DESACTIVADO
        previo.puerta != PuertaHardware.DESCONOCIDA && previo.puerta != siguiente.puerta ->
            if (siguiente.puerta == PuertaHardware.ABIERTA) EventoFisico.PUERTA_ABIERTA else EventoFisico.PUERTA_CERRADA
        previo.portatil == PortatilHardware.PRESENTE && siguiente.portatil == PortatilHardware.FUERA_RANGO -> EventoFisico.PORTATIL_FUERA_RANGO
        previo.portatil == PortatilHardware.FUERA_RANGO && siguiente.portatil == PortatilHardware.PRESENTE -> EventoFisico.PORTATIL_RECONECTADO
        previo.alarma != AlarmaHardware.DESCONOCIDA && previo.alarma != siguiente.alarma -> when (siguiente.alarma) {
            AlarmaHardware.ADVERTENCIA -> EventoFisico.ALARMA_ADVERTENCIA
            AlarmaHardware.CRITICA -> EventoFisico.ALARMA_CRITICA
            else -> null // NONE por sí solo no permite atribuir la causa a un reconocimiento.
        }
        else -> null
    }
    return ResultadoHardware(siguiente, evento = evento,
        sistemaParaSincronizar = if (mensaje.dato == "SYSTEM" && mensaje.valor in setOf("ON", "OFF") &&
            (previo.sistema != siguiente.sistema || previo.errorSincronizacion != null)) mensaje.valor == "ON" else null)
}
