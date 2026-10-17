package com.example.safehome

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val TIEMPO_MAXIMO_SIN_LATIDO_MS = 75_000L

/** Presencia validada; no representa conectividad hasta una futura lectura confirmada. */
data class PresenciaMovil(
    val conectado: Boolean,
    val ultimaConexion: Long,
    val ultimoLatido: Long
)

sealed interface EstadoPresenciaMovilUi {
    data object NoDisponible : EstadoPresenciaMovilUi
    data object Cargando : EstadoPresenciaMovilUi
    data class Valida(val presencia: PresenciaMovil) : EstadoPresenciaMovilUi
    data class Error(val mensaje: String) : EstadoPresenciaMovilUi
}

sealed interface EstadoPresenciaPropiaUi {
    data object Conectando : EstadoPresenciaPropiaUi
    data object Activa : EstadoPresenciaPropiaUi
    data object SinConexion : EstadoPresenciaPropiaUi
    data object Error : EstadoPresenciaPropiaUi
}

enum class EstadoVisualPresencia { ACTIVO, DESCONECTADO, SIN_DATOS, ERROR }
enum class EventoPresencia { MOVIL_CONECTADO, MOVIL_DESCONECTADO }
data class DecisionRegistroPresencia(val estadoPersistido: String, val evento: EventoPresencia?)
data class PresenciaMovilUi(val uidInterno: String, val nombreVisible: String, val estadoFinal: EstadoVisualPresencia)
data class SeguimientoAlertaConexion(
    val ultimoEstado: EstadoVisualPresencia? = null,
    val desconexionAlertada: Boolean = false
)
data class DecisionAlertaConexion(
    val seguimiento: SeguimientoAlertaConexion,
    val debeAlertarDesconexion: Boolean = false,
    val debeCancelarPorReconexión: Boolean = false,
    val esEstadoInicial: Boolean = false,
    val esDuplicada: Boolean = false
)

sealed interface ResultadoPresenciaMovil {
    data class Valida(val presencia: PresenciaMovil) : ResultadoPresenciaMovil
    data object Invalida : ResultadoPresenciaMovil
}

fun validarPresenciaMovil(datos: Map<String, Any?>): ResultadoPresenciaMovil {
    if (datos.keys != setOf("conectado", "ultimaConexion", "ultimoLatido")) return ResultadoPresenciaMovil.Invalida
    val conectado = datos["conectado"] as? Boolean ?: return ResultadoPresenciaMovil.Invalida
    val ultimaConexion = datos["ultimaConexion"] as? Long ?: return ResultadoPresenciaMovil.Invalida
    val ultimoLatido = datos["ultimoLatido"] as? Long ?: return ResultadoPresenciaMovil.Invalida
    if (ultimaConexion <= 0L || ultimoLatido <= 0L) return ResultadoPresenciaMovil.Invalida
    return ResultadoPresenciaMovil.Valida(PresenciaMovil(conectado, ultimaConexion, ultimoLatido))
}

fun convertirPresenciaMovil(datos: Any?): ResultadoPresenciaMovil {
    val origen = datos as? Map<*, *> ?: return ResultadoPresenciaMovil.Invalida
    val mapa = LinkedHashMap<String, Any?>(origen.size)
    for ((clave, valor) in origen) {
        val claveTexto = clave as? String ?: return ResultadoPresenciaMovil.Invalida
        mapa[claveTexto] = valor
    }
    return validarPresenciaMovil(mapa)
}

fun clasificarPresenciaMovil(datos: Any?): EstadoPresenciaMovilUi = when (val resultado = convertirPresenciaMovil(datos)) {
    is ResultadoPresenciaMovil.Valida -> EstadoPresenciaMovilUi.Valida(resultado.presencia)
    ResultadoPresenciaMovil.Invalida -> EstadoPresenciaMovilUi.NoDisponible
}

fun estadoVisualPresencia(estado: EstadoPresenciaMovilUi?, horaServidorActual: Long? = null): EstadoVisualPresencia = when (estado) {
    is EstadoPresenciaMovilUi.Valida -> {
        val edad = (horaServidorActual ?: estado.presencia.ultimoLatido) - estado.presencia.ultimoLatido
        if (estado.presencia.conectado && edad in 0..TIEMPO_MAXIMO_SIN_LATIDO_MS) EstadoVisualPresencia.ACTIVO else EstadoVisualPresencia.DESCONECTADO
    }
    is EstadoPresenciaMovilUi.Error -> EstadoVisualPresencia.ERROR
    EstadoPresenciaMovilUi.Cargando, EstadoPresenciaMovilUi.NoDisponible, null -> EstadoVisualPresencia.SIN_DATOS
}

