package com.example.safehome.autenticacion

import com.example.safehome.ResultadoPerfil
import com.example.safehome.validarPerfilUsuario
import com.example.safehome.iot.Lectura
import com.example.safehome.iot.Suscripcion
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.FirebaseFirestoreException

class RepositorioFirebaseCuentas(private val auth: FirebaseAuth, private val firestore: FirebaseFirestore) : RepositorioCuentas {
    override val uidActual: String? get() = auth.currentUser?.uid

    override fun observarAuth(recibir: (String?) -> Unit): Suscripcion {
        val listener = FirebaseAuth.AuthStateListener { recibir(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        return Suscripcion { auth.removeAuthStateListener(listener) }
    }

    override fun observarPerfil(uid: String, recibir: (Lectura<ResultadoPerfil>) -> Unit): Suscripcion {
        recibir(Lectura.Cargando)
        return try {
            val listener = firestore.collection("usuarios").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
                recibir(when {
                    error != null -> Lectura.Error("No se pudo verificar el perfil en Firebase. Revisa conexión y permisos.")
                    documento == null -> Lectura.Error("No se recibió el perfil de Firebase.")
                    documento.metadata.isFromCache || documento.metadata.hasPendingWrites() -> Lectura.Dato(ResultadoPerfil.Invalido, desdeCache = true)
                    !documento.exists() -> Lectura.SinDatos
                    else -> Lectura.Dato(validarPerfilUsuario(documento.data.orEmpty()))
                })
            }
            Suscripcion { listener.remove() }
        } catch (_: Exception) { recibir(Lectura.Error("No se pudo observar el perfil.")); Suscripcion {} }
    }

    override fun iniciar(correo: String, clave: String, terminar: (String?) -> Unit) {
        try { auth.signInWithEmailAndPassword(correo, clave).addOnCompleteListener {
            terminar(if (it.isSuccessful) null else "No se pudo iniciar sesión. Revisa tus credenciales y conexión.")
        } } catch (_: Exception) { terminar("No se pudo iniciar sesión.") }
    }

    override fun crear(correo: String, clave: String, terminar: (String?, String?) -> Unit) {
        try { auth.createUserWithEmailAndPassword(correo, clave).addOnCompleteListener {
            terminar(if (it.isSuccessful) it.result?.user?.uid else null,
                if (it.isSuccessful) null else "No se pudo registrar la cuenta. Revisa los datos y la conexión.")
        } } catch (_: Exception) { terminar(null, "No se pudo registrar la cuenta.") }
    }

    override fun prepararRegistro(uid: String, nombre: String, terminar: (String?) -> Unit) {
        if (uidActual != uid) { terminar("La sesión de registro cambió."); return }
        val usuario = firestore.collection("usuarios").document(uid)
        val hogar = firestore.collection("hogares").document(uid)
        try {
            firestore.runTransaction<Unit> { tx ->
                val perfil = tx.get(usuario)
                val casa = tx.get(hogar)
                if (perfil.exists() || casa.exists()) {
                    // Solo acepta la repetición exacta de una inicialización ya confirmada.
                    if (perfil.data != mapOf("activo" to true, "rol" to "estacion", "hogarId" to uid, "nombre" to nombre) ||
                        casa.getString("nombre") != "Hogar de $nombre" || casa.getTimestamp("creadoEn") == null)
                        throw FirebaseFirestoreException("Registro incompatible", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                } else {
                    tx.set(usuario, mapOf("activo" to true, "rol" to "estacion", "hogarId" to uid, "nombre" to nombre))
                    tx.set(hogar, mapOf("nombre" to "Hogar de $nombre", "creadoEn" to FieldValue.serverTimestamp()))
                }
                Unit
            }.addOnCompleteListener { terminar(if (it.isSuccessful) null else "No se pudo preparar el perfil/hogar en Firestore.") }
        } catch (_: Exception) { terminar("No se pudo preparar el perfil/hogar.") }
    }

    override fun recuperar(correo: String, terminar: (String?) -> Unit) {
        try { auth.sendPasswordResetEmail(correo).addOnCompleteListener {
            terminar(if (it.isSuccessful) null else "No se pudo solicitar la recuperación. Revisa tu conexión e inténtalo más tarde.")
        } } catch (_: Exception) { terminar("No se pudo solicitar la recuperación.") }
    }

    override fun cerrarSesion() = auth.signOut()
}
