package com.example.safehome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AlmacenEventosHardware {
    fun leer(): Map<String, *>
    /** Debe confirmar persistencia, no solo actualizar una caché en memoria. */
    fun escribir(clave: String, valor: String?): Boolean
}

enum class ErrorColaHardware { CORRUPTA, ESCRITURA, SATURADA }
data class LecturaColaHardware(val eventos: List<EventoHardwarePendiente>, val error: ErrorColaHardware? = null)

class ColaEventosHardwareLocal(private val almacen: AlmacenEventosHardware, private val limite: Int = 1000,
    private val alSaturar: () -> Unit = {}) {
    private val mutex = Mutex()
    private var fallo: ErrorColaHardware? = null

    private fun leerInterno(): LecturaColaHardware {
        val registros = almacen.leer().filterKeys { it.startsWith("evento.") }
        val eventos = registros.mapNotNull { (clave, valor) ->
            CodecEventoHardware.leer(valor)?.takeIf { clave == "evento.${it.idEvento}" }
        }
        return LecturaColaHardware(eventos.sortedWith(compareBy({ it.ocurridoEnMillis }, { it.idEvento })),
            fallo ?: if (registros.size != eventos.size) ErrorColaHardware.CORRUPTA else null)
    }

    suspend fun leer(): LecturaColaHardware = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { leerInterno() } catch (_: Exception) { LecturaColaHardware(emptyList(), ErrorColaHardware.CORRUPTA) }
        }
    }

    suspend fun agregar(evento: EventoHardwarePendiente): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            try {
                val previo = leerInterno().eventos.find { it.idEvento == evento.idEvento }
                if (previo != null && previo.copy(intentos = 0, ultimoIntentoMillis = null) != evento.copy(intentos = 0, ultimoIntentoMillis = null)) {
                    fallo = ErrorColaHardware.CORRUPTA
                    return@withLock false
                }
                if (previo == null && almacen.leer().keys.count { it.startsWith("evento.") } >= limite) {
                    if (fallo != ErrorColaHardware.SATURADA) alSaturar()
                    fallo = ErrorColaHardware.SATURADA
                    return@withLock false
                }
                escribir(evento.idEvento, CodecEventoHardware.serializar(previo ?: evento))
            } catch (_: Exception) { fallo = ErrorColaHardware.ESCRITURA; false }
        }
    }

    suspend fun marcarIntento(evento: EventoHardwarePendiente, ahora: Long): EventoHardwarePendiente? = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            try {
                val actual = leerInterno().eventos.find { it.idEvento == evento.idEvento && it.hogarId == evento.hogarId }
                    ?: return@withLock null
                val siguiente = actual.copy(intentos = if (actual.intentos == Int.MAX_VALUE) Int.MAX_VALUE else actual.intentos + 1,
                    ultimoIntentoMillis = ahora)
                if (escribir(siguiente.idEvento, CodecEventoHardware.serializar(siguiente))) siguiente else null
            } catch (_: Exception) { fallo = ErrorColaHardware.ESCRITURA; null }
        }
    }

    suspend fun eliminar(evento: EventoHardwarePendiente): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            try { escribir(evento.idEvento, null) } catch (_: Exception) { fallo = ErrorColaHardware.ESCRITURA; false }
        }
    }

    private fun escribir(id: String, valor: String?): Boolean {
        val guardado = almacen.escribir("evento.$id", valor)
        if (!guardado) fallo = ErrorColaHardware.ESCRITURA
        // Saturación permanece visible: pudo rechazarse un evento; nunca se oculta esa pérdida.
        else if (fallo == ErrorColaHardware.ESCRITURA) fallo = null
        return guardado
    }
}