fun textoEstadoVisualPresencia(estado: EstadoPresenciaMovilUi?): String = when (estadoVisualPresencia(estado)) {
    EstadoVisualPresencia.ACTIVO -> "Activo"
    EstadoVisualPresencia.DESCONECTADO -> "Desconectado"
    EstadoVisualPresencia.SIN_DATOS -> "Sin datos"
    EstadoVisualPresencia.ERROR -> "Error de conexión"
}

fun textoEstadoVisualPresencia(estadoVisual: EstadoVisualPresencia): String = when (estadoVisual) {
    EstadoVisualPresencia.ACTIVO -> "Activo"
    EstadoVisualPresencia.DESCONECTADO -> "Desconectado"
    EstadoVisualPresencia.SIN_DATOS -> "Sin datos"
    EstadoVisualPresencia.ERROR -> "Error de conexión"
}

fun decidirRegistroPresencia(estadoRegistrado: String?, presencia: PresenciaMovil): DecisionRegistroPresencia =
    if (presencia.conectado) DecisionRegistroPresencia("ACTIVO", if (estadoRegistrado == "ACTIVO") null else EventoPresencia.MOVIL_CONECTADO)
    else DecisionRegistroPresencia("DESCONECTADO", if (estadoRegistrado == "ACTIVO") EventoPresencia.MOVIL_DESCONECTADO else null)

fun uidEstaVinculado(uid: String, vinculados: Collection<String>): Boolean = uid.isNotBlank() && uid in vinculados

fun respuestaPresenciaEsVigente(
    uidActivo: String,
    hogarActivo: String,
    sesionActiva: Long,
    generacionActiva: Long,
    uidFirebaseActual: String?,
    uidRespuesta: String,
    hogarRespuesta: String,
    sesionRespuesta: Long,
    generacionRespuesta: Long
): Boolean = uidActivo == uidRespuesta && hogarActivo == hogarRespuesta &&
    sesionActiva == sesionRespuesta && generacionActiva == generacionRespuesta && uidFirebaseActual == uidRespuesta

fun decidirAlertaConexion(
    seguimiento: SeguimientoAlertaConexion,
    estadoActual: EstadoVisualPresencia,
    contextoVigente: Boolean = true
): DecisionAlertaConexion {
    if (!contextoVigente) return DecisionAlertaConexion(seguimiento)
    val anterior = seguimiento.ultimoEstado
    if (anterior == null) return DecisionAlertaConexion(
        seguimiento = seguimiento.copy(ultimoEstado = estadoActual),
        esEstadoInicial = true
    )
    if (estadoActual == EstadoVisualPresencia.ACTIVO) return DecisionAlertaConexion(
        seguimiento = SeguimientoAlertaConexion(EstadoVisualPresencia.ACTIVO, false),
        debeCancelarPorReconexión = seguimiento.desconexionAlertada
    )
    val alerta = anterior == EstadoVisualPresencia.ACTIVO && estadoActual == EstadoVisualPresencia.DESCONECTADO && !seguimiento.desconexionAlertada
    return DecisionAlertaConexion(
        seguimiento = SeguimientoAlertaConexion(estadoActual, seguimiento.desconexionAlertada || alerta),
        debeAlertarDesconexion = alerta,
        esDuplicada = estadoActual == EstadoVisualPresencia.DESCONECTADO && seguimiento.desconexionAlertada
    )
}

fun debeVibrarPorDesconexionMovil(anterior: EstadoPresenciaServicio?, actual: EstadoPresenciaServicio): Boolean =
    anterior == EstadoPresenciaServicio.ACTIVA && actual == EstadoPresenciaServicio.SIN_CONEXION

/** Condición pura del watchdog: no altera sesión, listener ni estado de heartbeat. */
fun debeEjecutarRecuperacionForzada(
    contextoVigente: Boolean,
    redValida: Boolean,
    rtdbConfirmado: Boolean,
    recuperacionForzadaEjecutada: Boolean
): Boolean = contextoVigente && redValida && !rtdbConfirmado && !recuperacionForzadaEjecutada

/** El listener de /.info/connected se conserva durante todo el contexto válido del servicio. */
fun debeInstalarListenerConexion(contextoVigente: Boolean, listenerYaInstalado: Boolean): Boolean =
    contextoVigente && !listenerYaInstalado

/** Un true confirmado solo republica una presencia si no hay una publicación válida en curso. */
fun debeRepublicarTrasConfirmacionRtdb(
    contextoVigente: Boolean,
    rtdbConfirmado: Boolean,
    presenciaActiva: Boolean,
    republicacionEnCurso: Boolean
): Boolean = contextoVigente && rtdbConfirmado && !presenciaActiva && !republicacionEnCurso

fun formatearHoraPresencia(milisegundos: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(milisegundos))
