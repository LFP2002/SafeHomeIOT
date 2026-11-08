package com.example.safehome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class ResultadoDestinoHardware { EXITO, TRANSITORIO, NO_AUTORIZADO, INVALIDO }
interface DestinoHistorialHardware {
    suspend fun guardar(evento: EventoHardwarePendiente, vigente: () -> Boolean): ResultadoDestinoHardware
}
enum class FaseHistorialHardware { CARGANDO, SINCRONIZADO, PENDIENTE, SINCRONIZANDO, ERROR }
data class EstadoSincronizacionHardware(val cantidadEventosPendientes: Int = 0,
    val fase: FaseHistorialHardware = FaseHistorialHardware.CARGANDO,
    val errorLocal: ErrorColaHardware? = null, val noTransitorio: Boolean = false)

/** Motor JVM: almacenamiento y destino inyectables. Un lote finito por invocación. */
class SincronizadorEventosHardware(
    private val cola: ColaEventosHardwareLocal,
    private val destino: DestinoHistorialHardware,
    private val reloj: () -> Long = System::currentTimeMillis
) {
    private data class Contexto(val hogar: String?, val generacion: Long)
    @Volatile private var contexto = Contexto(null, 0)
    private val monitor = Any()
    private val vaciado = Mutex()
    private var trabajo: Job? = null
    @Volatile private var bloqueado = false
    private val _estado = MutableStateFlow(EstadoSincronizacionHardware())
    val estado = _estado.asStateFlow()

    fun establecerHogar(hogar: String?) = synchronized(monitor) {
        contexto = Contexto(hogar, contexto.generacion + 1)
        trabajo?.cancel()
        trabajo = null
        bloqueado = false
        _estado.value = EstadoSincronizacionHardware()
    }

    suspend fun refrescar() {
        val actual = contexto
        publicar(actual, cola.leer())
    }

    suspend fun registrar(hogar: String, tipo: EventoFisico, ocurridoEnMillis: Long = reloj()): Boolean {
        val evento = EventoHardwarePendiente.crear(hogar, tipo, ocurridoEnMillis)
        val guardado = cola.agregar(evento)
        refrescar()
        return guardado
    }

    private fun publicar(actual: Contexto, lectura: LecturaColaHardware, fase: FaseHistorialHardware? = null) = synchronized(monitor) {
        if (contexto != actual) return
        val cantidad = lectura.eventos.count { it.hogarId == actual.hogar }
        val activa = fase == FaseHistorialHardware.SINCRONIZANDO ||
            (fase == null && _estado.value.fase == FaseHistorialHardware.SINCRONIZANDO)
        _estado.value = EstadoSincronizacionHardware(cantidad,
            if (activa) FaseHistorialHardware.SINCRONIZANDO
            else if (lectura.error != null || bloqueado) FaseHistorialHardware.ERROR
            else fase ?: if (_estado.value.fase == FaseHistorialHardware.SINCRONIZANDO) FaseHistorialHardware.SINCRONIZANDO
            else if (cantidad > 0) FaseHistorialHardware.PENDIENTE else FaseHistorialHardware.SINCRONIZADO,
            lectura.error, bloqueado)
    }

    suspend fun sincronizar(manual: Boolean = false): ResultadoDestinoHardware = withContext(Dispatchers.IO) {
        if (!vaciado.tryLock()) return@withContext ResultadoDestinoHardware.TRANSITORIO
        val actual = contexto
        val jobActual = currentCoroutineContext()[Job]
        fun vigente() = contexto == actual && actual.hogar != null
        try {
            synchronized(monitor) {
                if (contexto == actual) {
                    if (manual) bloqueado = false
                    trabajo = jobActual
                }
            }
            if (!vigente()) return@withContext ResultadoDestinoHardware.EXITO
            if (bloqueado) return@withContext ResultadoDestinoHardware.NO_AUTORIZADO
            val lectura = cola.leer()
            publicar(actual, lectura, FaseHistorialHardware.SINCRONIZANDO)
            // Los registros corruptos se conservan; los válidos todavía pueden enviarse.
            for (evento in lectura.eventos.filter { it.hogarId == actual.hogar }) {
                if (!vigente()) return@withContext ResultadoDestinoHardware.EXITO
                val intento = cola.marcarIntento(evento, reloj()) ?: run {
                    publicar(actual, cola.leer(), FaseHistorialHardware.ERROR)
                    return@withContext ResultadoDestinoHardware.INVALIDO
                }
                if (!vigente()) return@withContext ResultadoDestinoHardware.EXITO
                val resultado = try {
                    withTimeoutOrNull(20_000) { destino.guardar(intento, ::vigente) } ?: ResultadoDestinoHardware.TRANSITORIO
                } catch (cancelada: CancellationException) { throw cancelada }
                catch (_: Exception) { ResultadoDestinoHardware.TRANSITORIO }
                if (!vigente()) return@withContext ResultadoDestinoHardware.EXITO
                if (resultado != ResultadoDestinoHardware.EXITO) {
                    synchronized(monitor) {
                        if (vigente()) bloqueado = resultado in setOf(ResultadoDestinoHardware.NO_AUTORIZADO, ResultadoDestinoHardware.INVALIDO)
                    }
                    publicar(actual, cola.leer(), if (bloqueado) FaseHistorialHardware.ERROR else FaseHistorialHardware.PENDIENTE)
                    return@withContext resultado
                }
                if (!cola.eliminar(intento)) {
                    publicar(actual, cola.leer(), FaseHistorialHardware.ERROR)
                    return@withContext ResultadoDestinoHardware.INVALIDO
                }
                publicar(actual, cola.leer(), FaseHistorialHardware.SINCRONIZANDO)
            }
            val final = cola.leer()
            publicar(actual, final, if (final.eventos.any { it.hogarId == actual.hogar }) FaseHistorialHardware.PENDIENTE else FaseHistorialHardware.SINCRONIZADO)
            ResultadoDestinoHardware.EXITO
        } finally {
            synchronized(monitor) { if (contexto == actual) trabajo = null }
            vaciado.unlock()
        }
    }
}
