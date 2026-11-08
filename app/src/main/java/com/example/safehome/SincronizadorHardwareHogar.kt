package com.example.safehome

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Primero persiste eventos; la configuración actual se proyecta por separado y en orden.
 * Nunca envía órdenes Bluetooth ni reproduce configuración desde el historial pendiente.
 * No se escriben comandos/actual ni estado/actual (su esquema no representa UNKNOWN ni ALARM ACK).
 */
class SincronizadorHardwareHogar(
    private val firestore: FirebaseFirestore,
    private val historial: GestorHistorialHardware,
    private val scope: CoroutineScope
) {
    private val configuraciones = Mutex()

    suspend fun registrar(hogarId: String, cambio: CambioHardware, vigente: () -> Boolean,
        informarError: (String) -> Unit): String? {
        if (!vigente()) return null
        // Se captura al consumir la transición Android, antes de IO local o remoto.
        val ocurridoEn = System.currentTimeMillis()
        if (cambio.evento != null && !historial.registrar(hogarId, cambio.evento, ocurridoEn))
            return "No se pudo guardar un evento del hardware en el historial local."
        // La red nunca retiene el consumidor Bluetooth ni la persistencia de otra transición.
        cambio.sistemaActivo?.let { activo ->
            scope.launch {
                configuraciones.withLock {
                    if (vigente()) sincronizarConfiguracion(hogarId, activo, vigente)?.let { if (vigente()) informarError(it) }
                }
            }
        }
        return null
    }

    private suspend fun sincronizarConfiguracion(hogarId: String, activo: Boolean, vigente: () -> Boolean): String? {
        return suspendCancellableCoroutine { continuacion ->
            val hogar = firestore.collection("hogares").document(hogarId)
            val configuracion = hogar.collection("configuracion").document("actual")
            try {
                firestore.runTransaction<Unit> { tx ->
                    if (!vigente()) throw FirebaseFirestoreException("Contexto finalizado", FirebaseFirestoreException.Code.ABORTED)
                    run {
                        val datos = tx.get(configuracion).data ?: emptyMap()
                        val actual = (validarConfiguracionHogar(datos) as? ResultadoConfiguracionHogar.Valida)?.configuracion
                            ?: throw FirebaseFirestoreException("Configuración no disponible", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                        if (!vigente()) throw FirebaseFirestoreException("Contexto finalizado", FirebaseFirestoreException.Code.ABORTED)
                        if (transicionSistemaEsValida(actual, activo)) tx.update(configuracion,
                            mapOf("sistemaActivo" to activo, "actualizadoEn" to FieldValue.serverTimestamp()))
                    }
                    Unit
                }.addOnCompleteListener { tarea ->
                    val error = when {
                        tarea.isSuccessful -> null
                        (tarea.exception as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                            "Firebase no autorizó sincronizar la configuración del hardware."
                        else -> "No se pudo sincronizar la configuración del hardware."
                    }
                    if (continuacion.isActive) continuacion.resume(error)
                }
            } catch (_: Exception) {
                if (continuacion.isActive) continuacion.resume("No se pudo sincronizar la actividad del hardware.")
            }
        }
    }
}
