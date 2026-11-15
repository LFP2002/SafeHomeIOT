package com.example.safehome.iot

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query

internal fun adaptarTiempos(datos: Map<String, Any?>): Map<String, Any?> = datos.mapValues { (_, valor) ->
    if (valor is Timestamp) runCatching<Any> { Instante(valor.toDate().time) }.getOrElse { valor } else valor
}

internal fun mensajeErrorFirestore(error: Exception): String =
    mensajeErrorConsulta((error as? FirebaseFirestoreException)?.code?.name)

class RepositorioFirestoreSupervision(private val firestore: FirebaseFirestore) : RepositorioSupervision {
    private fun hogar(id: String) = firestore.collection("hogares").document(id)

    private fun <T : Any> observar(documento: DocumentReference, parser: (Map<String, Any?>) -> T?,
        recibir: (Lectura<T>) -> Unit): Suscripcion {
        recibir(Lectura.Cargando)
        return try {
            val listener = documento.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                recibir(when {
                    error != null -> Lectura.Error(mensajeErrorFirestore(error))
                    snapshot == null -> Lectura.Error("No se recibió respuesta de Firestore.")
                    !snapshot.exists() -> Lectura.SinDatos
                    else -> parser(adaptarTiempos(snapshot.data.orEmpty()))?.let {
                        Lectura.Dato(it, snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites())
                    }
                        ?: Lectura.Invalida
                })
            }
            Suscripcion { listener.remove() }
        } catch (error: Exception) { recibir(Lectura.Error(mensajeErrorFirestore(error))); Suscripcion {} }
    }

    override fun observarDispositivo(hogar: String, dispositivo: Dispositivo, recibir: (Lectura<EstadoDispositivo>) -> Unit) =
        observar(hogar(hogar).collection("dispositivos").document(dispositivo.name.lowercase()), ::parsearDispositivo, recibir)

    override fun observarEstadoFisico(hogar: String, recibir: (Lectura<EstadoFisico>) -> Unit) =
        observar(hogar(hogar).collection("estadoFisico").document("actual"), ::parsearEstadoFisico, recibir)

    override fun observarHistorial(hogar: String, recibir: (Lectura<HistorialSupervision>) -> Unit): Suscripcion {
        recibir(Lectura.Cargando)
        return try {
            val listener = hogar(hogar).collection("historial").orderBy("creadoEn", Query.Direction.DESCENDING)
                .limit(100).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    recibir(when {
                        error != null -> Lectura.Error(mensajeErrorFirestore(error))
                        snapshot == null -> Lectura.Error("No se recibió el historial de Firestore.")
                        else -> Lectura.Dato(prepararHistorial(snapshot.documents.map { it.id to adaptarTiempos(it.data.orEmpty()) }),
                            snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites())
                    })
                }
            Suscripcion { listener.remove() }
        } catch (error: Exception) { recibir(Lectura.Error(mensajeErrorFirestore(error))); Suscripcion {} }
    }
}
