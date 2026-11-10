package com.example.safehome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.safehome.autenticacion.RepositorioFirebaseCuentas
import com.example.safehome.autenticacion.SesionEstacion
import com.example.safehome.iot.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Composición de dependencias. Auth y supervisión tienen sus propios coordinadores. */
class ControlSesion(application: Application) : AndroidViewModel(application) {
    private val panelMutable = MutableStateFlow(PanelSupervision())
    val panel = panelMutable.asStateFlow()
    private val firestore = FirebaseFirestore.getInstance()
    private val supervisor = CoordinadorSupervision(RepositorioFirestoreSupervision(firestore)) { panelMutable.value = it }
    private val acceso = SesionEstacion(RepositorioFirebaseCuentas(FirebaseAuth.getInstance(), firestore)) {
        if (it == null) supervisor.cerrar() else supervisor.iniciar(it)
    }
    val estado = acceso.estado
    fun iniciarSesion(correo: String, clave: String) = acceso.iniciar(correo, clave)
    fun registrarEstacion(nombre: String, correo: String, clave: String, confirmacion: String) = acceso.registrar(nombre, correo, clave, confirmacion)
    fun recuperarContrasena(correo: String) = acceso.recuperar(correo)
    fun reintentarPerfil() = acceso.reintentar()
    fun cerrarSesion() = acceso.cerrarSesion()
    fun reintentarSupervision() {
        val actual = estado.value
        actual.perfil?.let { perfil -> actual.uid?.let { supervisor.iniciar(SesionSupervision(it, perfil.hogarId)) } }
    }
    override fun onCleared() { acceso.cerrar(); supervisor.cerrar(); super.onCleared() }
}
