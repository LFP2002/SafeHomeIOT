package com.example.safehome

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Date
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

fun datosEventoPendiente(evento: EventoHardwarePendiente, sincronizadoEn: Any): Map<String, Any> =
    datosEventoFisico(evento.tipo, Timestamp(Date(evento.ocurridoEnMillis))) + mapOf(
        "ocurridoEnMillis" to evento.ocurridoEnMillis, "sincronizadoEn" to sincronizadoEn)

fun documentoCorrespondeAlEvento(datos: Map<String, Any?>, evento: EventoHardwarePendiente): Boolean =
    datos["tipo"] == evento.tipo.name && datos["ocurridoEnMillis"] == evento.ocurridoEnMillis &&
        datos["creadoEn"] == Timestamp(Date(evento.ocurridoEnMillis)) &&
        datos["sincronizadoEn"] is Timestamp &&
        (evento.tipo !in setOf(EventoFisico.SISTEMA_ACTIVADO, EventoFisico.SISTEMA_DESACTIVADO) ||
            datos["sistemaActivo"] == (evento.tipo == EventoFisico.SISTEMA_ACTIVADO))

class DestinoFirestoreHistorialHardware(private val firestore: FirebaseFirestore) : DestinoHistorialHardware {
    override suspend fun guardar(evento: EventoHardwarePendiente, vigente: () -> Boolean): ResultadoDestinoHardware =
        suspendCancellableCoroutine { continuacion ->
            val documento = firestore.collection("hogares").document(evento.hogarId)
                .collection("historial").document(evento.idEvento)
            try {
                firestore.runTransaction<Unit> { tx ->
                    if (!vigente()) throw FirebaseFirestoreException("Sesión finalizada", FirebaseFirestoreException.Code.ABORTED)
                    val existente = tx.get(documento)
                    if (!vigente()) throw FirebaseFirestoreException("Sesión finalizada", FirebaseFirestoreException.Code.ABORTED)
                    if (existente.exists()) {
                        if (!documentoCorrespondeAlEvento(existente.data.orEmpty(), evento))
                            throw FirebaseFirestoreException("Evento incompatible", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                        // Ya confirmado: no reescribir sincronizadoEn ni documentos históricos.
                    } else tx.set(documento, datosEventoPendiente(evento, FieldValue.serverTimestamp()))
                    Unit
                }.addOnCompleteListener { tarea ->
                    val resultado = if (tarea.isSuccessful) ResultadoDestinoHardware.EXITO else when (
                        (tarea.exception as? FirebaseFirestoreException)?.code) {
                        FirebaseFirestoreException.Code.PERMISSION_DENIED,
                        FirebaseFirestoreException.Code.UNAUTHENTICATED -> ResultadoDestinoHardware.NO_AUTORIZADO
                        FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                        FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                        FirebaseFirestoreException.Code.NOT_FOUND -> ResultadoDestinoHardware.INVALIDO
                        else -> ResultadoDestinoHardware.TRANSITORIO
                    }
                    if (continuacion.isActive) continuacion.resume(resultado)
                }
            } catch (_: Exception) {
                if (continuacion.isActive) continuacion.resume(ResultadoDestinoHardware.TRANSITORIO)
            }
        }
}
