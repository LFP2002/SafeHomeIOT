package com.example.safehome

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAXIMO_CORREO = 254
private const val ESPERA_MAXIMA_MS = 20_000L
private val formatoCorreo = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun debeRecargarPerfilTrasRespuestaVinculacion(estado: String?): Boolean =
    estado in setOf("APROBADA", "RECHAZADA")

data class ErroresFormulario(val correo: String? = null, val clave: String? = null) {
    val esValido: Boolean get() = correo == null && clave == null
}

fun normalizarCorreo(correo: String): String = correo.trim {
    it.isWhitespace() && !Character.isISOControl(it)
}

fun validarFormularioInicio(correo: String, clave: String): ErroresFormulario {
    val correoNormalizado = normalizarCorreo(correo)
    val errorCorreo = when {
        correoNormalizado.isEmpty() -> "Ingresa tu correo electrónico."
        correoNormalizado.length > MAXIMO_CORREO -> "El correo es demasiado largo."
        correoNormalizado.any { it.isWhitespace() || Character.isISOControl(it) } ->
            "El correo no puede contener espacios ni controles."
        !formatoCorreo.matches(correoNormalizado) ->
            "Ingresa un correo válido, por ejemplo nombre@dominio.cl."
        else -> null
    }
    val errorClave = when {
        clave.isEmpty() -> "Ingresa tu contraseña."
        else -> null
    }
    return ErroresFormulario(errorCorreo, errorClave)
}

fun validarCorreoRecuperacion(correo: String): String? = validarFormularioInicio(correo, "x").correo

sealed interface EstadoSolicitudInicio {
    data object Inactiva : EstadoSolicitudInicio
    data object EnCurso : EstadoSolicitudInicio
    data object EsperaAgotada : EstadoSolicitudInicio
}

sealed interface EstadoPerfilUi {
    data object SinSesion : EstadoPerfilUi
    data object Comprobando : EstadoPerfilUi
    data class Valido(val perfil: PerfilUsuario) : EstadoPerfilUi
    data class EliminacionPendiente(val perfil: PerfilUsuario) : EstadoPerfilUi
    data class Error(val mensaje: String, val puedeReintentar: Boolean) : EstadoPerfilUi
}

sealed interface EstadoHogarUi {
    data object Cargando : EstadoHogarUi
    data object EsperandoConfirmacionServidor : EstadoHogarUi
    data class Valido(val estado: EstadoHogar) : EstadoHogarUi
    data class Error(val mensaje: String, val puedeReintentar: Boolean) : EstadoHogarUi
}

sealed interface EstadoConfiguracionHogarUi {
    data object Cargando : EstadoConfiguracionHogarUi
    data object EsperandoConfirmacionServidor : EstadoConfiguracionHogarUi
    data class Valida(val configuracion: ConfiguracionHogar) : EstadoConfiguracionHogarUi
    data class Error(val mensaje: String, val puedeReintentar: Boolean) : EstadoConfiguracionHogarUi
}

sealed interface EstadoRecuperacionUi {
    data object Inactiva : EstadoRecuperacionUi
    data object EnCurso : EstadoRecuperacionUi
    data class Mensaje(val texto: String, val esError: Boolean) : EstadoRecuperacionUi
}

private const val MENSAJE_RECUPERACION_EXITOSA = "Si existe una cuenta compatible, recibirás instrucciones para restablecer tu contraseña."
private const val MENSAJE_RECUPERACION_TIMEOUT = "La solicitud tardó demasiado. Revisa tu conexión e inténtalo nuevamente."

fun estadoRecuperacionCorreoInvalido(error: String): EstadoRecuperacionUi.Mensaje =
    EstadoRecuperacionUi.Mensaje(error, true)

fun estadoRecuperacionAlIniciar(): EstadoRecuperacionUi = EstadoRecuperacionUi.EnCurso

fun estadoRecuperacionFinalizada(fueExitosa: Boolean, mensajeError: String): EstadoRecuperacionUi.Mensaje =
    if (fueExitosa) EstadoRecuperacionUi.Mensaje(MENSAJE_RECUPERACION_EXITOSA, false)
    else EstadoRecuperacionUi.Mensaje(mensajeError, true)

fun estadoRecuperacionPorTimeout(): EstadoRecuperacionUi.Mensaje =
    EstadoRecuperacionUi.Mensaje(MENSAJE_RECUPERACION_TIMEOUT, true)

fun callbackRecuperacionEsVigente(idSolicitud: Long, idActivo: Long?): Boolean =
    idSolicitud == idActivo

fun recuperacionPuedeIniciarse(idActivo: Long?): Boolean = idActivo == null

sealed interface EstadoRegistroEstacionUi {
    data object Inactivo : EstadoRegistroEstacionUi
    data object EnCurso : EstadoRegistroEstacionUi
    data object EsperaAgotada : EstadoRegistroEstacionUi
    data class Error(val mensaje: String) : EstadoRegistroEstacionUi
}

sealed interface EstadoRegistroMovilUi {
    data object Inactivo : EstadoRegistroMovilUi
    data object EnCurso : EstadoRegistroMovilUi
    data object EsperaAgotada : EstadoRegistroMovilUi
    data class Error(val mensaje: String) : EstadoRegistroMovilUi
}

sealed interface EstadoVinculacionPendienteUi {
    data object Inactivo : EstadoVinculacionPendienteUi
    data object EsperandoAprobacion : EstadoVinculacionPendienteUi
    data object Rechazada : EstadoVinculacionPendienteUi
    data class Error(val mensaje: String) : EstadoVinculacionPendienteUi
}

sealed interface EstadoMovilesUi {
    data object Inactivo : EstadoMovilesUi
    data object Cargando : EstadoMovilesUi
    data class Valido(val pendientes: List<SolicitudVinculacion>, val vinculados: List<SolicitudVinculacion>) : EstadoMovilesUi
    data class Error(val mensaje: String) : EstadoMovilesUi
}

sealed interface EstadoListaMovilesUi {
    data object Inactivo : EstadoListaMovilesUi
    data object Cargando : EstadoListaMovilesUi
    data class Valido(val moviles: List<SolicitudVinculacion>) : EstadoListaMovilesUi
    data class Error(val mensaje: String) : EstadoListaMovilesUi
}

sealed interface EstadoSolicitudesUi {
    data object Inactivo : EstadoSolicitudesUi
    data object Cargando : EstadoSolicitudesUi
    data class Valido(val solicitudes: List<SolicitudVinculacion>) : EstadoSolicitudesUi
    data class Error(val mensaje: String) : EstadoSolicitudesUi
}

sealed interface EstadoOperacionMovilUi {
    data object Inactiva : EstadoOperacionMovilUi
    data object EnCurso : EstadoOperacionMovilUi
    data class Error(val mensaje: String) : EstadoOperacionMovilUi
}

sealed interface EstadoEliminacionCuentaUi {
    data object Inactiva : EstadoEliminacionCuentaUi
    data object EnCurso : EstadoEliminacionCuentaUi
    data object Exitosa : EstadoEliminacionCuentaUi
    data class Error(val mensaje: String) : EstadoEliminacionCuentaUi
}

sealed interface EstadoEscrituraUi {
    data object Inactiva : EstadoEscrituraUi
    data object Aplicando : EstadoEscrituraUi
    data object EsperaAgotada : EstadoEscrituraUi
    data class Error(val mensaje: String) : EstadoEscrituraUi
}

sealed interface EstadoComandoUi {
    data object Cargando : EstadoComandoUi
    data object EsperandoConfirmacionServidor : EstadoComandoUi
    data class Valido(val comando: ComandoReconocimiento) : EstadoComandoUi
    data class Error(val mensaje: String, val puedeReintentar: Boolean) : EstadoComandoUi
}

sealed interface EstadoEnvioComandoUi {
    data object Inactivo : EstadoEnvioComandoUi
    data object Enviando : EstadoEnvioComandoUi
    data object EsperaAgotada : EstadoEnvioComandoUi
    data object Confirmado : EstadoEnvioComandoUi
    data class Error(val mensaje: String) : EstadoEnvioComandoUi
}

sealed interface EstadoHistorialUi {
    data class Cargando(
        val pagina: Int,
        val eventosVisibles: List<EventoHistorial> = emptyList(),
        val haySiguienteVisible: Boolean = false
    ) : EstadoHistorialUi
    data class Valido(val eventos: List<EventoHistorial>, val pagina: Int, val haySiguiente: Boolean) : EstadoHistorialUi
    data class Error(val mensaje: String, val puedeReintentar: Boolean, val eventosVisibles: List<EventoHistorial> = emptyList(), val pagina: Int = 0, val haySiguienteVisible: Boolean = false) : EstadoHistorialUi
}

val EstadoEnvioComandoUi.estaPendiente: Boolean
    get() = this is EstadoEnvioComandoUi.Enviando || this is EstadoEnvioComandoUi.EsperaAgotada

val EstadoEscrituraUi.estaPendiente: Boolean
    get() = this is EstadoEscrituraUi.Aplicando || this is EstadoEscrituraUi.EsperaAgotada

data class EstadoSesionUi(
    val haySesion: Boolean = false,
    val solicitud: EstadoSolicitudInicio = EstadoSolicitudInicio.Inactiva,
    val mensaje: String? = null,
    val perfil: EstadoPerfilUi = EstadoPerfilUi.SinSesion,
    val lecturaPerfilPendiente: Boolean = false,
    val hogar: EstadoHogarUi? = null,
    val configuracion: EstadoConfiguracionHogarUi? = null,
    val escritura: EstadoEscrituraUi = EstadoEscrituraUi.Inactiva,
    val comando: EstadoComandoUi? = null,
    val envioComando: EstadoEnvioComandoUi = EstadoEnvioComandoUi.Inactivo,
    val solicitarPermisoNotificaciones: Boolean = false,
    val historial: EstadoHistorialUi? = null,
    val correoCuenta: String? = null,
    val recuperacion: EstadoRecuperacionUi = EstadoRecuperacionUi.Inactiva,
    val registroEstacion: EstadoRegistroEstacionUi = EstadoRegistroEstacionUi.Inactivo,
    val registroMovil: EstadoRegistroMovilUi = EstadoRegistroMovilUi.Inactivo,
    val vinculacionPendiente: EstadoVinculacionPendienteUi = EstadoVinculacionPendienteUi.Inactivo,
    val moviles: EstadoMovilesUi = EstadoMovilesUi.Inactivo,
    val listaMoviles: EstadoListaMovilesUi = EstadoListaMovilesUi.Inactivo,
    val solicitudes: EstadoSolicitudesUi = EstadoSolicitudesUi.Inactivo,
    val presenciasMoviles: Map<String, EstadoPresenciaMovilUi> = emptyMap(),
    val presenciaPropia: EstadoPresenciaPropiaUi = EstadoPresenciaPropiaUi.SinConexion,
    val desfaseHoraServidor: Long = 0L,
    val marcaRelojPresencia: Long = 0L,
    val presenciasUi: List<PresenciaMovilUi> = emptyList(),
    val codigoVinculacion: String? = null,
    val nombreHogar: String = "Mi hogar",
    val operacionMovil: EstadoOperacionMovilUi = EstadoOperacionMovilUi.Inactiva,
    val eliminacionCuenta: EstadoEliminacionCuentaUi = EstadoEliminacionCuentaUi.Inactiva
)

fun estadoTrasCuentaEliminada(): EstadoSesionUi = EstadoSesionUi(eliminacionCuenta = EstadoEliminacionCuentaUi.Exitosa)
fun estadoTrasDescartarCuentaEliminada(): EstadoSesionUi = EstadoSesionUi()
fun estadoTrasErrorEliminacion(mensaje: String): EstadoSesionUi = EstadoSesionUi(
    operacionMovil = EstadoOperacionMovilUi.Error(mensaje),
    eliminacionCuenta = EstadoEliminacionCuentaUi.Error(mensaje)
)

