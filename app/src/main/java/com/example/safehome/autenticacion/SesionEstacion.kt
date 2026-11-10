package com.example.safehome.autenticacion

import com.example.safehome.*
import com.example.safehome.iot.Lectura
import com.example.safehome.iot.SesionSupervision
import com.example.safehome.iot.Suscripcion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RepositorioCuentas {
    val uidActual: String?
    fun observarAuth(recibir: (String?) -> Unit): Suscripcion
    fun observarPerfil(uid: String, recibir: (Lectura<ResultadoPerfil>) -> Unit): Suscripcion
    fun iniciar(correo: String, clave: String, terminar: (String?) -> Unit)
    fun crear(correo: String, clave: String, terminar: (String?, String?) -> Unit)
    fun prepararRegistro(uid: String, nombre: String, terminar: (String?) -> Unit)
    fun recuperar(correo: String, terminar: (String?) -> Unit)
    fun cerrarSesion()
}

data class EstadoSesionUi(val uid: String? = null, val perfil: PerfilUsuario? = null,
    val comprobando: Boolean = false, val ocupado: Boolean = false, val mensaje: String? = null,
    val registroPendiente: Boolean = false)

/** Auth y aprobación de perfil separados. Nunca autoriza roles ni perfiles de caché. */
class SesionEstacion(private val cuentas: RepositorioCuentas,
    private val cambiarSupervision: (SesionSupervision?) -> Unit) {
    private val mutable = MutableStateFlow(EstadoSesionUi())
    val estado = mutable.asStateFlow()
    private var perfilListener: Suscripcion? = null
    private var authListener: Suscripcion? = null
    private var generacion = 0L
    private var operacion = 0L
    private var enVuelo = false
    private var registrando = false
    private var permitirAuth = true
    private var cerrado = false
    private var pendiente: Pair<String, String>? = null

    init { authListener = cuentas.observarAuth(::cambioAuth) }

    private fun publicar(nuevo: EstadoSesionUi) {
        val previo = mutable.value
        fun EstadoSesionUi.contexto() = perfil?.let { p -> uid?.let { SesionSupervision(it, p.hogarId) } }
        if (previo.contexto() != nuevo.contexto()) cambiarSupervision(nuevo.contexto())
        mutable.value = nuevo
    }

    private fun cancelarPerfil() {
        generacion++
        perfilListener?.cancelar()
        perfilListener = null
    }

    @Synchronized private fun cambioAuth(uid: String?) {
        if (cerrado || registrando) return
        if (!permitirAuth && uid != null) { cuentas.cerrarSesion(); return }
        cancelarPerfil()
        if (uid == null) {
            publicar(EstadoSesionUi(ocupado = enVuelo, mensaje = mutable.value.mensaje))
            return
        }
        if (pendiente?.first == uid) {
            publicar(EstadoSesionUi(uid = uid, ocupado = enVuelo, registroPendiente = true,
                mensaje = "La cuenta Auth existe, pero falta preparar el perfil/hogar. No se eliminó ningún dato."))
            return
        }
        publicar(EstadoSesionUi(uid = uid, comprobando = true, ocupado = enVuelo))
        val token = generacion
        val suscripcion = cuentas.observarPerfil(uid) { resultado -> recibirPerfil(token, uid, resultado) }
        if (generacion == token && cuentas.uidActual == uid) perfilListener = suscripcion else suscripcion.cancelar()
    }

    @Synchronized private fun recibirPerfil(token: Long, uid: String, lectura: Lectura<ResultadoPerfil>) {
        if (cerrado || token != generacion || cuentas.uidActual != uid || !permitirAuth) return
        val base = EstadoSesionUi(uid = uid, ocupado = enVuelo)
        publicar(when (lectura) {
            is Lectura.Dato -> if (lectura.desdeCache) base.copy(comprobando = true,
                mensaje = "Esperando confirmación del perfil desde el servidor.") else when (val perfil = lectura.valor) {
                is ResultadoPerfil.Valido -> base.copy(uid = uid, perfil = perfil.perfil)
                ResultadoPerfil.RolNoAdmitido -> base.copy(mensaje = "Acceso rechazado: esta versión solo admite cuentas Estación. Tus datos se conservan.")
                ResultadoPerfil.Invalido -> base.copy(mensaje = "El perfil Estación está inactivo o no es válido. No se modificaron sus datos.")
            }
            Lectura.Cargando -> base.copy(comprobando = true)
            Lectura.SinDatos -> base.copy(mensaje = "No existe un perfil de Estación para esta cuenta. Solicita revisión; no se creará automáticamente.")
            Lectura.Invalida -> base.copy(mensaje = "El perfil no es válido.")
            is Lectura.Error -> base.copy(mensaje = lectura.mensaje)
        })
    }

    @Synchronized fun iniciar(correo: String, clave: String): ErroresFormulario {
        val errores = validarFormularioInicio(correo, clave)
        if (!errores.esValido || enVuelo || cerrado || cuentas.uidActual != null) return errores
        permitirAuth = true
        val token = ++operacion
        enVuelo = true
        publicar(EstadoSesionUi(ocupado = true))
        cuentas.iniciar(normalizarCorreo(correo), clave) { error -> finalizarInicio(token, error) }
        return errores
    }

    @Synchronized private fun finalizarInicio(token: Long, error: String?) {
        enVuelo = false
        if (cerrado || token != operacion) { if (!cerrado) publicar(mutable.value.copy(ocupado = false)); return }
        if (error != null) publicar(EstadoSesionUi(mensaje = error)) else cambioAuth(cuentas.uidActual)
    }

    @Synchronized fun registrar(nombre: String, correo: String, clave: String, confirmacion: String): ErroresRegistroEstacion {
        val limpio = normalizarNombreVisible(nombre)
        val errores = validarRegistroEstacion(limpio, correo, clave, confirmacion)
        if (!errores.esValido || enVuelo || cerrado || cuentas.uidActual != null) return errores
        permitirAuth = true
        registrando = true
        enVuelo = true
        val token = ++operacion
        publicar(EstadoSesionUi(ocupado = true))
        cuentas.crear(normalizarCorreo(correo), clave) { uid, error -> completarCreacion(token, limpio, uid, error) }
        return errores
    }

    @Synchronized private fun completarCreacion(token: Long, nombre: String, uid: String?, error: String?) {
        if (cerrado || token != operacion) {
            registrando = false
            enVuelo = false
            if (!cerrado) { if (!permitirAuth) cuentas.cerrarSesion(); publicar(mutable.value.copy(ocupado = false)) }
            return
        }
        if (error != null || uid == null || cuentas.uidActual != uid) {
            registrando = false
            enVuelo = false
            publicar(EstadoSesionUi(uid = cuentas.uidActual,
                mensaje = error ?: "La sesión de registro cambió. No se preparó ningún hogar."))
        } else {
            pendiente = uid to nombre
            preparar(token, uid, nombre)
        }
    }

    private fun preparar(token: Long, uid: String, nombre: String) {
        cuentas.prepararRegistro(uid, nombre) { error -> completarPreparacion(token, error) }
    }

    @Synchronized private fun completarPreparacion(token: Long, error: String?) {
        enVuelo = false
        registrando = false
        if (cerrado || token != operacion) { if (!cerrado) publicar(mutable.value.copy(ocupado = false)); return }
        if (error == null) pendiente = null
        cambioAuth(cuentas.uidActual)
        if (error != null) publicar(mutable.value.copy(mensaje = "$error La cuenta Auth se conserva; puedes reintentar preparar el hogar."))
    }

    @Synchronized fun reintentar() {
        if (enVuelo || cerrado) return
        val registro = pendiente
        if (registro != null && cuentas.uidActual == registro.first) {
            enVuelo = true
            registrando = true
            publicar(mutable.value.copy(ocupado = true))
            preparar(++operacion, registro.first, registro.second)
        } else cambioAuth(cuentas.uidActual)
    }

    @Synchronized fun recuperar(correo: String): String? {
        val error = validarCorreoRecuperacion(correo)
        if (error != null || enVuelo || cerrado) return error
        enVuelo = true
        val token = ++operacion
        publicar(mutable.value.copy(ocupado = true, mensaje = null))
        cuentas.recuperar(normalizarCorreo(correo)) { resultado -> completarRecuperacion(token, resultado) }
        return null
    }

    @Synchronized private fun completarRecuperacion(token: Long, error: String?) {
        enVuelo = false
        if (cerrado) return
        publicar(mutable.value.copy(ocupado = false, mensaje = if (token == operacion)
            error ?: "Si existe una cuenta compatible, recibirás instrucciones para restablecer tu contraseña." else mutable.value.mensaje))
    }

    @Synchronized fun cerrarSesion() {
        operacion++
        permitirAuth = false
        pendiente = null
        cancelarPerfil()
        publicar(EstadoSesionUi(ocupado = enVuelo))
        cuentas.cerrarSesion()
    }

    @Synchronized fun cerrar() {
        cerrado = true
        operacion++
        cancelarPerfil()
        authListener?.cancelar()
        cambiarSupervision(null)
    }
}
