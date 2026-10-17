package com.example.safehome

import com.google.firebase.Timestamp

const val EVENTOS_POR_PAGINA = 3
const val LIMITE_CONSULTA_HISTORIAL = 4L

sealed interface EventoHistorial {
    val horaLocal: String

    data class EstadoActualizado(val puertaAbierta: Boolean, val personaPresente: Boolean, override val horaLocal: String) : EventoHistorial
    data class ReconocimientoSolicitado(val secuenciaComando: Long, override val horaLocal: String) : EventoHistorial
    data class SistemaActualizado(val sistemaActivo: Boolean, override val horaLocal: String) : EventoHistorial

    data class PresenciaMovilActualizada(val nombre: String, val conectado: Boolean, override val horaLocal: String) : EventoHistorial
}

sealed interface ResultadoEventoHistorial {
    data class Valido(val evento: EventoHistorial) : ResultadoEventoHistorial
    data object Invalido : ResultadoEventoHistorial
}

data class OperacionReconocimientoConHistorial(
    val secuencia: Long,
    val incluyeEvento: Boolean = true
)

data class PaginaHistorial(
    val eventos: List<EventoHistorial>,
    val haySiguiente: Boolean
)

fun seleccionarPaginaHistorial(eventosLeidos: List<EventoHistorial>): PaginaHistorial =
    PaginaHistorial(eventosLeidos.take(EVENTOS_POR_PAGINA), eventosLeidos.size > EVENTOS_POR_PAGINA)

fun puedeIrPaginaAnterior(pagina: Int): Boolean = pagina > 0

fun validarEventoHistorial(datos: Map<String, Any?>): ResultadoEventoHistorial {
    val hora = formatearHoraLocal(datos["creadoEn"] as? Timestamp ?: return ResultadoEventoHistorial.Invalido)
        ?: return ResultadoEventoHistorial.Invalido
    return when (datos["tipo"]) {
        "ESTADO_ACTUALIZADO" -> {
            val puerta = datos["puertaAbierta"] as? Boolean ?: return ResultadoEventoHistorial.Invalido
            val presencia = datos["personaPresente"] as? Boolean ?: return ResultadoEventoHistorial.Invalido
            ResultadoEventoHistorial.Valido(EventoHistorial.EstadoActualizado(puerta, presencia, hora))
        }
        "RECONOCIMIENTO_SOLICITADO" -> {
            val secuencia = datos["secuenciaComando"] as? Long ?: return ResultadoEventoHistorial.Invalido
            if (secuencia <= 0) ResultadoEventoHistorial.Invalido else ResultadoEventoHistorial.Valido(EventoHistorial.ReconocimientoSolicitado(secuencia, hora))
        }
        "SISTEMA_ACTIVADO", "SISTEMA_DESACTIVADO" -> {
            if (datos.keys != setOf("tipo", "sistemaActivo", "creadoEn")) return ResultadoEventoHistorial.Invalido
            val activo = datos["sistemaActivo"] as? Boolean ?: return ResultadoEventoHistorial.Invalido
            if ((datos["tipo"] == "SISTEMA_ACTIVADO") != activo) ResultadoEventoHistorial.Invalido else ResultadoEventoHistorial.Valido(EventoHistorial.SistemaActualizado(activo, hora))
        }
        "MOVIL_CONECTADO", "MOVIL_DESCONECTADO" -> {
            if (datos.keys != setOf("tipo", "uidMovil", "nombreMovil", "creadoEn")) return ResultadoEventoHistorial.Invalido
            val uid = datos["uidMovil"] as? String ?: return ResultadoEventoHistorial.Invalido
            val nombre = datos["nombreMovil"] as? String ?: return ResultadoEventoHistorial.Invalido
            if (uid.isBlank()) return ResultadoEventoHistorial.Invalido
            if (validarNombreVisible(nombre) != null) ResultadoEventoHistorial.Invalido
            else ResultadoEventoHistorial.Valido(EventoHistorial.PresenciaMovilActualizada(nombre, datos["tipo"] == "MOVIL_CONECTADO", hora))
        }
        else -> ResultadoEventoHistorial.Invalido
    }
}

fun textoEventoHistorial(evento: EventoHistorial): List<String> = when (evento) {
    is EventoHistorial.EstadoActualizado -> listOf("Estado actualizado", if (evento.puertaAbierta) "Puerta abierta" else "Puerta cerrada", if (evento.personaPresente) "Presencia detectada" else "Sin señal")
    is EventoHistorial.ReconocimientoSolicitado -> listOf("Solicitud de reconocimiento enviada")
    is EventoHistorial.SistemaActualizado -> listOf(if (evento.sistemaActivo) "Sistema activado" else "Sistema desactivado")
    is EventoHistorial.PresenciaMovilActualizada -> listOf(evento.nombre, if (evento.conectado) "Móvil conectado" else "Móvil desconectado")
}

fun respuestaHistorialEsVigente(
    idActivo: Long,
    uidActivo: String,
    hogarActivo: String,
    sesionActiva: Long,
    uidFirebaseActual: String?,
    idRespuesta: Long,
    uidRespuesta: String,
    hogarRespuesta: String,
    sesionRespuesta: Long
): Boolean = respuestaEstadoEsVigente(
    idActivo, uidActivo, hogarActivo, sesionActiva, uidFirebaseActual,
    idRespuesta, uidRespuesta, hogarRespuesta, sesionRespuesta
)

fun resultadoPaginaHistorialEsVigente(
    idActivo: Long,
    uidActivo: String,
    hogarActivo: String,
    sesionActiva: Long,
    generacionActiva: Long,
    uidFirebaseActual: String?,
    idRespuesta: Long,
    uidRespuesta: String,
    hogarRespuesta: String,
    sesionRespuesta: Long,
    generacionRespuesta: Long
): Boolean = generacionActiva == generacionRespuesta && respuestaHistorialEsVigente(
    idActivo, uidActivo, hogarActivo, sesionActiva, uidFirebaseActual,
    idRespuesta, uidRespuesta, hogarRespuesta, sesionRespuesta
)