class ControlSesion(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val realtimeDatabase = FirebaseDatabase.getInstance()
    private data class SolicitudActiva(val id: Long, val timeout: Job)
    private data class LecturaPerfilActiva(
        val id: Long,
        val uid: String,
        val sesion: Long,
        val timeout: Job,
        val expirada: Boolean = false
    )
    private data class ListenerEstadoActivo(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        var registro: ListenerRegistration? = null,
        var recibioServidor: Boolean = false
    )
    private data class ListenerConfiguracionActivo(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        var registro: ListenerRegistration? = null,
        var recibioServidor: Boolean = false
    )
    private data class EscrituraActiva(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        val objetivo: ObjetivoEscritura,
        val timeout: Job,
        val expirada: Boolean = false,
        val transaccionTerminada: Boolean = false
    )
    private data class SolicitudRecuperacionActiva(val id: Long, val timeout: Job)
    private data class RegistroEstacionActivo(
        val id: Long,
        val timeout: Job,
        val expirada: Boolean = false
    )
    private data class RegistroMovilActivo(val id: Long, val timeout: Job, val expirada: Boolean = false)
    private data class LecturaCodigoVinculacionActiva(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long
    )
    private data class ListenerComandoActivo(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        var registro: ListenerRegistration? = null,
        var recibioServidor: Boolean = false
    )
    private data class EnvioComandoActivo(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        val secuenciaEsperada: Long?,
        val timeout: Job,
        val expirada: Boolean = false
    )
    private data class LecturaHistorialActiva(
        val id: Long,
        val uid: String,
        val hogarId: String,
        val sesion: Long,
        val generacion: Long,
        val pagina: Int
    )
    private data class ConfirmacionAprobacion(
        val perfil: Map<String, Any?>,
        val solicitud: Map<String, Any?>,
        val movil: Map<String, Any?>
    )
    private data class PublicacionPresenciaActiva(val id: Long, val uid: String, val hogarId: String, val sesion: Long, val generacion: Long)
    private data class ListenerPresenciaEstacion(val uidMovil: String, val referencia: DatabaseReference, val listener: ValueEventListener)
    private data class LecturaMovilesActiva(val id: Long, val uid: String, val hogarId: String, val sesion: Long, val timeout: Job, val expirada: Boolean = false)

    private val _estado = MutableStateFlow(EstadoSesionUi(haySesion = auth.currentUser != null))
    val estado: StateFlow<EstadoSesionUi> = _estado.asStateFlow()
    private var siguienteId = 0L
    private var sesionActual = 0L
    private var solicitudActiva: SolicitudActiva? = null
    private var lecturaPerfilActiva: LecturaPerfilActiva? = null
    private var listenerEstadoActivo: ListenerEstadoActivo? = null
    private var listenerConfiguracionActivo: ListenerConfiguracionActivo? = null
    private var escrituraActiva: EscrituraActiva? = null
    private var listenerComandoActivo: ListenerComandoActivo? = null
    private var envioComandoActivo: EnvioComandoActivo? = null
    private var lecturaHistorialActiva: LecturaHistorialActiva? = null
    private var generacionHistorial = 0L
    private var paginaHistorialActual = 0
    private val cursoresHistorial = mutableListOf<com.google.firebase.firestore.DocumentSnapshot?>(null)
    private val notificadorAlertas = NotificadorAlertasHogar(application)
    private val notificadorAlertasConexion = NotificadorAlertasConexion(application)
    private var solicitudPermisoMostrada = false
    private var solicitudRecuperacionActiva: SolicitudRecuperacionActiva? = null
    private var registroEstacionActivo: RegistroEstacionActivo? = null
    private var registroMovilActivo: RegistroMovilActivo? = null
    private var aprobacionMovilActiva: Long? = null
    private var lecturaCodigoVinculacionActiva: LecturaCodigoVinculacionActiva? = null
    private var listenerConexionMovil: ValueEventListener? = null
    private var listenerDesfaseServidor: ValueEventListener? = null
    private var tickerPresenciaEstacion: Job? = null
    private val ultimoVisualPresencia = mutableMapOf<String, EstadoVisualPresencia>()
    private var referenciaConexionMovil: DatabaseReference? = null
    private var publicacionPresenciaActiva: PublicacionPresenciaActiva? = null
    private var generacionPresencia = 0L
    private val listenersPresenciaEstacion = mutableMapOf<String, ListenerPresenciaEstacion>()
    private var lecturaMovilesActiva: LecturaMovilesActiva? = null
    private var desvinculacionMovilActiva: Long? = null
    private var listenerPerfilMovil: ListenerRegistration? = null
    private data class OperacionCuentaActiva(val id: Long, val uid: String, val sesion: Long, val timeout: Job, val expirada: Boolean = false)
    private var operacionCuentaActiva: OperacionCuentaActiva? = null

    init {
        auth.currentUser?.uid?.let { iniciarConsultaPerfil(it) }
    }

    fun iniciarSesion(correo: String, clave: String): ErroresFormulario {
        val errores = validarFormularioInicio(correo, clave)
        if (!errores.esValido || solicitudActiva != null || registroEstacionActivo != null || lecturaPerfilActiva != null) return errores

        val id = ++siguienteId
        _estado.value = EstadoSesionUi(false, EstadoSolicitudInicio.EnCurso)
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            if (solicitudActiva?.id == id) {
                _estado.value = EstadoSesionUi(
                    haySesion = false,
                    solicitud = EstadoSolicitudInicio.EsperaAgotada,
                    mensaje = "Se agotó la espera. Puedes volver mientras termina la solicitud."
                )
            }
        }
        solicitudActiva = SolicitudActiva(id, timeout)
        try {
            auth.signInWithEmailAndPassword(normalizarCorreo(correo), clave).addOnCompleteListener { tarea ->
                val uid = if (tarea.isSuccessful) tarea.result.user?.uid else null
                completarSolicitud(id, tarea.isSuccessful, tarea.exception, uid)
            }
        } catch (_: Exception) {
            completarSolicitud(id, false, null, null)
        }
        return errores
    }

    fun registrarEstacion(nombre: String, correo: String, clave: String, confirmacion: String): ErroresRegistroEstacion {
        val nombreNormalizado = normalizarNombreVisible(nombre)
        val errores = validarRegistroEstacion(nombreNormalizado, normalizarCorreo(correo), clave, confirmacion)
        if (!errores.esValido || solicitudActiva != null || registroEstacionActivo != null || lecturaPerfilActiva != null) return errores
        val id = ++siguienteId
        _estado.value = EstadoSesionUi(haySesion = false, registroEstacion = EstadoRegistroEstacionUi.EnCurso)
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activo = registroEstacionActivo
            if (activo?.id == id) {
                registroEstacionActivo = activo.copy(expirada = true)
                _estado.value = _estado.value.copy(registroEstacion = EstadoRegistroEstacionUi.EsperaAgotada)
            }
        }
        registroEstacionActivo = RegistroEstacionActivo(id, timeout)
        try {
            auth.createUserWithEmailAndPassword(normalizarCorreo(correo), clave).addOnCompleteListener { tarea ->
                completarAuthRegistro(id, nombreNormalizado, tarea.isSuccessful, if (tarea.isSuccessful) tarea.result?.user?.uid else null, tarea.exception)
            }
        } catch (_: Exception) {
            completarAuthRegistro(id, nombreNormalizado, false, null, null)
        }
        return errores
    }

    private fun completarAuthRegistro(id: Long, nombre: String, fueExitosa: Boolean, uid: String?, error: Exception?) {
        val activo = registroEstacionActivo ?: return
        if (activo.id != id) return
        if (!fueExitosa || uid == null || auth.currentUser?.uid != uid || activo.expirada) {
            if (fueExitosa && uid != null && auth.currentUser?.uid == uid) compensarRegistroFallido(id, uid)
            else finalizarRegistroFallido(id, error)
            return
        }
        val codigo = generarCodigoVinculacion()
        try {
            val hogar = firestore.collection("hogares").document(uid)
            firestore.batch().apply {
                set(firestore.collection("usuarios").document(uid), mapOf("activo" to true, "rol" to "estacion", "hogarId" to uid, "nombre" to nombre))
                set(hogar, mapOf("nombre" to "Hogar de $nombre", "creadoEn" to FieldValue.serverTimestamp()))
                set(hogar.collection("estado").document("actual"), mapOf("puertaAbierta" to false, "personaPresente" to true, "actualizadoEn" to FieldValue.serverTimestamp()))
                set(hogar.collection("configuracion").document("actual"), mapOf("sistemaActivo" to true, "actualizadoEn" to FieldValue.serverTimestamp()))
                set(hogar.collection("comandos").document("actual"), mapOf("tipo" to "RECONOCER_ALERTA", "secuencia" to 0L, "emitidoEn" to FieldValue.serverTimestamp()))
                set(hogar.collection("vinculacion").document("actual"), mapOf("codigo" to codigo, "activo" to true, "creadoEn" to FieldValue.serverTimestamp(), "actualizadoEn" to FieldValue.serverTimestamp()))
                set(firestore.collection("codigosVinculacion").document(codigo), mapOf("hogarId" to uid, "propietarioUid" to uid, "activo" to true, "creadoEn" to FieldValue.serverTimestamp()))
            }.commit().addOnCompleteListener { tarea -> completarInicializacionRegistro(id, uid, tarea.isSuccessful, tarea.exception) }
        } catch (_: Exception) {
            completarInicializacionRegistro(id, uid, false, null)
        }
    }

    private fun completarInicializacionRegistro(id: Long, uid: String, fueExitosa: Boolean, error: Exception?) {
        val activo = registroEstacionActivo ?: return
        if (activo.id != id || auth.currentUser?.uid != uid) return
        if (!fueExitosa || activo.expirada) {
            compensarRegistroFallido(id, uid, error)
            return
        }
        activo.timeout.cancel()
        registroEstacionActivo = null
        iniciarConsultaPerfil(uid)
    }

    private fun compensarRegistroFallido(id: Long, uid: String, error: Exception? = null) {
        if (auth.currentUser?.uid != uid) {
            finalizarRegistroFallido(id, error)
            return
        }
        try {
            auth.currentUser?.delete()?.addOnCompleteListener { finalizarRegistroFallido(id, error) }
        } catch (_: Exception) {
            finalizarRegistroFallido(id, error)
        }
    }

    private fun finalizarRegistroFallido(id: Long, error: Exception?) {
        val activo = registroEstacionActivo ?: return
        if (activo.id != id) return
        activo.timeout.cancel()
        registroEstacionActivo = null
        auth.signOut()
        _estado.value = EstadoSesionUi(haySesion = false, registroEstacion = EstadoRegistroEstacionUi.Error("No se pudo crear la cuenta y preparar el hogar. Inténtalo más tarde."))
    }

    fun registrarMovil(nombre: String, correo: String, clave: String, confirmacion: String, codigo: String): ErroresRegistroMovil {
        val nombreNormalizado = normalizarNombreVisible(nombre)
        val codigoNormalizado = normalizarCodigoVinculacion(codigo)
        val errores = validarRegistroMovil(nombreNormalizado, normalizarCorreo(correo), clave, confirmacion, codigoNormalizado)
        if (!errores.esValido || registroMovilActivo != null || registroEstacionActivo != null || solicitudActiva != null || lecturaPerfilActiva != null) return errores
        val id = ++siguienteId
        _estado.value = EstadoSesionUi(haySesion = false, registroMovil = EstadoRegistroMovilUi.EnCurso)
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activo = registroMovilActivo
            if (activo?.id == id) { registroMovilActivo = activo.copy(expirada = true); _estado.value = _estado.value.copy(registroMovil = EstadoRegistroMovilUi.EsperaAgotada) }
        }
        registroMovilActivo = RegistroMovilActivo(id, timeout)
        try { auth.createUserWithEmailAndPassword(normalizarCorreo(correo), clave).addOnCompleteListener { tarea -> completarAuthMovil(id, nombreNormalizado, codigoNormalizado, tarea.isSuccessful, if (tarea.isSuccessful) tarea.result?.user?.uid else null, tarea.exception) } }
        catch (_: Exception) { completarAuthMovil(id, nombreNormalizado, codigoNormalizado, false, null, null) }
        return errores
    }

    private fun completarAuthMovil(id: Long, nombre: String, codigo: String, exitosa: Boolean, uid: String?, error: Exception?) {
        val activa = registroMovilActivo ?: return
        if (activa.id != id) return
        if (!exitosa || uid == null || auth.currentUser?.uid != uid || activa.expirada) { finalizarRegistroMovilFallido(id, uid); return }
        try { firestore.collection("codigosVinculacion").document(codigo).get(Source.SERVER).addOnCompleteListener { tarea -> completarCodigoMovil(id, uid, nombre, codigo, tarea.isSuccessful, if (tarea.isSuccessful) tarea.result else null, tarea.exception) } }
        catch (_: Exception) { finalizarRegistroMovilFallido(id, uid) }
    }

    private fun completarCodigoMovil(id: Long, uid: String, nombre: String, codigo: String, exitosa: Boolean, documento: com.google.firebase.firestore.DocumentSnapshot?, error: Exception?) {
        val activa = registroMovilActivo ?: return
        if (activa.id != id || auth.currentUser?.uid != uid || activa.expirada) { finalizarRegistroMovilFallido(id, uid); return }
        val mapa = if (exitosa && documento?.exists() == true) validarCodigoVinculacion(documento.data ?: emptyMap()) as? ResultadoCodigoVinculacion.Valido else null
        if (mapa == null) { finalizarRegistroMovilFallido(id, uid); return }
        try {
            val solicitud = firestore.collection("hogares").document(mapa.codigo.hogarId).collection("solicitudesVinculacion").document(uid)
            firestore.batch().apply {
                set(firestore.collection("usuarios").document(uid), mapOf("activo" to false, "rol" to "movil_pendiente", "hogarId" to mapa.codigo.hogarId, "nombre" to nombre))
                set(solicitud, mapOf("uidMovil" to uid, "nombre" to nombre, "codigo" to codigo, "estado" to "PENDIENTE", "creadoEn" to FieldValue.serverTimestamp()))
            }.commit().addOnCompleteListener { tarea -> completarSolicitudMovil(id, uid, tarea.isSuccessful) }
        } catch (_: Exception) { finalizarRegistroMovilFallido(id, uid) }
    }

    private fun completarSolicitudMovil(id: Long, uid: String, exitosa: Boolean) {
        val activa = registroMovilActivo ?: return
        if (activa.id != id || auth.currentUser?.uid != uid || activa.expirada || !exitosa) { finalizarRegistroMovilFallido(id, uid); return }
        activa.timeout.cancel(); registroMovilActivo = null
        iniciarConsultaPerfil(uid)
    }

    private fun finalizarRegistroMovilFallido(id: Long, uid: String?) {
        val activa = registroMovilActivo ?: return
        if (activa.id != id) return
        activa.timeout.cancel(); registroMovilActivo = null
        if (uid != null && auth.currentUser?.uid == uid) try { auth.currentUser?.delete() } catch (_: Exception) {}
        auth.signOut()
        _estado.value = EstadoSesionUi(haySesion = false, registroMovil = EstadoRegistroMovilUi.Error("No se pudo solicitar la vinculación. Inténtalo más tarde."))
    }

    fun cargarMoviles() {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (!perfil.rol.esEstacion() || lecturaMovilesActiva != null) return
        val uid = auth.currentUser?.uid ?: return
        val sesion = sesionActual
        val id = ++siguienteId
        registrarDatos("moviles_inicio", "iniciado", null)
        _estado.value = _estado.value.copy(listaMoviles = EstadoListaMovilesUi.Cargando)
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activa = lecturaMovilesActiva
            if (activa?.id == id) {
                lecturaMovilesActiva = activa.copy(expirada = true)
                registrarDatos("moviles_timeout", "timeout", null)
                _estado.value = _estado.value.copy(listaMoviles = EstadoListaMovilesUi.Error("La carga de móviles tardó demasiado. Puedes reintentar."))
            }
        }
        lecturaMovilesActiva = LecturaMovilesActiva(id, uid, perfil.hogarId, sesion, timeout)
        try {
            val hogar = firestore.collection("hogares").document(perfil.hogarId)
            hogar.collection("moviles").get(Source.SERVER).addOnCompleteListener { tareaMoviles ->
                val activa = lecturaMovilesActiva
                if (activa?.id != id || auth.currentUser?.uid != uid || sesionActual != sesion) return@addOnCompleteListener
                activa.timeout.cancel()
                lecturaMovilesActiva = null
                if (!tareaMoviles.isSuccessful || tareaMoviles.result == null) { registrarDatos("moviles_error", "fallo", tareaMoviles.exception); _estado.value = _estado.value.copy(listaMoviles = EstadoListaMovilesUi.Error("No se pudieron cargar los móviles vinculados.")); return@addOnCompleteListener }
                val vinculados = tareaMoviles.result!!.documents.mapNotNull { documento ->
                    val datos = documento.data ?: return@mapNotNull null
                    val uidMovil = datos["uidMovil"] as? String ?: return@mapNotNull null
                    val nombre = datos["nombre"] as? String ?: return@mapNotNull null
                    if (uidMovil != documento.id || validarNombreVisible(nombre) != null) null else SolicitudVinculacion(uidMovil, nombre, "ABCDEFGHJKLM", "APROBADA")
                }
                registrarDatos(if (vinculados.isEmpty()) "moviles_vacio" else "moviles_exito", "exito", null)
                _estado.value = _estado.value.copy(listaMoviles = EstadoListaMovilesUi.Valido(vinculados), moviles = EstadoMovilesUi.Valido(emptyList(), vinculados))
                sincronizarPresenciasEstacion(perfil, vinculados.map { it.uidMovil }.toSet())
            }
        } catch (error: Exception) { lecturaMovilesActiva?.timeout?.cancel(); lecturaMovilesActiva = null; registrarDatos("moviles_error", "excepcion", error); _estado.value = _estado.value.copy(listaMoviles = EstadoListaMovilesUi.Error("No se pudieron cargar los móviles vinculados.")) }
    }

    fun cargarSolicitudes() {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (!perfil.rol.esEstacion() || _estado.value.solicitudes is EstadoSolicitudesUi.Cargando) return
        val uid = auth.currentUser?.uid ?: return
        val sesion = sesionActual
        registrarDatos("solicitudes_inicio", "iniciado", null)
        _estado.value = _estado.value.copy(solicitudes = EstadoSolicitudesUi.Cargando)
        try { firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").get(Source.SERVER).addOnCompleteListener { tarea ->
            if (auth.currentUser?.uid != uid || sesionActual != sesion) return@addOnCompleteListener
            if (!tarea.isSuccessful || tarea.result == null) { registrarDatos("solicitudes_error", "fallo", tarea.exception); _estado.value = _estado.value.copy(solicitudes = EstadoSolicitudesUi.Error("No se pudieron cargar las solicitudes.")); return@addOnCompleteListener }
            val pendientes = tarea.result!!.documents.mapNotNull { (validarSolicitudVinculacion(it.data ?: emptyMap()) as? ResultadoSolicitudVinculacion.Valida)?.solicitud }.filter { it.estado == "PENDIENTE" }
            registrarDatos("solicitudes_exito", "exito", null)
            _estado.value = _estado.value.copy(solicitudes = EstadoSolicitudesUi.Valido(pendientes))
        } } catch (error: Exception) { registrarDatos("solicitudes_error", "excepcion", error); _estado.value = _estado.value.copy(solicitudes = EstadoSolicitudesUi.Error("No se pudieron cargar las solicitudes.")) }
    }

    private fun contextoPresenciaVigente(uid: String, hogarId: String, sesion: Long, generacion: Long): Boolean =
        auth.currentUser?.uid == uid && sesionActual == sesion && generacionPresencia == generacion &&
            ((_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil?.let { it.hogarId == hogarId } == true)

    private fun iniciarPresenciaMovil(perfil: PerfilUsuario) {
        if (perfil.rol != RolUsuario.MOVIL) return
        val uid = auth.currentUser?.uid ?: return
        invalidarPresencia(sinPublicar = true)
        _estado.value = _estado.value.copy(presenciaPropia = EstadoPresenciaPropiaUi.Conectando)
        try { getApplication<Application>().startForegroundService(Intent(getApplication(), ServicioPresenciaMovil::class.java).putExtra(ServicioPresenciaMovil.EXTRA_UID, uid).putExtra(ServicioPresenciaMovil.EXTRA_HOGAR, perfil.hogarId)) } catch (_: Exception) { _estado.value = _estado.value.copy(presenciaPropia = EstadoPresenciaPropiaUi.Error) }
    }

    private fun sincronizarPresenciasEstacion(perfil: PerfilUsuario, vinculados: Set<String>) {
        if (!perfil.rol.esEstacion()) return
        if (listenerDesfaseServidor == null) {
            listenerDesfaseServidor = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { (snapshot.value as? Long)?.let { _estado.value = _estado.value.copy(desfaseHoraServidor = it) } }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) { }
            }
            realtimeDatabase.getReference(".info/serverTimeOffset").addValueEventListener(listenerDesfaseServidor!!)
        }
        val uidEstacion = auth.currentUser?.uid ?: return
        val sesion = sesionActual
        val generacion = generacionPresencia
        listenersPresenciaEstacion.values.filter { it.uidMovil !in vinculados }.forEach { it.referencia.removeEventListener(it.listener) }
        listenersPresenciaEstacion.keys.retainAll(vinculados)
        val actuales = _estado.value.presenciasMoviles.filterKeys { it in vinculados }.toMutableMap()
        vinculados.forEach { uidMovil ->
            actuales.putIfAbsent(uidMovil, EstadoPresenciaMovilUi.Cargando)
            if (listenersPresenciaEstacion.containsKey(uidMovil)) return@forEach
            val referencia = realtimeDatabase.getReference("presencia").child(perfil.hogarId).child(uidMovil)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!contextoPresenciaVigente(uidEstacion, perfil.hogarId, sesion, generacion) || !uidEstaVinculado(uidMovil, listenersPresenciaEstacion.keys)) return
                    val estadoPresencia = clasificarPresenciaMovil(snapshot.value)
                    _estado.value = _estado.value.copy(presenciasMoviles = _estado.value.presenciasMoviles + (uidMovil to estadoPresencia))
                    publicarPresenciasUi()
                    val presencia = (estadoPresencia as? EstadoPresenciaMovilUi.Valida)?.presencia ?: return
                    val nombre = ((_estado.value.moviles as? EstadoMovilesUi.Valido)?.vinculados?.firstOrNull { it.uidMovil == uidMovil })?.nombre ?: return
                    registrarTransicionPresencia(uidEstacion, perfil.hogarId, uidMovil, nombre, presencia, sesion, generacion)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    if (contextoPresenciaVigente(uidEstacion, perfil.hogarId, sesion, generacion) && uidEstaVinculado(uidMovil, listenersPresenciaEstacion.keys)) {
                        _estado.value = _estado.value.copy(presenciasMoviles = _estado.value.presenciasMoviles + (uidMovil to EstadoPresenciaMovilUi.Error("Error de conexión")))
                        publicarPresenciasUi()
                    }
                }
            }
            referencia.addValueEventListener(listener)
            listenersPresenciaEstacion[uidMovil] = ListenerPresenciaEstacion(uidMovil, referencia, listener)
        }
        _estado.value = _estado.value.copy(presenciasMoviles = actuales)
        publicarPresenciasUi()
        if (vinculados.isEmpty()) {
            tickerPresenciaEstacion?.cancel()
            tickerPresenciaEstacion = null
            ultimoVisualPresencia.clear()
        } else if (tickerPresenciaEstacion == null) {
            registrarPresenciaEstacion("estacion_ticker_inicio", "móviles observados")
            tickerPresenciaEstacion = viewModelScope.launch {
                while (true) {
                    delay(5_000L)
                    val actual = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil
                    if (actual == null || !actual.rol.esEstacion()) break
                    val horaServidor = System.currentTimeMillis() + _estado.value.desfaseHoraServidor
                    _estado.value.presenciasMoviles.forEach { (uidMovil, estadoPresencia) ->
                        val visual = estadoVisualPresencia(estadoPresencia, horaServidor)
                        val anterior = ultimoVisualPresencia.put(uidMovil, visual)
                        val presenciaValida = (estadoPresencia as? EstadoPresenciaMovilUi.Valida)?.presencia
                        val edadSegundos = presenciaValida?.let { ((horaServidor - it.ultimoLatido).coerceAtLeast(0L) / 1_000L) }
                        registrarPresenciaEstacion("estacion_ticker_tick", "válido=${presenciaValida != null} previo=${anterior ?: "ninguno"} calculado=$visual edadSegundos=${edadSegundos ?: "n/a"}")
                        if (anterior != null && anterior != visual && visual in setOf(EstadoVisualPresencia.ACTIVO, EstadoVisualPresencia.DESCONECTADO)) {
                            val presencia = presenciaValida ?: return@forEach
                            registrarPresenciaEstacion(if (visual == EstadoVisualPresencia.ACTIVO) "estacion_reclasificacion_activo" else "estacion_reclasificacion_desconectado", "transición")
                            val nombre = ((_estado.value.listaMoviles as? EstadoListaMovilesUi.Valido)?.moviles?.firstOrNull { it.uidMovil == uidMovil })?.nombre ?: return@forEach
                            registrarTransicionPresencia(auth.currentUser?.uid ?: return@forEach, actual.hogarId, uidMovil, nombre, presencia.copy(conectado = visual == EstadoVisualPresencia.ACTIVO), sesionActual, generacionPresencia)
                        }
                    }
                    _estado.value = _estado.value.copy(marcaRelojPresencia = horaServidor)
                    publicarPresenciasUi(horaServidor)
                }
            }
        }
    }

    private fun publicarPresenciasUi(horaServidor: Long = System.currentTimeMillis() + _estado.value.desfaseHoraServidor) {
        val aprobados = (_estado.value.listaMoviles as? EstadoListaMovilesUi.Valido)?.moviles ?: emptyList()
        val ui = aprobados.map { movil ->
            PresenciaMovilUi(movil.uidMovil, movil.nombre, estadoVisualPresencia(_estado.value.presenciasMoviles[movil.uidMovil], horaServidor))
        }
        _estado.value = _estado.value.copy(presenciasUi = ui)
        ui.forEach(notificadorAlertasConexion::procesar)
        registrarPresenciaEstacion("estacion_ui_publicada", "aprobados=${aprobados.size} entradas=${ui.size} estados=${ui.joinToString { it.estadoFinal.name }}")
    }

    private fun registrarTransicionPresencia(uidEstacion: String, hogarId: String, uidMovil: String, nombre: String, presencia: PresenciaMovil, sesion: Long, generacion: Long) {
        if (!contextoPresenciaVigente(uidEstacion, hogarId, sesion, generacion)) return
        try {
            val movil = firestore.collection("hogares").document(hogarId).collection("moviles").document(uidMovil)
            val evento = firestore.collection("hogares").document(hogarId).collection("historial").document()
            firestore.runTransaction<Unit> { tx ->
                val datos = tx.get(movil).data ?: throw FirebaseFirestoreException("Móvil no aprobado", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                if (datos["uidMovil"] != uidMovil || datos["nombre"] != nombre) throw FirebaseFirestoreException("Móvil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val decision = decidirRegistroPresencia(datos["estadoPresenciaRegistrado"] as? String, presencia)
                if (datos["estadoPresenciaRegistrado"] != decision.estadoPersistido) tx.update(movil, "estadoPresenciaRegistrado", decision.estadoPersistido)
                decision.evento?.let { tipo -> tx.set(evento, mapOf("tipo" to tipo.name, "uidMovil" to uidMovil, "nombreMovil" to nombre, "creadoEn" to FieldValue.serverTimestamp())) }
                Unit
            }.addOnCompleteListener { tarea -> if (!tarea.isSuccessful) registrarPresenciaEstacion("estacion_historial_error", "tipo=${tarea.exception?.javaClass?.simpleName ?: "sin_error"}") }
        } catch (_: Exception) { registrarPresenciaEstacion("estacion_historial_error", "excepción") }
    }

    private fun registrarPresenciaEstacion(etapa: String, detalle: String) {
        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Log.d("SafeHomePresencia", "etapa=$etapa $detalle")
    }

    private fun invalidarPresencia(sinPublicar: Boolean = false) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil
        val uid = auth.currentUser?.uid
        if (!sinPublicar && perfil?.rol == RolUsuario.MOVIL && uid != null) {
            try { getApplication<Application>().stopService(Intent(getApplication(), ServicioPresenciaMovil::class.java)); realtimeDatabase.getReference("presencia").child(perfil.hogarId).child(uid).setValue(mapOf("conectado" to false, "ultimaConexion" to ServerValue.TIMESTAMP, "ultimoLatido" to ServerValue.TIMESTAMP)) } catch (_: Exception) {}
        }
        listenerConexionMovil?.let { referenciaConexionMovil?.removeEventListener(it) }
        listenerConexionMovil = null
        referenciaConexionMovil = null
        listenerDesfaseServidor?.let { realtimeDatabase.getReference(".info/serverTimeOffset").removeEventListener(it) }
        listenerDesfaseServidor = null
        listenersPresenciaEstacion.values.forEach { it.referencia.removeEventListener(it.listener) }
        listenersPresenciaEstacion.clear()
        tickerPresenciaEstacion?.cancel()
        tickerPresenciaEstacion = null
        registrarPresenciaEstacion("estacion_ticker_detener", "contexto invalidado")
        ultimoVisualPresencia.clear()
        notificadorAlertasConexion.reiniciar()
        _estado.value = _estado.value.copy(presenciasUi = emptyList())
        publicacionPresenciaActiva = null
        generacionPresencia++
    }

    private fun invalidarLecturaMoviles() {
        lecturaMovilesActiva?.timeout?.cancel()
        lecturaMovilesActiva = null
    }

    fun aprobarMovil(uidMovil: String) {
        if (aprobacionMovilActiva != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (!perfil.rol.esEstacion()) return
        val uid = auth.currentUser?.uid ?: return
        val id = ++siguienteId; aprobacionMovilActiva = id
        try {
            val usuario = firestore.collection("usuarios").document(uidMovil)
            val solicitud = firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").document(uidMovil)
            val movil = firestore.collection("hogares").document(perfil.hogarId).collection("moviles").document(uidMovil)
            firestore.runTransaction<Unit> { tx ->
                val perfilMovil = tx.get(usuario).data ?: throw FirebaseFirestoreException("Perfil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val solicitudActual = (validarSolicitudVinculacion(tx.get(solicitud).data ?: emptyMap()) as? ResultadoSolicitudVinculacion.Valida)?.solicitud ?: throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                if (solicitudActual.estado != "PENDIENTE" || solicitudActual.uidMovil != uidMovil || perfilMovil["rol"] != "movil_pendiente" || perfilMovil["activo"] != false || perfilMovil["hogarId"] != perfil.hogarId || perfilMovil["nombre"] != solicitudActual.nombre) throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                tx.update(usuario, mapOf("activo" to true, "rol" to "movil"))
                tx.update(solicitud, mapOf("estado" to "APROBADA", "respondidaEn" to FieldValue.serverTimestamp()))
                tx.set(movil, mapOf("uidMovil" to uidMovil, "nombre" to solicitudActual.nombre, "vinculadoEn" to FieldValue.serverTimestamp()))
                Unit
            }.addOnCompleteListener { tarea ->
                if (aprobacionMovilActiva != id || auth.currentUser?.uid != uid) return@addOnCompleteListener
                if (!tarea.isSuccessful) {
                    aprobacionMovilActiva = null
                    registrarFalloVinculacion("transacción", tarea.exception)
                    _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo aprobar la vinculación."))
                } else confirmarAprobacionServidor(id, uid, perfil.hogarId, uidMovil, usuario, solicitud, movil)
            }
        } catch (error: Exception) { aprobacionMovilActiva = null; registrarFalloVinculacion("lectura previa", error); _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo aprobar la vinculación.")) }
    }

    fun rechazarMovil(uidMovil: String) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (!perfil.rol.esEstacion()) return
        val uidEstacion = auth.currentUser?.uid ?: return
        val sesion = sesionActual
        try {
            val usuario = firestore.collection("usuarios").document(uidMovil)
            val solicitud = firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").document(uidMovil)
            firestore.runTransaction<Unit> { tx ->
                val perfilMovil = tx.get(usuario).data ?: throw FirebaseFirestoreException("Perfil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val solicitudActual = (validarSolicitudVinculacion(tx.get(solicitud).data ?: emptyMap()) as? ResultadoSolicitudVinculacion.Valida)?.solicitud
                    ?: throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                if (solicitudActual.estado != "PENDIENTE" || solicitudActual.uidMovil != uidMovil || perfilMovil["rol"] != "movil_pendiente" || perfilMovil["activo"] != false || perfilMovil["hogarId"] != perfil.hogarId || perfilMovil["nombre"] != solicitudActual.nombre) throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                tx.update(usuario, mapOf("rol" to "movil_rechazado", "activo" to false))
                tx.update(solicitud, mapOf("estado" to "RECHAZADA", "respondidaEn" to FieldValue.serverTimestamp()))
                Unit
            }.addOnCompleteListener { tarea ->
                if (auth.currentUser?.uid != uidEstacion || sesionActual != sesion) return@addOnCompleteListener
                if (tarea.isSuccessful) cargarMoviles() else _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo rechazar la vinculación."))
            }
        } catch (_: Exception) { _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo rechazar la vinculación.")) }
    }

    fun desvincularMovil(uidMovil: String) {
        if (desvinculacionMovilActiva != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val uidEstacion = auth.currentUser?.uid ?: return
        if (!perfil.rol.esEstacion() || uidMovil.isBlank()) return
        val sesion = sesionActual
        val id = ++siguienteId
        desvinculacionMovilActiva = id
        _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.EnCurso)
        val usuario = firestore.collection("usuarios").document(uidMovil)
        val solicitud = firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").document(uidMovil)
        val movil = firestore.collection("hogares").document(perfil.hogarId).collection("moviles").document(uidMovil)
        try {
            firestore.runTransaction<Unit> { tx ->
                val datosPerfil = tx.get(usuario).data ?: throw FirebaseFirestoreException("Perfil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val datosSolicitud = (validarSolicitudVinculacion(tx.get(solicitud).data ?: emptyMap()) as? ResultadoSolicitudVinculacion.Valida)?.solicitud
                    ?: throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val datosMovil = tx.get(movil).data ?: throw FirebaseFirestoreException("Móvil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                if (datosPerfil["rol"] != "movil" || datosPerfil["activo"] != true || datosPerfil["hogarId"] != perfil.hogarId ||
                    datosPerfil["nombre"] != datosSolicitud.nombre || !desvinculacionEsValida(PerfilUsuario(RolUsuario.MOVIL, perfil.hogarId, datosSolicitud.nombre), datosSolicitud, uidMovil) ||
                    datosMovil["uidMovil"] != uidMovil || datosMovil["nombre"] != datosSolicitud.nombre) {
                    throw FirebaseFirestoreException("Vinculación no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                }
                tx.update(usuario, mapOf("rol" to "movil_desvinculado", "activo" to false))
                tx.update(solicitud, mapOf("estado" to "DESVINCULADA", "respondidaEn" to FieldValue.serverTimestamp()))
                tx.delete(movil)
                Unit
            }.addOnCompleteListener { tarea ->
                if (desvinculacionMovilActiva != id || auth.currentUser?.uid != uidEstacion || sesionActual != sesion) return@addOnCompleteListener
                desvinculacionMovilActiva = null
                if (!tarea.isSuccessful) {
                    _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Error("No se pudo desvincular el Móvil. Inténtalo nuevamente."))
                    return@addOnCompleteListener
                }
                realtimeDatabase.getReference("presencia").child(perfil.hogarId).child(uidMovil).removeValue()
                _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Inactiva)
                cargarMoviles()
            }
        } catch (_: Exception) { desvinculacionMovilActiva = null; _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Error("No se pudo desvincular el Móvil. Inténtalo nuevamente.")) }
    }

    fun solicitarNuevaVinculacion(codigoEntrada: String) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val uid = auth.currentUser?.uid ?: return
        val codigo = normalizarCodigoVinculacion(codigoEntrada)
        if (!puedeSolicitarNuevaVinculacion(perfil.rol) || !codigoVinculacionEsValido(codigo) || _estado.value.operacionMovil is EstadoOperacionMovilUi.EnCurso) return
        _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.EnCurso)
        firestore.collection("codigosVinculacion").document(codigo).get(Source.SERVER).addOnCompleteListener { tarea ->
            if (auth.currentUser?.uid != uid) return@addOnCompleteListener
            val mapa = if (tarea.isSuccessful) (validarCodigoVinculacion(tarea.result?.data ?: emptyMap()) as? ResultadoCodigoVinculacion.Valido)?.codigo else null
            if (mapa == null) { _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Error("No se pudo solicitar la vinculación. Revisa el código e inténtalo nuevamente.")); return@addOnCompleteListener }
            val usuario = firestore.collection("usuarios").document(uid)
            val solicitud = firestore.collection("hogares").document(mapa.hogarId).collection("solicitudesVinculacion").document(uid)
            firestore.batch().apply {
                update(usuario, mapOf("rol" to "movil_pendiente", "activo" to false, "hogarId" to mapa.hogarId))
                set(solicitud, mapOf("uidMovil" to uid, "nombre" to perfil.nombreVisible, "codigo" to codigo, "estado" to "PENDIENTE", "creadoEn" to FieldValue.serverTimestamp()))
            }.commit().addOnCompleteListener { resultado ->
                if (auth.currentUser?.uid != uid) return@addOnCompleteListener
                if (!resultado.isSuccessful) _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Error("No se pudo solicitar la vinculación. Inténtalo nuevamente."))
                else iniciarConsultaPerfil(uid)
            }
        }
    }

    fun eliminarCuentaMovilDesvinculado(clave: String) = eliminarCuentaMovil(clave)

    fun eliminarCuentaMovil(clave: String) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val usuario = auth.currentUser ?: return
        if (!eliminacionMovilPropiaEsValida(usuario.uid, usuario.uid, perfil.rol) || clave.isEmpty() || operacionCuentaActiva != null) return
        val operacion = iniciarOperacionCuenta(usuario.uid) ?: return
        registrarEliminacion("eliminacion_inicio", true)
        _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.EnCurso, eliminacionCuenta = EstadoEliminacionCuentaUi.EnCurso)
        val credencial = EmailAuthProvider.getCredential(usuario.email ?: run { finalizarOperacionCuenta(operacion.id, "No se pudo completar la eliminación. Vuelve a iniciar sesión."); return }, clave)
        usuario.reauthenticate(credencial).addOnCompleteListener { reautenticacion ->
            if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
            if (!reautenticacion.isSuccessful) { registrarEliminacion("reautenticacion_error", false, reautenticacion.exception); finalizarOperacionCuenta(operacion.id, "No se pudo confirmar la contraseña. Inténtalo nuevamente."); return@addOnCompleteListener }
            registrarEliminacion("reautenticacion_exito", true)
            try {
                realtimeDatabase.getReference("presencia").child(perfil.hogarId).child(usuario.uid).removeValue()
                    .addOnCompleteListener { tarea -> registrarEliminacion(if (tarea.isSuccessful) "limpieza_rtdb_exito" else "limpieza_rtdb_error", tarea.isSuccessful, tarea.exception) }
            } catch (error: Exception) { registrarEliminacion("limpieza_rtdb_error", false, error) }
            retirarDatosMovilPropios(perfil, usuario.uid).addOnCompleteListener { retiro ->
                if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
                if (!retiro.isSuccessful) { registrarEliminacion("limpieza_firestore_error", false, retiro.exception); finalizarOperacionCuenta(operacion.id, "No se pudo completar la eliminación. Inténtalo nuevamente."); return@addOnCompleteListener }
                registrarEliminacion("limpieza_firestore_exito", true)
                usuario.delete().addOnCompleteListener { borradoAuth ->
                    // Firebase puede cerrar la sesión antes de entregar el callback de delete().
                    // En ese caso auth.currentUser ya es null, pero el resultado todavía pertenece
                    // a esta misma operación y debe completar la UI de éxito.
                    if (!operacionCuentaVigenteTrasBorradoAuth(operacion.id, usuario.uid)) return@addOnCompleteListener
                    if (borradoAuth.isSuccessful) { registrarEliminacion("auth_delete_exito", true); finalizarOperacionCuenta(operacion.id); finalizarEliminacionExitosa() }
                    else {
                        registrarEliminacion("auth_delete_error", false, borradoAuth.exception)
                        finalizarOperacionCuenta(operacion.id, "La cuenta quedó sin acceso al hogar. Confirma tu contraseña nuevamente para completar la eliminación.")
                        iniciarConsultaPerfil(usuario.uid)
                    }
                }
            }
        }
    }

    private fun retirarDatosMovilPropios(perfil: PerfilUsuario, uid: String) = firestore.runTransaction<Unit> { tx ->
        val usuario = firestore.collection("usuarios").document(uid)
        val datosPerfil = tx.get(usuario).data ?: throw FirebaseFirestoreException("Perfil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
        if (datosPerfil["hogarId"] != perfil.hogarId || datosPerfil["nombre"] != perfil.nombreVisible) throw FirebaseFirestoreException("Perfil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
        if (perfil.rol == RolUsuario.MOVIL) {
            val solicitud = firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").document(uid)
            val movil = firestore.collection("hogares").document(perfil.hogarId).collection("moviles").document(uid)
            val datosSolicitud = tx.get(solicitud).data ?: throw FirebaseFirestoreException("Solicitud no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
            val datosMovil = tx.get(movil).data ?: throw FirebaseFirestoreException("Móvil no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
            if (datosPerfil["rol"] != "movil" || datosPerfil["activo"] != true || datosSolicitud["uidMovil"] != uid || datosSolicitud["estado"] != "APROBADA" || datosMovil["uidMovil"] != uid) throw FirebaseFirestoreException("Cuenta no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
            tx.update(usuario, mapOf("rol" to "movil_desvinculado", "activo" to false))
            tx.delete(movil)
            tx.delete(solicitud)
        } else if (perfil.rol !in setOf(RolUsuario.MOVIL_DESVINCULADO, RolUsuario.MOVIL_RECHAZADO) ||
            datosPerfil["rol"] !in setOf("movil_desvinculado", "movil_rechazado") || datosPerfil["activo"] != false) {
            throw FirebaseFirestoreException("Cuenta no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
        }
        Unit
    }

    fun eliminarCuentaEstacion(clave: String) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val usuario = auth.currentUser ?: return
        if (!perfil.rol.esEstacion() || clave.isEmpty() || operacionCuentaActiva != null) return
        val operacion = iniciarOperacionCuenta(usuario.uid) ?: return
        registrarEliminacion("eliminacion_inicio", true)
        _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.EnCurso, eliminacionCuenta = EstadoEliminacionCuentaUi.EnCurso)
        val credencial = EmailAuthProvider.getCredential(usuario.email ?: run { finalizarOperacionCuenta(operacion.id, "No se pudo completar la eliminación. Vuelve a iniciar sesión."); return }, clave)
        usuario.reauthenticate(credencial).addOnCompleteListener { reautenticacion ->
            if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
            if (!reautenticacion.isSuccessful) { registrarEliminacion("reautenticacion_error", false, reautenticacion.exception); finalizarOperacionCuenta(operacion.id, "No se pudo confirmar la contraseña. Inténtalo nuevamente."); return@addOnCompleteListener }
            registrarEliminacion("reautenticacion_exito", true)
            val hogar = firestore.collection("hogares").document(perfil.hogarId)
            hogar.collection("moviles").get(Source.SERVER).addOnCompleteListener { moviles ->
                if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
                if (!moviles.isSuccessful || moviles.result == null) { finalizarOperacionCuenta(operacion.id, "No se pudo verificar la cuenta. Inténtalo nuevamente."); return@addOnCompleteListener }
                hogar.collection("solicitudesVinculacion").get(Source.SERVER).addOnCompleteListener { solicitudes ->
                    if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
                    if (!solicitudes.isSuccessful || solicitudes.result == null) { finalizarOperacionCuenta(operacion.id, "No se pudo verificar la cuenta. Inténtalo nuevamente."); return@addOnCompleteListener }
                    val pendientes = solicitudes.result!!.documents.count { it.getString("estado") == "PENDIENTE" }
                    if (!estacionPuedePrepararEliminacion(moviles.result!!.size(), pendientes)) {
                        finalizarOperacionCuenta(operacion.id, "Para eliminar esta cuenta, primero desvincula los móviles y resuelve las solicitudes pendientes.")
                        return@addOnCompleteListener
                    }
                    hogar.collection("vinculacion").document("actual").get(Source.SERVER).addOnCompleteListener { vinculacion ->
                        if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
                        if (!vinculacion.isSuccessful || vinculacion.result?.exists() != true) { finalizarOperacionCuenta(operacion.id, "No se pudo preparar la eliminación. Inténtalo nuevamente."); return@addOnCompleteListener }
                        val codigo = vinculacion.result?.getString("codigo")
                        if (!codigoVinculacionEsValido(codigo ?: "") || vinculacion.result?.getBoolean("activo") != true) { finalizarOperacionCuenta(operacion.id, "No se pudo preparar la eliminación. Inténtalo nuevamente."); return@addOnCompleteListener }
                        firestore.batch().apply {
                            update(firestore.collection("usuarios").document(usuario.uid), mapOf("activo" to false, "rol" to "estacion_eliminada"))
                            update(hogar.collection("vinculacion").document("actual"), mapOf("activo" to false, "actualizadoEn" to FieldValue.serverTimestamp()))
                            if (codigoVinculacionEsValido(codigo ?: "")) delete(firestore.collection("codigosVinculacion").document(codigo!!))
                        }.commit().addOnCompleteListener { preparado ->
                            if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
                            if (!preparado.isSuccessful) { registrarEliminacion("limpieza_firestore_error", false, preparado.exception); finalizarOperacionCuenta(operacion.id, "No se pudo preparar la eliminación. Inténtalo nuevamente."); return@addOnCompleteListener }
                            registrarEliminacion("limpieza_firestore_exito", true)
                            usuario.delete().addOnCompleteListener { borrado ->
                                // delete() puede invalidar currentUser antes de este callback.
                                if (!operacionCuentaVigenteTrasBorradoAuth(operacion.id, usuario.uid)) return@addOnCompleteListener
                                if (borrado.isSuccessful) { registrarEliminacion("auth_delete_exito", true); finalizarOperacionCuenta(operacion.id); finalizarEliminacionExitosa() }
                                else {
                                    registrarEliminacion("auth_delete_error", false, borrado.exception)
                                    finalizarOperacionCuenta(operacion.id, "La cuenta quedó sin acceso al hogar. Confirma tu contraseña nuevamente para completar la eliminación.")
                                    iniciarConsultaPerfil(usuario.uid)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun reintentarEliminacionCuentaEstacion(clave: String) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.EliminacionPendiente)?.perfil ?: return
        val usuario = auth.currentUser ?: return
        if (perfil.rol != RolUsuario.ESTACION_ELIMINADA || clave.isEmpty() || operacionCuentaActiva != null) return
        val operacion = iniciarOperacionCuenta(usuario.uid) ?: return
        registrarEliminacion("eliminacion_inicio", true)
        _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.EnCurso, eliminacionCuenta = EstadoEliminacionCuentaUi.EnCurso)
        val correo = usuario.email
        if (correo.isNullOrBlank()) {
            finalizarOperacionCuenta(operacion.id, "No se pudo completar la eliminación. Vuelve a iniciar sesión.")
            return
        }
        val credencial = EmailAuthProvider.getCredential(correo, clave)
        usuario.reauthenticate(credencial).addOnCompleteListener { reautenticacion ->
            if (!operacionCuentaVigente(operacion.id, usuario.uid)) return@addOnCompleteListener
            if (!reautenticacion.isSuccessful) {
                registrarEliminacion("reautenticacion_error", false, reautenticacion.exception)
                finalizarOperacionCuenta(operacion.id, "No se pudo confirmar la contraseña. Inténtalo nuevamente.")
                return@addOnCompleteListener
            }
            registrarEliminacion("reautenticacion_exito", true)
            usuario.delete().addOnCompleteListener { borrado ->
                // El reintento también debe aceptar el callback posterior al cierre de Auth.
                if (!operacionCuentaVigenteTrasBorradoAuth(operacion.id, usuario.uid)) return@addOnCompleteListener
                if (borrado.isSuccessful) {
                    registrarEliminacion("auth_delete_exito", true)
                    finalizarOperacionCuenta(operacion.id)
                    finalizarEliminacionExitosa()
                } else {
                    registrarEliminacion("auth_delete_error", false, borrado.exception)
                    finalizarOperacionCuenta(operacion.id, "No se pudo completar la eliminación. Inténtalo nuevamente.")
                }
            }
        }
    }

    private fun iniciarOperacionCuenta(uid: String): OperacionCuentaActiva? {
        if (operacionCuentaActiva != null) return null
        val id = ++siguienteId
        val sesion = sesionActual
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activa = operacionCuentaActiva
            if (activa?.id == id) {
                operacionCuentaActiva = activa.copy(expirada = true)
                _estado.value = _estado.value.copy(operacionMovil = EstadoOperacionMovilUi.Error("La operación tardó demasiado. Vuelve a iniciar sesión antes de reintentar."), eliminacionCuenta = EstadoEliminacionCuentaUi.Error("La operación tardó demasiado. Vuelve a iniciar sesión antes de reintentar."))
            }
        }
        return OperacionCuentaActiva(id, uid, sesion, timeout).also { operacionCuentaActiva = it }
    }

    private fun operacionCuentaVigente(id: Long, uid: String): Boolean {
        val activa = operacionCuentaActiva
        return activa?.id == id && !activa.expirada && activa.uid == uid && activa.sesion == sesionActual && auth.currentUser?.uid == uid
    }

    /**
     * La eliminación de Firebase Auth es la única etapa que puede quitar
     * currentUser antes de que llegue su propio callback. Conservamos las
     * protecciones de id, UID, sesión y timeout, sin exigir que Auth siga
     * exponiendo un usuario que justamente acaba de ser eliminado.
     */
    private fun operacionCuentaVigenteTrasBorradoAuth(id: Long, uid: String): Boolean {
        val activa = operacionCuentaActiva
        return activa?.id == id && !activa.expirada && activa.uid == uid && activa.sesion == sesionActual
    }

    private fun finalizarOperacionCuenta(id: Long, error: String? = null) {
        val activa = operacionCuentaActiva ?: return
        if (activa.id != id) return
        activa.timeout.cancel()
        operacionCuentaActiva = null
        _estado.value = _estado.value.copy(
            operacionMovil = if (error == null) EstadoOperacionMovilUi.Inactiva else EstadoOperacionMovilUi.Error(error),
            eliminacionCuenta = if (error == null) _estado.value.eliminacionCuenta else EstadoEliminacionCuentaUi.Error(error)
        )
    }

    private fun confirmarAprobacionServidor(id: Long, uidEstacion: String, hogarId: String, uidMovil: String, usuario: com.google.firebase.firestore.DocumentReference, solicitud: com.google.firebase.firestore.DocumentReference, movil: com.google.firebase.firestore.DocumentReference) {
        try {
            firestore.runTransaction<ConfirmacionAprobacion> { tx ->
                ConfirmacionAprobacion(
                    tx.get(usuario).data ?: emptyMap(),
                    tx.get(solicitud).data ?: emptyMap(),
                    tx.get(movil).data ?: emptyMap()
                )
            }.addOnCompleteListener { tarea ->
                if (aprobacionMovilActiva != id || auth.currentUser?.uid != uidEstacion) return@addOnCompleteListener
                aprobacionMovilActiva = null
                if (!tarea.isSuccessful || tarea.result == null) {
                    registrarFalloVinculacion("confirmación servidor", tarea.exception)
                    _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo aprobar la vinculación."))
                    return@addOnCompleteListener
                }
                val documentos = tarea.result!!
                val perfil = documentos.perfil
                val solicitudActual = documentos.solicitud
                val movilActual = documentos.movil
                val nombre = solicitudActual["nombre"] as? String
                if (nombre == null || !aprobacionServidorEsValida(perfil, solicitudActual, movilActual, uidMovil, hogarId, nombre)) {
                    registrarFalloVinculacion("confirmación servidor", null)
                    _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo aprobar la vinculación."))
                } else cargarMoviles()
            }
        } catch (error: Exception) {
            aprobacionMovilActiva = null
            registrarFalloVinculacion("confirmación servidor", error)
            _estado.value = _estado.value.copy(moviles = EstadoMovilesUi.Error("No se pudo aprobar la vinculación."))
        }
    }

    private fun registrarFalloVinculacion(etapa: String, error: Exception?) {
        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val firestoreError = error as? FirebaseFirestoreException
        Log.e("SafeHomeVinculacion", "etapa=$etapa tipo=${error?.javaClass?.simpleName ?: "sin_excepcion"} codigo=${firestoreError?.code ?: "sin_codigo"} mensaje=${error?.message ?: "sin_mensaje"}")
    }

    fun recuperarContrasena(correo: String): String? {
        val correoNormalizado = normalizarCorreo(correo)
        val error = validarCorreoRecuperacion(correoNormalizado)
        if (error != null) {
            registrarDiagnosticoRecuperacion("recuperacion_correo_invalido")
            _estado.value = _estado.value.copy(recuperacion = estadoRecuperacionCorreoInvalido(error))
            return error
        }
        if (!recuperacionPuedeIniciarse(solicitudRecuperacionActiva?.id)) return null
        val id = ++siguienteId
        registrarDiagnosticoRecuperacion("recuperacion_inicio")
        _estado.value = _estado.value.copy(recuperacion = estadoRecuperacionAlIniciar(), mensaje = null)
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activa = solicitudRecuperacionActiva
            if (activa != null && callbackRecuperacionEsVigente(id, activa.id)) {
                solicitudRecuperacionActiva = null
                activa.timeout.cancel()
                registrarDiagnosticoRecuperacion("recuperacion_timeout")
                _estado.value = _estado.value.copy(recuperacion = estadoRecuperacionPorTimeout())
            }
        }
        solicitudRecuperacionActiva = SolicitudRecuperacionActiva(id, timeout)
        try {
            auth.sendPasswordResetEmail(correoNormalizado).addOnCompleteListener { tarea ->
                completarRecuperacion(id, tarea.isSuccessful, tarea.exception)
            }
        } catch (_: Exception) {
            completarRecuperacion(id, false, null)
        }
        return null
    }

    private fun completarRecuperacion(id: Long, fueExitosa: Boolean, error: Exception?) {
        val activa = solicitudRecuperacionActiva
        if (activa == null || !callbackRecuperacionEsVigente(id, activa.id)) {
            registrarDiagnosticoRecuperacion("recuperacion_callback_descartado")
            return
        }
        activa.timeout.cancel()
        solicitudRecuperacionActiva = null
        registrarDiagnosticoRecuperacion(if (fueExitosa) "recuperacion_exito" else "recuperacion_error")
        _estado.value = _estado.value.copy(
            recuperacion = estadoRecuperacionFinalizada(fueExitosa, mensajeParaRecuperacion(error))
        )
    }

    private fun registrarDiagnosticoRecuperacion(etapa: String) {
        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Log.d("SafeHomeRecuperacion", etapa)
    }

    private fun completarSolicitud(id: Long, fueExitosa: Boolean, error: Exception?, uid: String?) {
        val activa = solicitudActiva ?: return
        if (activa.id != id) return
        activa.timeout.cancel()
        solicitudActiva = null
        val expirada = _estado.value.solicitud == EstadoSolicitudInicio.EsperaAgotada
        if (fueExitosa && !expirada && uid != null && auth.currentUser?.uid == uid) {
            iniciarConsultaPerfil(uid)
        } else if (fueExitosa) {
            if (auth.currentUser?.uid == uid) auth.signOut()
            _estado.value = EstadoSesionUi(haySesion = false)
        } else {
            _estado.value = EstadoSesionUi(haySesion = auth.currentUser != null, mensaje = mensajeParaLogin(error))
        }
    }

    fun reintentarPerfil() {
        if (lecturaPerfilActiva != null) return
        val uid = auth.currentUser?.uid ?: return
        iniciarConsultaPerfil(uid)
    }

    private fun iniciarConsultaPerfil(uid: String) {
        if (lecturaPerfilActiva != null || auth.currentUser?.uid != uid) return
        eliminarListenerEstado()
        eliminarListenerConfiguracion()
        invalidarPresencia()
        invalidarLecturaMoviles()
        lecturaCodigoVinculacionActiva = null
        invalidarEscritura()
        eliminarListenerComando()
        invalidarEnvioComando()
        invalidarLecturaHistorial()
        listenerPerfilMovil?.remove()
        listenerPerfilMovil = null
        notificadorAlertas.reiniciarSesion()
        solicitudPermisoMostrada = false
        val id = ++siguienteId
        val sesion = ++sesionActual
        _estado.value = EstadoSesionUi(
            haySesion = true,
            perfil = EstadoPerfilUi.Comprobando,
            lecturaPerfilPendiente = true
        )
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activa = lecturaPerfilActiva
            if (activa?.id == id && activa.sesion == sesion) {
                lecturaPerfilActiva = activa.copy(expirada = true)
                _estado.value = EstadoSesionUi(
                    haySesion = true,
                    perfil = EstadoPerfilUi.Error("No se pudo comprobar el acceso. Inténtalo más tarde.", false),
                    lecturaPerfilPendiente = true
                )
            }
        }
        lecturaPerfilActiva = LecturaPerfilActiva(id, uid, sesion, timeout)
        try {
            firestore.collection("usuarios").document(uid).get(Source.SERVER).addOnCompleteListener { tarea ->
                completarPerfil(id, uid, sesion, tarea.isSuccessful, if (tarea.isSuccessful) tarea.result else null, tarea.exception)
            }
        } catch (_: Exception) {
            completarPerfil(id, uid, sesion, false, null, null)
        }
    }

    private fun completarPerfil(
        id: Long,
        uid: String,
        sesion: Long,
        fueExitosa: Boolean,
        documento: com.google.firebase.firestore.DocumentSnapshot?,
        error: Exception?
    ) {
        val activa = lecturaPerfilActiva ?: return
        if (!respuestaPerfilEsVigente(
                activa.id,
                activa.uid,
                activa.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                sesion
            )
        ) return
        activa.timeout.cancel()
        lecturaPerfilActiva = null
        if (activa.expirada) {
            // El timeout visual no cancela de forma fiable la lectura remota; su respuesta se descarta.
            _estado.value = EstadoSesionUi(
                haySesion = true,
                perfil = EstadoPerfilUi.Error("No se pudo comprobar el acceso. Inténtalo más tarde.", true)
            )
            return
        }
        if (!fueExitosa) {
            registrarFalloDatos("perfil", error)
            _estado.value = EstadoSesionUi(
                haySesion = true,
                perfil = EstadoPerfilUi.Error(mensajeParaPerfil(error), true)
            )
            return
        }
        if (documento == null || !documento.exists()) {
            denegarPerfil("No tienes acceso configurado para esta aplicación.")
            return
        }
        when (val resultado = validarPerfilUsuario(documento.data ?: emptyMap())) {
            is ResultadoPerfil.Valido -> if (resultado.perfil.rol == RolUsuario.MOVIL_PENDIENTE) esperarAprobacionMovil(resultado.perfil) else aceptarPerfil(resultado.perfil)
            is ResultadoPerfil.Desvinculado -> aceptarPerfilDesvinculado(resultado.perfil)
            is ResultadoPerfil.EliminacionPendiente -> aceptarEliminacionPendiente(resultado.perfil)
            ResultadoPerfil.Invalido -> denegarPerfil("No tienes acceso configurado para esta aplicación.")
        }
    }

    private fun aceptarPerfil(perfil: PerfilUsuario) {
        if (perfil.rol.esRemoto()) notificadorAlertas.prepararCanales()
        if (perfil.rol.esEstacion()) notificadorAlertasConexion.prepararCanal()
        val debeSolicitarPermiso = (perfil.rol.esRemoto() || perfil.rol.esEstacion()) &&
            !solicitudPermisoMostrada && (notificadorAlertas.requierePermisoNotificaciones() || notificadorAlertasConexion.requierePermisoNotificaciones())
        if (debeSolicitarPermiso) solicitudPermisoMostrada = true
        _estado.value = EstadoSesionUi(
            haySesion = true,
            perfil = EstadoPerfilUi.Valido(perfil),
            hogar = EstadoHogarUi.Cargando,
            configuracion = EstadoConfiguracionHogarUi.Cargando,
            solicitarPermisoNotificaciones = debeSolicitarPermiso,
            correoCuenta = auth.currentUser?.email
        )
        iniciarListenerEstado(perfil)
        iniciarListenerConfiguracion(perfil)
        if (perfil.rol.esEstacion()) {
            iniciarListenerComando(perfil)
            iniciarLecturaCodigoVinculacion(perfil)
        }
        if (perfil.rol == RolUsuario.MOVIL) iniciarPresenciaMovil(perfil)
        if (perfil.rol == RolUsuario.MOVIL) observarPerfilMovil(perfil)
        iniciarLecturaNombreHogar(perfil)
    }

    private fun aceptarPerfilDesvinculado(perfil: PerfilUsuario) {
        invalidarPresencia()
        _estado.value = EstadoSesionUi(haySesion = true, perfil = EstadoPerfilUi.Valido(perfil), correoCuenta = auth.currentUser?.email)
    }

    private fun aceptarEliminacionPendiente(perfil: PerfilUsuario) {
        invalidarPresencia()
        _estado.value = EstadoSesionUi(
            haySesion = true,
            perfil = EstadoPerfilUi.EliminacionPendiente(perfil),
            correoCuenta = auth.currentUser?.email
        )
    }

    private fun observarPerfilMovil(perfil: PerfilUsuario) {
        listenerPerfilMovil?.remove()
        val uid = auth.currentUser?.uid ?: return
        listenerPerfilMovil = firestore.collection("usuarios").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
            if (error != null || auth.currentUser?.uid != uid || documento == null || documento.metadata.isFromCache) return@addSnapshotListener
            val resultado = validarPerfilUsuario(documento.data ?: emptyMap())
            if (resultado is ResultadoPerfil.Desvinculado && perfil.rol == RolUsuario.MOVIL) {
                listenerPerfilMovil?.remove(); listenerPerfilMovil = null
                invalidarPresencia()
                try { getApplication<Application>().stopService(Intent(getApplication(), ServicioPresenciaMovil::class.java)); realtimeDatabase.getReference("presencia").child(perfil.hogarId).child(uid).removeValue() } catch (_: Exception) { }
                aceptarPerfilDesvinculado(resultado.perfil)
            }
        }
    }

    private fun denegarPerfil(mensaje: String) {
        _estado.value = EstadoSesionUi(
            haySesion = true,
            perfil = EstadoPerfilUi.Error(mensaje, true)
        )
    }

    private fun esperarAprobacionMovil(perfil: PerfilUsuario) {
        val uid = auth.currentUser?.uid ?: return
        _estado.value = EstadoSesionUi(haySesion = true, vinculacionPendiente = EstadoVinculacionPendienteUi.EsperandoAprobacion)
        try {
            firestore.collection("hogares").document(perfil.hogarId).collection("solicitudesVinculacion").document(uid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
                    if (auth.currentUser?.uid != uid || error != null || documento == null || documento.metadata.isFromCache) return@addSnapshotListener
                    val solicitud = (validarSolicitudVinculacion(documento.data ?: emptyMap()) as? ResultadoSolicitudVinculacion.Valida)?.solicitud
                    if (debeRecargarPerfilTrasRespuestaVinculacion(solicitud?.estado)) {
                        iniciarConsultaPerfil(uid)
                    }
                }
        } catch (_: Exception) { _estado.value = _estado.value.copy(vinculacionPendiente = EstadoVinculacionPendienteUi.Error("No se pudo comprobar la aprobación.")) }
    }

    private fun iniciarLecturaNombreHogar(perfil: PerfilUsuario) {
        val uid = auth.currentUser?.uid ?: return
        val sesion = sesionActual
        try {
            firestore.collection("hogares").document(perfil.hogarId).get(Source.SERVER).addOnCompleteListener { tarea ->
                if (auth.currentUser?.uid != uid || sesion != sesionActual) return@addOnCompleteListener
                val hogar = if (tarea.isSuccessful && tarea.result?.exists() == true) (validarHogarVisible(tarea.result?.data ?: emptyMap()) as? ResultadoHogarVisible.Valido)?.hogar else null
                _estado.value = _estado.value.copy(nombreHogar = nombreHogarSeguro(hogar))
            }
        } catch (_: Exception) { _estado.value = _estado.value.copy(nombreHogar = "Mi hogar") }
    }

    private fun iniciarLecturaCodigoVinculacion(perfil: PerfilUsuario) {
        if (!perfil.rol.esEstacion() || lecturaCodigoVinculacionActiva != null) return
        val uid = auth.currentUser?.uid ?: return
        val activa = LecturaCodigoVinculacionActiva(++siguienteId, uid, perfil.hogarId, sesionActual)
        lecturaCodigoVinculacionActiva = activa
        try {
            firestore.collection("hogares").document(perfil.hogarId).collection("vinculacion").document("actual")
                .get(Source.SERVER).addOnCompleteListener { tarea ->
                    completarLecturaCodigoVinculacion(activa, tarea.isSuccessful, if (tarea.isSuccessful) tarea.result else null, tarea.exception)
                }
        } catch (_: Exception) {
            lecturaCodigoVinculacionActiva = null
        }
    }

    private fun completarLecturaCodigoVinculacion(activa: LecturaCodigoVinculacionActiva, fueExitosa: Boolean, documento: com.google.firebase.firestore.DocumentSnapshot?, error: Exception?) {
        val actual = lecturaCodigoVinculacionActiva ?: return
        if (!respuestaEstadoEsVigente(actual.id, actual.uid, actual.hogarId, actual.sesion, auth.currentUser?.uid, activa.id, activa.uid, activa.hogarId, activa.sesion)) return
        lecturaCodigoVinculacionActiva = null
        val codigo = if (fueExitosa && documento?.exists() == true && documento.getBoolean("activo") == true) documento.getString("codigo") else null
        if (codigoVinculacionEsValido(codigo ?: "")) {
            _estado.value = _estado.value.copy(codigoVinculacion = codigo)
            asegurarMapaCodigoEstacion(activa, codigo!!)
        }
    }

    private fun asegurarMapaCodigoEstacion(activa: LecturaCodigoVinculacionActiva, codigo: String) {
        try {
            val referencia = firestore.collection("codigosVinculacion").document(codigo)
            referencia.get(Source.SERVER).addOnCompleteListener { tarea ->
                if (auth.currentUser?.uid != activa.uid || activa.sesion != sesionActual || !tarea.isSuccessful) return@addOnCompleteListener
                if (!tarea.result!!.exists()) referencia.set(mapOf("hogarId" to activa.hogarId, "propietarioUid" to activa.uid, "activo" to true, "creadoEn" to FieldValue.serverTimestamp()))
            }
        } catch (_: Exception) { /* La UI no afirma que el mapa esté listo si las reglas aún no lo permiten. */ }
    }

    fun reintentarEstadoHogar() {
        if (listenerEstadoActivo != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        iniciarListenerEstado(perfil)
    }

    fun reintentarConfiguracionHogar() {
        if (listenerConfiguracionActivo != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        iniciarListenerConfiguracion(perfil)
    }

    private fun iniciarListenerConfiguracion(perfil: PerfilUsuario) {
        if (listenerConfiguracionActivo != null) return
        val uid = auth.currentUser?.uid ?: return
        if ((_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil != perfil) return
        val activo = ListenerConfiguracionActivo(++siguienteId, uid, perfil.hogarId, sesionActual)
        listenerConfiguracionActivo = activo
        actualizarConfiguracion(EstadoConfiguracionHogarUi.Cargando)
        try {
            val registro = firestore.collection("hogares").document(perfil.hogarId)
                .collection("configuracion").document("actual")
                .addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
                    completarListenerConfiguracion(activo.id, activo.uid, activo.hogarId, activo.sesion, documento, error)
                }
            if (listenerConfiguracionActivo === activo) activo.registro = registro else registro.remove()
        } catch (_: Exception) {
            eliminarListenerConfiguracion()
            actualizarConfiguracion(EstadoConfiguracionHogarUi.Error("No se puede verificar la configuración del sistema.", true))
        }
    }

    private fun completarListenerConfiguracion(id: Long, uid: String, hogarId: String, sesion: Long, documento: com.google.firebase.firestore.DocumentSnapshot?, error: FirebaseFirestoreException?) {
        val activo = listenerConfiguracionActivo ?: return
        if (!respuestaConfiguracionEsVigente(activo.id, activo.uid, activo.hogarId, activo.sesion, auth.currentUser?.uid, id, uid, hogarId, sesion)) return
        if (error != null || documento == null) {
            eliminarListenerConfiguracion()
            actualizarConfiguracion(EstadoConfiguracionHogarUi.Error("No se puede verificar la configuración del sistema.", error?.let(::esErrorTransitorio) ?: true))
            notificadorAlertas.reiniciarSesion()
            return
        }
        if (documento.metadata.isFromCache) {
            actualizarConfiguracion(if (activo.recibioServidor) EstadoConfiguracionHogarUi.Error("No se puede verificar la configuración del sistema.", false) else EstadoConfiguracionHogarUi.EsperandoConfirmacionServidor)
            notificadorAlertas.reiniciarSesion()
            return
        }
        val configuracion = (if (documento.exists()) validarConfiguracionHogar(documento.data ?: emptyMap()) else ResultadoConfiguracionHogar.Invalida as ResultadoConfiguracionHogar)
        if (configuracion !is ResultadoConfiguracionHogar.Valida) {
            actualizarConfiguracion(EstadoConfiguracionHogarUi.Error("No se puede verificar la configuración del sistema.", false))
            notificadorAlertas.reiniciarSesion()
            return
        }
        val anterior = (_estado.value.configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion
        activo.recibioServidor = true
        actualizarConfiguracion(EstadoConfiguracionHogarUi.Valida(configuracion.configuracion))
        if (anterior?.sistemaActivo != configuracion.configuracion.sistemaActivo) notificadorAlertas.reiniciarSesion()
        confirmarEscrituraSistemaSiCorresponde(configuracion.configuracion)
    }

    private fun actualizarConfiguracion(nueva: EstadoConfiguracionHogarUi) {
        if (_estado.value.perfil is EstadoPerfilUi.Valido) _estado.value = _estado.value.copy(configuracion = nueva)
    }

    private fun eliminarListenerConfiguracion() {
        listenerConfiguracionActivo?.registro?.remove()
        listenerConfiguracionActivo = null
    }

    private fun iniciarListenerEstado(perfil: PerfilUsuario) {
        if (listenerEstadoActivo != null) return
        val uid = auth.currentUser?.uid ?: return
        val perfilActual = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (perfilActual != perfil) return

        val activo = ListenerEstadoActivo(
            id = ++siguienteId,
            uid = uid,
            hogarId = perfil.hogarId,
            sesion = sesionActual
        )
        listenerEstadoActivo = activo
        actualizarEstadoHogar(EstadoHogarUi.Cargando)
        try {
            val registro = firestore.collection("hogares").document(perfil.hogarId)
                .collection("estado").document("actual")
                .addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
                    completarListenerEstado(
                        activo.id,
                        activo.uid,
                        activo.hogarId,
                        activo.sesion,
                        documento,
                        error
                    )
                }
            if (listenerEstadoActivo === activo) {
                activo.registro = registro
            } else {
                registro.remove()
            }
        } catch (_: Exception) {
            eliminarListenerEstado()
            actualizarEstadoHogar(
                EstadoHogarUi.Error("No se pudo comprobar el estado actual del hogar.", true)
            )
        }
    }

    private fun completarListenerEstado(
        id: Long,
        uid: String,
        hogarId: String,
        sesion: Long,
        documento: com.google.firebase.firestore.DocumentSnapshot?,
        error: FirebaseFirestoreException?
    ) {
        val activo = listenerEstadoActivo ?: return
        if (!respuestaEstadoEsVigente(
                activo.id,
                activo.uid,
                activo.hogarId,
                activo.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                hogarId,
                sesion
            )
        ) return

        if (error != null) {
            eliminarListenerEstado()
            actualizarEstadoHogar(EstadoHogarUi.Error(mensajeParaEstado(error), esErrorTransitorio(error)))
            return
        }
        if (documento == null) {
            eliminarListenerEstado()
            actualizarEstadoHogar(EstadoHogarUi.Error("No se pudo comprobar el estado actual del hogar.", true))
            return
        }
        if (documento.metadata.isFromCache) {
            actualizarEstadoHogar(
                if (activo.recibioServidor) {
                    EstadoHogarUi.Error("No se puede verificar el estado actual del hogar.", false)
                } else {
                    EstadoHogarUi.EsperandoConfirmacionServidor
                }
            )
            return
        }
        if (!documento.exists()) {
            actualizarEstadoHogar(EstadoHogarUi.Error("El estado del hogar no está disponible.", false))
            return
        }
        when (val resultado = validarEstadoHogar(documento.data ?: emptyMap())) {
            is ResultadoEstadoHogar.Valido -> {
                activo.recibioServidor = true
                actualizarEstadoHogar(EstadoHogarUi.Valido(resultado.estado))
                val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil
                if (perfil?.rol == RolUsuario.CUIDADOR &&
                    notificacionesHabilitadas((_estado.value.configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion)
                ) {
                    notificadorAlertas.procesarEstadoConfirmado(resultado.estado.nivel)
                }
            }
            ResultadoEstadoHogar.Invalido -> {
                actualizarEstadoHogar(EstadoHogarUi.Error("El estado del hogar no es válido.", false))
            }
        }
    }

    private fun actualizarEstadoHogar(nuevoEstado: EstadoHogarUi) {
        if (_estado.value.perfil !is EstadoPerfilUi.Valido) return
        _estado.value = _estado.value.copy(hogar = nuevoEstado)
    }

    private fun eliminarListenerEstado() {
        listenerEstadoActivo?.registro?.remove()
        listenerEstadoActivo = null
    }

    fun descartarSolicitudPermisoNotificaciones() {
        _estado.value = _estado.value.copy(solicitarPermisoNotificaciones = false)
    }

    fun reintentarComando() {
        if (listenerComandoActivo != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (perfil.rol.esEstacion()) iniciarListenerComando(perfil)
    }

    private fun iniciarListenerComando(perfil: PerfilUsuario) {
        if (listenerComandoActivo != null || !perfil.rol.esEstacion()) return
        val uid = auth.currentUser?.uid ?: return
        val perfilActual = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (perfilActual != perfil) return

        val activo = ListenerComandoActivo(++siguienteId, uid, perfil.hogarId, sesionActual)
        listenerComandoActivo = activo
        actualizarEstadoComando(EstadoComandoUi.Cargando)
        try {
            val registro = firestore.collection("hogares").document(perfil.hogarId)
                .collection("comandos").document("actual")
                .addSnapshotListener(MetadataChanges.INCLUDE) { documento, error ->
                    completarListenerComando(
                        activo.id,
                        activo.uid,
                        activo.hogarId,
                        activo.sesion,
                        documento,
                        error
                    )
                }
            if (listenerComandoActivo === activo) activo.registro = registro else registro.remove()
        } catch (_: Exception) {
            eliminarListenerComando()
            actualizarEstadoComando(EstadoComandoUi.Error("No se pudo comprobar la última solicitud.", true))
        }
    }

    private fun completarListenerComando(
        id: Long,
        uid: String,
        hogarId: String,
        sesion: Long,
        documento: com.google.firebase.firestore.DocumentSnapshot?,
        error: FirebaseFirestoreException?
    ) {
        val activo = listenerComandoActivo ?: return
        if (!respuestaComandoEsVigente(
                activo.id,
                activo.uid,
                activo.hogarId,
                activo.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                hogarId,
                sesion
            )
        ) return
        if (error != null) {
            eliminarListenerComando()
            actualizarEstadoComando(EstadoComandoUi.Error(mensajeParaComando(error), esErrorTransitorio(error)))
            return
        }
        if (documento == null) {
            eliminarListenerComando()
            actualizarEstadoComando(EstadoComandoUi.Error("No se pudo comprobar la última solicitud.", true))
            return
        }
        if (documento.metadata.isFromCache) {
            actualizarEstadoComando(
                if (activo.recibioServidor) {
                    EstadoComandoUi.Error("No se puede verificar la última solicitud.", false)
                } else {
                    EstadoComandoUi.EsperandoConfirmacionServidor
                }
            )
            return
        }
        if (!documento.exists()) {
            actualizarEstadoComando(EstadoComandoUi.Error("La última solicitud no está disponible.", false))
            return
        }
        when (val resultado = validarComandoReconocimiento(documento.data ?: emptyMap())) {
            is ResultadoComandoReconocimiento.Valido -> {
                activo.recibioServidor = true
                actualizarEstadoComando(EstadoComandoUi.Valido(resultado.comando))
            }
            ResultadoComandoReconocimiento.Invalido -> {
                actualizarEstadoComando(EstadoComandoUi.Error("La última solicitud no es válida.", false))
            }
        }
    }

    private fun actualizarEstadoComando(nuevoEstado: EstadoComandoUi) {
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        if (!perfil.rol.esEstacion()) return
        _estado.value = _estado.value.copy(comando = nuevoEstado)
    }

    private fun eliminarListenerComando() {
        listenerComandoActivo?.registro?.remove()
        listenerComandoActivo = null
    }

    fun seleccionarActividad(activa: Boolean) {
        if (!activa) {
            invalidarLecturaHistorial()
            _estado.value = _estado.value.copy(historial = null)
            return
        }
        if (lecturaHistorialActiva == null && _estado.value.historial == null) actualizarHistorial()
    }

    fun actualizarHistorial() {
        if (lecturaHistorialActiva != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val uid = auth.currentUser?.uid ?: return
        generacionHistorial++
        paginaHistorialActual = 0
        cursoresHistorial.clear()
        cursoresHistorial.add(null)
        solicitarPaginaHistorial(uid, perfil.hogarId, 0)
    }

    fun paginaHistorialAnterior() {
        if (lecturaHistorialActiva != null || !puedeIrPaginaAnterior(paginaHistorialActual)) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val uid = auth.currentUser?.uid ?: return
        solicitarPaginaHistorial(uid, perfil.hogarId, paginaHistorialActual - 1)
    }

    fun paginaHistorialSiguiente() {
        if (lecturaHistorialActiva != null) return
        val estado = _estado.value.historial as? EstadoHistorialUi.Valido ?: return
        if (!estado.haySiguiente) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val uid = auth.currentUser?.uid ?: return
        solicitarPaginaHistorial(uid, perfil.hogarId, paginaHistorialActual + 1)
    }

    private fun solicitarPaginaHistorial(uid: String, hogarId: String, pagina: Int) {
        if (lecturaHistorialActiva != null || auth.currentUser?.uid != uid) return
        if (pagina !in cursoresHistorial.indices) return
        val cursor = cursoresHistorial[pagina]
        val activo = LecturaHistorialActiva(++siguienteId, uid, hogarId, sesionActual, generacionHistorial, pagina)
        lecturaHistorialActiva = activo
        val anterior = _estado.value.historial as? EstadoHistorialUi.Valido
        _estado.value = _estado.value.copy(
            historial = EstadoHistorialUi.Cargando(
                pagina = pagina,
                eventosVisibles = anterior?.eventos ?: emptyList(),
                haySiguienteVisible = anterior?.haySiguiente ?: false
            )
        )
        try {
            var consulta = firestore.collection("hogares").document(hogarId).collection("historial")
                .whereIn("tipo", listOf("MOVIL_CONECTADO", "MOVIL_DESCONECTADO"))
                .orderBy("creadoEn", Query.Direction.DESCENDING)
                .limit(LIMITE_CONSULTA_HISTORIAL)
            if (cursor != null) consulta = consulta.startAfter(cursor)
            consulta.get(Source.SERVER).addOnCompleteListener { tarea ->
                val resultado = if (tarea.isSuccessful) tarea.result else null
                completarPaginaHistorial(
                    activo.id, activo.uid, activo.hogarId, activo.sesion, activo.generacion, activo.pagina,
                    tarea.isSuccessful, resultado, tarea.exception
                )
            }
        } catch (_: Exception) {
            lecturaHistorialActiva = null
            registrarFalloDatos("historial", null)
            _estado.value = _estado.value.copy(historial = EstadoHistorialUi.Error("No se pudo cargar la actividad del hogar.", true, anterior?.eventos ?: emptyList(), anterior?.pagina ?: 0, anterior?.haySiguiente ?: false))
        }
    }

    private fun completarPaginaHistorial(
        id: Long,
        uid: String,
        hogarId: String,
        sesion: Long,
        generacion: Long,
        pagina: Int,
        fueExitosa: Boolean,
        consulta: com.google.firebase.firestore.QuerySnapshot?,
        error: Exception?
    ) {
        val activa = lecturaHistorialActiva ?: return
        if (!resultadoPaginaHistorialEsVigente(
                activa.id, activa.uid, activa.hogarId, activa.sesion, activa.generacion, auth.currentUser?.uid,
                id, uid, hogarId, sesion, generacion
            )
        ) return
        lecturaHistorialActiva = null
        if (!fueExitosa || consulta == null) {
            registrarFalloDatos("historial", error)
            val anterior = _estado.value.historial as? EstadoHistorialUi.Cargando
            _estado.value = _estado.value.copy(historial = EstadoHistorialUi.Error(mensajeParaHistorial(error as? FirebaseFirestoreException), (error as? FirebaseFirestoreException)?.let(::esErrorTransitorio) ?: true, anterior?.eventosVisibles ?: emptyList(), anterior?.pagina ?: 0, anterior?.haySiguienteVisible ?: false))
            return
        }
        val eventos = consulta.documents.map { documento ->
            (validarEventoHistorial(documento.data ?: emptyMap()) as? ResultadoEventoHistorial.Valido)?.evento
        }
        if (eventos.any { it == null }) {
            _estado.value = _estado.value.copy(historial = EstadoHistorialUi.Error("La actividad del hogar no es válida.", false))
            return
        }
        val paginaVisible = seleccionarPaginaHistorial(eventos.filterNotNull())
        if (paginaVisible.haySiguiente) {
            val cursorSiguiente = consulta.documents[EVENTOS_POR_PAGINA - 1]
            if (cursoresHistorial.size == pagina + 1) cursoresHistorial.add(cursorSiguiente)
            else cursoresHistorial[pagina + 1] = cursorSiguiente
        }
        paginaHistorialActual = pagina
        _estado.value = _estado.value.copy(
            historial = EstadoHistorialUi.Valido(paginaVisible.eventos, pagina, paginaVisible.haySiguiente)
        )
    }

    private fun registrarFalloDatos(etapa: String, error: Exception?) {
        registrarDatos(etapa, "fallo", error)
    }

    private fun registrarDatos(etapa: String, resultado: String, error: Exception?) {
        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val firestoreError = error as? FirebaseFirestoreException
        Log.e("SafeHomeDatos", "etapa=$etapa resultado=$resultado tipo=${error?.javaClass?.simpleName ?: "sin_excepcion"} codigo=${firestoreError?.code ?: "sin_codigo"} mensaje=${error?.message ?: "sin_mensaje"}")
    }

    private fun invalidarLecturaHistorial() {
        generacionHistorial++
        lecturaHistorialActiva = null
        paginaHistorialActual = 0
        cursoresHistorial.clear()
        cursoresHistorial.add(null)
    }

    private fun invalidarEscritura() {
        // El timeout visual no cancela la transacción remota; los callbacks posteriores se ignoran.
        escrituraActiva?.timeout?.cancel()
        escrituraActiva = null
    }

    fun cambiarSistema(sistemaActivo: Boolean) {
        if (escrituraActiva != null || envioComandoActivo != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val configuracion = (_estado.value.configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion ?: return
        if (!estacionPuedeCambiarSistema(perfil.rol, configuracion, false) || !transicionSistemaEsValida(configuracion, sistemaActivo)) return
        val uid = auth.currentUser?.uid ?: return
        val id = ++siguienteId
        val sesion = sesionActual
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activa = escrituraActiva
            if (activa?.id == id && activa.sesion == sesion) {
                escrituraActiva = activa.copy(expirada = true)
                _estado.value = _estado.value.copy(escritura = EstadoEscrituraUi.EsperaAgotada)
            }
        }
        val activa = EscrituraActiva(id, uid, perfil.hogarId, sesion, ObjetivoEscritura.Sistema(sistemaActivo), timeout)
        escrituraActiva = activa
        _estado.value = _estado.value.copy(escritura = EstadoEscrituraUi.Aplicando)
        try {
            val referencia = firestore.collection("hogares").document(perfil.hogarId).collection("configuracion").document("actual")
            val evento = firestore.collection("hogares").document(perfil.hogarId).collection("historial").document()
            firestore.runTransaction<Boolean> { transaccion ->
                val documento = transaccion.get(referencia)
                val actual = if (documento.exists()) (validarConfiguracionHogar(documento.data ?: emptyMap()) as? ResultadoConfiguracionHogar.Valida)?.configuracion else null
                if (!transicionSistemaEsValida(actual, sistemaActivo)) {
                    throw FirebaseFirestoreException("Configuración no disponible", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                }
                transaccion.update(referencia, mapOf("sistemaActivo" to sistemaActivo, "actualizadoEn" to FieldValue.serverTimestamp()))
                transaccion.set(evento, mapOf("tipo" to if (sistemaActivo) "SISTEMA_ACTIVADO" else "SISTEMA_DESACTIVADO", "sistemaActivo" to sistemaActivo, "creadoEn" to FieldValue.serverTimestamp()))
                sistemaActivo
            }.addOnCompleteListener { tarea -> completarCambioSistema(id, uid, perfil.hogarId, sesion, tarea.isSuccessful, tarea.exception) }
        } catch (_: Exception) {
            completarCambioSistema(id, uid, perfil.hogarId, sesion, false, null)
        }
    }

    private fun completarCambioSistema(id: Long, uid: String, hogarId: String, sesion: Long, fueExitosa: Boolean, error: Exception?) {
        val activa = escrituraActiva ?: return
        if (!respuestaEscrituraEsVigente(activa.id, activa.uid, activa.hogarId, activa.sesion, auth.currentUser?.uid, id, uid, hogarId, sesion)) return
        activa.timeout.cancel()
        if (!fueExitosa) {
            escrituraActiva = null
            _estado.value = _estado.value.copy(escritura = EstadoEscrituraUi.Error(mensajeParaEscritura(error)))
            return
        }
        escrituraActiva = activa.copy(transaccionTerminada = true)
        (_estado.value.configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.let(::confirmarEscrituraSistemaSiCorresponde)
    }

    private fun confirmarEscrituraSistemaSiCorresponde(configuracion: ConfiguracionHogar) {
        val activa = escrituraActiva ?: return
        val objetivo = (activa.objetivo as? ObjetivoEscritura.Sistema)?.sistemaActivo ?: return
        if (!activa.transaccionTerminada || configuracion.sistemaActivo != objetivo) return
        activa.timeout.cancel()
        escrituraActiva = null
        _estado.value = _estado.value.copy(escritura = EstadoEscrituraUi.Inactiva)
    }

    fun enviarReconocimientoAlerta() {
        if (envioComandoActivo != null || escrituraActiva != null) return
        val perfil = (_estado.value.perfil as? EstadoPerfilUi.Valido)?.perfil ?: return
        val estadoConfirmado = (_estado.value.hogar as? EstadoHogarUi.Valido)?.estado ?: return
        if ((_estado.value.configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo != true) return
        if (!cuidadorPuedeReconocer(perfil.rol, estadoConfirmado)) return
        val uid = auth.currentUser?.uid ?: return

        val id = ++siguienteId
        val sesion = sesionActual
        val timeout = viewModelScope.launch {
            delay(ESPERA_MAXIMA_MS)
            val activo = envioComandoActivo
            if (activo?.id == id && activo.sesion == sesion) {
                envioComandoActivo = activo.copy(expirada = true)
                _estado.value = _estado.value.copy(envioComando = EstadoEnvioComandoUi.EsperaAgotada)
            }
        }
        // La secuencia esperada se completa tras leerla atómicamente dentro de la transacción.
        val activo = EnvioComandoActivo(id, uid, perfil.hogarId, sesion, null, timeout)
        envioComandoActivo = activo
        _estado.value = _estado.value.copy(envioComando = EstadoEnvioComandoUi.Enviando)
        try {
            val referencia = firestore.collection("hogares").document(perfil.hogarId)
                .collection("comandos").document("actual")
            val evento = firestore.collection("hogares").document(perfil.hogarId)
                .collection("historial").document()
            firestore.runTransaction<Long> { transaccion ->
                val documento = transaccion.get(referencia)
                if (!documento.exists()) {
                    throw FirebaseFirestoreException("Comando no disponible", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                }
                val comando = (validarComandoReconocimiento(documento.data ?: emptyMap()) as? ResultadoComandoReconocimiento.Valido)
                    ?.comando ?: throw FirebaseFirestoreException("Comando no válido", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                val siguiente = siguienteSecuenciaComando(comando.secuencia)
                    ?: throw FirebaseFirestoreException("Secuencia no válida", FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                transaccion.update(
                    referencia,
                    mapOf(
                        "tipo" to "RECONOCER_ALERTA",
                        "secuencia" to siguiente,
                        "emitidoEn" to FieldValue.serverTimestamp()
                    )
                )
                transaccion.set(
                    evento,
                    mapOf(
                        "tipo" to "RECONOCIMIENTO_SOLICITADO",
                        "secuenciaComando" to siguiente,
                        "creadoEn" to FieldValue.serverTimestamp()
                    )
                )
                siguiente
            }.addOnCompleteListener { tarea ->
                val secuencia = if (tarea.isSuccessful) tarea.result else null
                completarEnvioComando(id, uid, perfil.hogarId, sesion, tarea.isSuccessful, tarea.exception, secuencia)
            }
        } catch (_: Exception) {
            completarEnvioComando(id, uid, perfil.hogarId, sesion, false, null, null)
        }
    }

    private fun completarEnvioComando(
        id: Long,
        uid: String,
        hogarId: String,
        sesion: Long,
        fueExitosa: Boolean,
        error: Exception?,
        secuenciaEsperada: Long?
    ) {
        val activo = envioComandoActivo ?: return
        if (!respuestaComandoEsVigente(
                activo.id,
                activo.uid,
                activo.hogarId,
                activo.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                hogarId,
                sesion
            )
        ) return
        if (!fueExitosa || secuenciaEsperada == null) {
            activo.timeout.cancel()
            envioComandoActivo = null
            _estado.value = _estado.value.copy(envioComando = EstadoEnvioComandoUi.Error(mensajeParaEnvioComando(error)))
            return
        }
        envioComandoActivo = activo.copy(secuenciaEsperada = secuenciaEsperada)
        verificarConfirmacionComando(id, uid, hogarId, sesion)
    }

    private fun verificarConfirmacionComando(id: Long, uid: String, hogarId: String, sesion: Long) {
        val activo = envioComandoActivo ?: return
        if (!respuestaComandoEsVigente(
                activo.id,
                activo.uid,
                activo.hogarId,
                activo.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                hogarId,
                sesion
            )
        ) return
        try {
            firestore.collection("hogares").document(hogarId).collection("comandos").document("actual")
                .get(Source.SERVER).addOnCompleteListener { tarea ->
                    completarConfirmacionComando(
                        id,
                        uid,
                        hogarId,
                        sesion,
                        tarea.isSuccessful,
                        if (tarea.isSuccessful) tarea.result else null,
                        tarea.exception
                    )
                }
        } catch (_: Exception) {
            completarConfirmacionComando(id, uid, hogarId, sesion, false, null, null)
        }
    }

    private fun completarConfirmacionComando(
        id: Long,
        uid: String,
        hogarId: String,
        sesion: Long,
        fueExitosa: Boolean,
        documento: com.google.firebase.firestore.DocumentSnapshot?,
        error: Exception?
    ) {
        val activo = envioComandoActivo ?: return
        if (!respuestaComandoEsVigente(
                activo.id,
                activo.uid,
                activo.hogarId,
                activo.sesion,
                auth.currentUser?.uid,
                id,
                uid,
                hogarId,
                sesion
            )
        ) return
        if (!fueExitosa || documento == null || !documento.exists()) {
            activo.timeout.cancel()
            envioComandoActivo = null
            _estado.value = _estado.value.copy(
                envioComando = EstadoEnvioComandoUi.Error(mensajeParaEnvioComando(error))
            )
            return
        }
        val comando = (validarComandoReconocimiento(documento.data ?: emptyMap()) as? ResultadoComandoReconocimiento.Valido)
            ?.comando
        val secuenciaEsperada = activo.secuenciaEsperada
        if (comando == null || secuenciaEsperada == null || !confirmacionComandoEsEsperada(secuenciaEsperada, comando)) {
            activo.timeout.cancel()
            envioComandoActivo = null
            _estado.value = _estado.value.copy(
                envioComando = EstadoEnvioComandoUi.Error("No se pudo confirmar la solicitud enviada.")
            )
            return
        }
        activo.timeout.cancel()
        envioComandoActivo = null
        _estado.value = _estado.value.copy(envioComando = EstadoEnvioComandoUi.Confirmado)
    }

    private fun invalidarEnvioComando() {
        // El timeout visual no cancela la transacción ni la lectura remota; sus callbacks se descartan.
        envioComandoActivo?.timeout?.cancel()
        envioComandoActivo = null
    }

    private fun finalizarEliminacionExitosa() {
        solicitudActiva?.timeout?.cancel()
        solicitudActiva = null
        solicitudRecuperacionActiva?.timeout?.cancel()
        solicitudRecuperacionActiva = null
        registroEstacionActivo?.timeout?.cancel()
        registroEstacionActivo = null
        registroMovilActivo?.timeout?.cancel()
        registroMovilActivo = null
        operacionCuentaActiva?.timeout?.cancel()
        operacionCuentaActiva = null
        aprobacionMovilActiva = null
        desvinculacionMovilActiva = null
        lecturaPerfilActiva?.timeout?.cancel()
        lecturaPerfilActiva = null
        eliminarListenerEstado()
        eliminarListenerConfiguracion()
        invalidarPresencia()
        invalidarLecturaMoviles()
        invalidarEscritura()
        eliminarListenerComando()
        invalidarEnvioComando()
        invalidarLecturaHistorial()
        listenerPerfilMovil?.remove()
        listenerPerfilMovil = null
        lecturaCodigoVinculacionActiva = null
        notificadorAlertas.reiniciarSesion()
        solicitudPermisoMostrada = false
        sesionActual++
        auth.signOut()
        _estado.value = estadoTrasCuentaEliminada()
        registrarEliminacion("estado_exito_publicado", true)
    }

    fun volverAlInicioTrasEliminacion() {
        if (_estado.value.eliminacionCuenta !is EstadoEliminacionCuentaUi.Exitosa) return
        _estado.value = estadoTrasDescartarCuentaEliminada()
        registrarEliminacion("volver_inicio", true)
    }

    private fun registrarEliminacion(etapa: String, exitosa: Boolean, error: Exception? = null) {
        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val firestoreError = error as? FirebaseFirestoreException
        val authError = error as? FirebaseAuthException
        Log.d("SafeHomeCuenta", "etapa=$etapa exito=$exitosa tipo=${error?.javaClass?.simpleName ?: "sin_excepcion"} codigo=${firestoreError?.code ?: authError?.errorCode ?: "sin_codigo"}")
    }

    fun cerrarSesion() {
        if (solicitudActiva != null) return
        val lectura = lecturaPerfilActiva
        if (lectura != null && !lectura.expirada) return
        lectura?.timeout?.cancel()
        lecturaPerfilActiva = null
        eliminarListenerEstado()
        eliminarListenerConfiguracion()
        invalidarPresencia()
        invalidarLecturaMoviles()
        invalidarEscritura()
        eliminarListenerComando()
        invalidarEnvioComando()
        invalidarLecturaHistorial()
        listenerPerfilMovil?.remove()
        listenerPerfilMovil = null
        notificadorAlertas.reiniciarSesion()
        solicitudPermisoMostrada = false
        sesionActual++
        auth.signOut()
        _estado.value = EstadoSesionUi(haySesion = false)
    }

    override fun onCleared() {
        registroEstacionActivo?.timeout?.cancel()
        registroEstacionActivo = null
        lecturaCodigoVinculacionActiva = null
        eliminarListenerEstado()
        eliminarListenerConfiguracion()
        invalidarPresencia()
        invalidarLecturaMoviles()
        invalidarEscritura()
        eliminarListenerComando()
        invalidarEnvioComando()
        invalidarLecturaHistorial()
        listenerPerfilMovil?.remove()
        listenerPerfilMovil = null
        notificadorAlertas.reiniciarSesion()
        super.onCleared()
    }

    private fun mensajeParaLogin(error: Exception?): String = when (error) {
        is FirebaseNetworkException -> "No se pudo conectar. Revisa tu conexión e inténtalo de nuevo."
        is FirebaseTooManyRequestsException -> "Demasiados intentos. Espera un momento antes de volver a intentar."
        else -> "No se pudo iniciar sesión. Revisa tu correo y contraseña."
    }

    private fun mensajeParaRecuperacion(error: Exception?): String = when (error) {
        is FirebaseNetworkException -> "No se pudo conectar. Inténtalo nuevamente más tarde."
        is FirebaseTooManyRequestsException -> "No se pudo procesar la solicitud ahora. Inténtalo más tarde."
        else -> "No se pudo procesar la solicitud. Inténtalo más tarde."
    }

    private fun mensajeParaPerfil(error: Exception?): String = when ((error as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se pudo verificar la sesión. Revisa tu conexión e inténtalo nuevamente."
        else -> "No se pudo verificar la sesión. Inténtalo nuevamente."
    }

    private fun mensajeParaEstado(error: FirebaseFirestoreException): String = when (error.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se puede verificar el estado actual del hogar."
        else -> "No se pudo comprobar el estado actual del hogar."
    }

    private fun esErrorTransitorio(error: FirebaseFirestoreException): Boolean = when (error.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> true
        else -> false
    }

    private fun mensajeParaEscritura(error: Exception?): String = when ((error as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se pudo aplicar ni confirmar el cambio."
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "No tienes permiso para aplicar este cambio."
        else -> "No se pudo aplicar el cambio."
    }

    private fun mensajeParaComando(error: FirebaseFirestoreException): String = when (error.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se puede verificar la última solicitud."
        else -> "No se pudo comprobar la última solicitud."
    }

    private fun mensajeParaEnvioComando(error: Exception?): String = when ((error as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se pudo enviar ni confirmar la solicitud."
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "No tienes permiso para enviar esta solicitud."
        else -> "No se pudo enviar la solicitud."
    }

    private fun mensajeParaHistorial(error: FirebaseFirestoreException?): String = when (error?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED -> "No se puede verificar la actividad del hogar."
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "No tienes permiso para consultar la actividad del hogar."
        else -> "No se pudo cargar la actividad del hogar."
    }
}
