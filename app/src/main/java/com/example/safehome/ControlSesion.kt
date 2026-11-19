package com.example.safehome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.safehome.autenticacion.RepositorioFirebaseCuentas
import com.example.safehome.autenticacion.SesionEstacion
import com.example.safehome.iot.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

/** Composición de dependencias. Auth y supervisión tienen sus propios coordinadores. */
class ControlSesion(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val panelMutable = MutableStateFlow(PanelSupervision())
    val panel = panelMutable.asStateFlow()
    private val firestore = FirebaseFirestore.getInstance()
    private val base = HistorialLocal.abrir(application)
    private val hogares = application.getSharedPreferences("historial-cuentas", android.content.Context.MODE_PRIVATE)
    private val supervisor = SupervisionMqtt(application, viewModelScope, base.eventos()) { panelMutable.value = it }
    private val acceso = SesionEstacion(RepositorioFirebaseCuentas(FirebaseAuth.getInstance(), firestore)) {
        if (it != null) hogares.edit().putString(it.uid, it.hogar).apply()
        supervisor.sesion(it)
    }
    val estado = acceso.estado
    init { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { supervisor.primerPlano(true) }
    override fun onStop(owner: LifecycleOwner) { supervisor.primerPlano(false) }
    fun enviarComando(activar: Boolean) = supervisor.enviar(activar)
    fun reconocerAlarma() = supervisor.reconocer()
    /** Solo historial propio: no autoriza MQTT ni usa un perfil de caché para conectarse. */
    fun historialOffline(uid: String): Flow<Lectura<HistorialSupervision>> {
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return flowOf(Lectura.SinDatos)
        val hogar = hogares.getString(uid, null) ?: return flowOf(Lectura.SinDatos)
        return base.eventos().observar(uid, hogar).map<List<EventoLocal>, Lectura<HistorialSupervision>> { filas ->
            Lectura.Dato(HistorialSupervision(filas.map {
                EventoSupervision(it.id, it.tipo, "Estación · Origen: ${it.origen}", Instante(it.ocurridoEn),
                    it.tipo.lowercase().replace('_', ' '))
            }, 0, false))
        }.catch { emit(Lectura.Error("No se pudo leer el historial local.")) }
    }
    fun iniciarSesion(correo: String, clave: String) = acceso.iniciar(correo, clave)
    fun registrarEstacion(nombre: String, correo: String, clave: String, confirmacion: String) = acceso.registrar(nombre, correo, clave, confirmacion)
    fun recuperarContrasena(correo: String) = acceso.recuperar(correo)
    fun reintentarPerfil() = acceso.reintentar()
    fun cerrarSesion() = acceso.cerrarSesion()
    fun reintentarSupervision() {
        supervisor.reintentar()
    }
    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        acceso.cerrar(); supervisor.cerrar()
        super.onCleared()
    }
}
