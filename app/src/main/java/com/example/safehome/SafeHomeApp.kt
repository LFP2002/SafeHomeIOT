package com.example.safehome

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import com.example.safehome.ui.theme.SafeHomeBlue
import com.example.safehome.ui.theme.SafeHomeCanvas
import com.example.safehome.ui.theme.SafeHomeCoral
import com.example.safehome.ui.theme.SafeHomeCritical
import com.example.safehome.ui.theme.SafeHomeInk
import com.example.safehome.ui.theme.SafeHomeLilac
import com.example.safehome.ui.theme.SafeHomeMuted
import com.example.safehome.ui.theme.SafeHomeOutline
import com.example.safehome.ui.theme.SafeHomeSuccess
import com.example.safehome.ui.theme.SafeHomeSoftSurface
import com.example.safehome.ui.theme.SafeHomeTheme
import com.example.safehome.ui.theme.SafeHomeViolet
import com.example.safehome.ui.theme.SafeHomeWarning

@Composable
fun SafeHomeApp(controlSesion: ControlSesion) {
    var mostrarLogin by rememberSaveable { mutableStateOf(false) }
    var mostrarRegistro by rememberSaveable { mutableStateOf(false) }
    var mostrarRegistroMovil by rememberSaveable { mutableStateOf(false) }
    var reinicioFormulario by rememberSaveable { mutableStateOf(0) }
    val estado by controlSesion.estado.collectAsState()
    val presenciaServicio by ServicioPresenciaMovil.estado.collectAsState()
    val lanzadorPermisoNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { controlSesion.descartarSolicitudPermisoNotificaciones() }

    when {
        estado.eliminacionCuenta is EstadoEliminacionCuentaUi.Exitosa -> PantallaCuentaEliminada {
            controlSesion.volverAlInicioTrasEliminacion()
            reinicioFormulario++
            mostrarLogin = false
            mostrarRegistro = false
            mostrarRegistroMovil = false
        }
        estado.haySesion && estado.perfil is EstadoPerfilUi.Valido -> {
            val perfil = (estado.perfil as EstadoPerfilUi.Valido).perfil
            if (perfil.rol in setOf(RolUsuario.MOVIL_DESVINCULADO, RolUsuario.MOVIL_RECHAZADO)) PantallaMovilDesvinculado(
                nombre = perfil.nombreVisible,
                fueRechazada = perfil.rol == RolUsuario.MOVIL_RECHAZADO,
                operacion = estado.operacionMovil,
                onVincular = controlSesion::solicitarNuevaVinculacion,
                onEliminar = controlSesion::eliminarCuentaMovilDesvinculado,
                onCerrarSesion = { controlSesion.cerrarSesion(); reinicioFormulario++; mostrarLogin = true }
            ) else PantallaPerfilValido(
                perfil = perfil,
                estadoHogar = estado.hogar ?: EstadoHogarUi.Cargando,
                configuracion = estado.configuracion ?: EstadoConfiguracionHogarUi.Cargando,
                escritura = estado.escritura,
                comando = estado.comando,
                envioComando = estado.envioComando,
                historial = estado.historial,
                correoCuenta = estado.correoCuenta,
                codigoVinculacion = estado.codigoVinculacion,
                nombreHogar = estado.nombreHogar,
                moviles = estado.moviles,
                listaMoviles = estado.listaMoviles,
                solicitudes = estado.solicitudes,
                presenciasMoviles = estado.presenciasMoviles,
                presenciasUi = estado.presenciasUi,
                presenciaPropia = presenciaServicio,
                desfaseHoraServidor = estado.desfaseHoraServidor,
                onReintentar = controlSesion::reintentarEstadoHogar,
                onReintentarConfiguracion = controlSesion::reintentarConfiguracionHogar,
                onReintentarComando = controlSesion::reintentarComando,
                onSeleccionarActividad = controlSesion::seleccionarActividad,
                onActualizarHistorial = controlSesion::actualizarHistorial,
                onPaginaAnterior = controlSesion::paginaHistorialAnterior,
                onPaginaSiguiente = controlSesion::paginaHistorialSiguiente,
                onCambiarSistema = controlSesion::cambiarSistema,
                onReconocerAlerta = controlSesion::enviarReconocimientoAlerta,
                onCargarMoviles = controlSesion::cargarMoviles,
                onCargarSolicitudes = controlSesion::cargarSolicitudes,
                onAprobarMovil = controlSesion::aprobarMovil,
                onRechazarMovil = controlSesion::rechazarMovil,
                onDesvincularMovil = controlSesion::desvincularMovil,
                operacionMovil = estado.operacionMovil,
                onEliminarEstacion = controlSesion::eliminarCuentaEstacion,
                onEliminarMovil = controlSesion::eliminarCuentaMovil,
                onCerrarSesion = {
                    controlSesion.cerrarSesion()
                    reinicioFormulario++
                    mostrarLogin = true
                }
            )
        }
        estado.haySesion && estado.perfil is EstadoPerfilUi.EliminacionPendiente -> {
            val perfil = (estado.perfil as EstadoPerfilUi.EliminacionPendiente).perfil
            PantallaEliminacionPendienteEstacion(
                nombre = perfil.nombreVisible,
                operacion = estado.operacionMovil,
                onReintentar = controlSesion::reintentarEliminacionCuentaEstacion,
                onCerrarSesion = {
                    controlSesion.cerrarSesion()
                    reinicioFormulario++
                    mostrarLogin = true
                }
            )
        }
        estado.haySesion && estado.perfil is EstadoPerfilUi.Error -> {
            val error = estado.perfil as EstadoPerfilUi.Error
            PantallaErrorPerfil(
                mensaje = error.mensaje,
                puedeReintentar = error.puedeReintentar,
                onReintentar = controlSesion::reintentarPerfil,
                onCerrarSesion = {
                    controlSesion.cerrarSesion()
                    reinicioFormulario++
                    mostrarLogin = true
                }
            )
        }
        estado.haySesion && estado.vinculacionPendiente is EstadoVinculacionPendienteUi.EsperandoAprobacion -> PantallaEstadoAcceso(
            titulo = "Esperando aprobación de la Estación",
            detalle = "Tu solicitud fue enviada. Podrás acceder cuando la Estación la apruebe.",
            cierre = { controlSesion.cerrarSesion(); reinicioFormulario++; mostrarLogin = true }
        )
        estado.haySesion && estado.vinculacionPendiente is EstadoVinculacionPendienteUi.Rechazada -> PantallaEstadoAcceso(
            titulo = "Vinculación rechazada",
            detalle = "La Estación no aprobó la vinculación. La cuenta se eliminará cuando sea posible; puede ser necesario volver a iniciar sesión.",
            cierre = { controlSesion.cerrarSesion(); reinicioFormulario++; mostrarLogin = true }
        )
        estado.haySesion -> PantallaComprobandoPerfil()
        mostrarRegistroMovil -> PantallaRegistroMovil(controlSesion, reinicioFormulario) { mostrarRegistroMovil = false; mostrarLogin = true }
        mostrarRegistro -> PantallaRegistroEstacion(controlSesion, reinicioFormulario) {
            mostrarRegistro = false
            mostrarLogin = true
        }
        mostrarLogin -> PantallaLogin(controlSesion, reinicioFormulario, onCrearEstacion = {
            mostrarRegistro = true
            mostrarLogin = false
        }, onRegistrarMovil = { mostrarRegistroMovil = true; mostrarLogin = false }) { mostrarLogin = false }
        else -> PantallaBienvenida { mostrarLogin = true }
    }
    if (estado.solicitarPermisoNotificaciones && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = controlSesion::descartarSolicitudPermisoNotificaciones,
            title = { Text("Alertas del hogar") },
            text = { Text("Permite notificaciones para avisarte cuando cambie el estado del hogar mientras SafeHome está abierta.") },
            confirmButton = {
                TextButton(onClick = {
                    controlSesion.descartarSolicitudPermisoNotificaciones()
                    lanzadorPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Permitir") }
            },
            dismissButton = {
                TextButton(onClick = controlSesion::descartarSolicitudPermisoNotificaciones) { Text("Ahora no") }
            }
        )
    }
}

@Composable
private fun AyudaContrasenaNueva(clave: String, error: String?) {
    error?.let { Text(it) }
    Text("${clave.length} / $LONGITUD_MAXIMA_CONTRASENA")
}

@Composable
private fun ContadorContrasena(clave: String, error: String?) {
    error?.let { Text(it) }
    Text("${clave.length} / $LONGITUD_MAXIMA_CONTRASENA")
}

@Composable
private fun PantallaEliminacionPendienteEstacion(
    nombre: String,
    operacion: EstadoOperacionMovilUi,
    onReintentar: (String) -> Unit,
    onCerrarSesion: () -> Unit
) {
    var clave by remember { mutableStateOf("") }
    val ocupado = operacion is EstadoOperacionMovilUi.EnCurso
    PantallaIntermediaCentrada(ime = true) {
        WhiteCard {
            Text("Completa la eliminación", style = MaterialTheme.typography.headlineSmall)
            Text("$nombre, el acceso al hogar ya fue retirado. Confirma tu contraseña para eliminar definitivamente esta cuenta.", color = SafeHomeMuted)
            OutlinedTextField(
                value = clave,
                onValueChange = { clave = limitarLongitudContrasena(it) },
                label = { Text("Contraseña actual") },
                singleLine = true,
                enabled = !ocupado,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            BotonCoral(
                texto = "Eliminar mi cuenta",
                onClick = { onReintentar(clave); clave = "" },
                enabled = clave.isNotEmpty() && !ocupado,
                cargando = ocupado
            )
            TextButton(
                onClick = onCerrarSesion,
                enabled = !ocupado,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Cerrar sesión", color = SafeHomeBlue) }
            (operacion as? EstadoOperacionMovilUi.Error)?.let { MensajeOperacion(it.mensaje, SafeHomeCritical) }
        }
    }
}

@Composable
fun PantallaBienvenida(onComenzar: () -> Unit) {
    GradientScreen {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            MarcaSafeHome(invertida = true)
            Text("Siempre cerca.", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "Conecta tu hogar.\nAcompaña a quienes quieres.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(12.dp))
            BotonCoral("Comenzar", onComenzar)
        }
    }
}

@Composable
fun PantallaLogin(
    controlSesion: ControlSesion,
    reinicioFormulario: Int,
    onCrearEstacion: () -> Unit,
    onRegistrarMovil: () -> Unit,
    onVolver: () -> Unit
) {
    var correo by rememberSaveable(reinicioFormulario) { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var errorCorreo by remember { mutableStateOf<String?>(null) }
    var errorClave by remember { mutableStateOf<String?>(null) }
    val estado by controlSesion.estado.collectAsState()
    val foco = LocalFocusManager.current
    val esperando = estado.solicitud == EstadoSolicitudInicio.EnCurso
    val recuperando = estado.recuperacion is EstadoRecuperacionUi.EnCurso

    fun ingresar() {
        if (esperando) return
        foco.clearFocus()
        correo = normalizarCorreo(correo)
        val errores = validarFormularioInicio(correo, clave)
        errorCorreo = errores.correo
        errorClave = errores.clave
        if (errores.esValido) {
            controlSesion.iniciarSesion(correo, clave)
            clave = ""
        }
    }
    fun abandonarFormulario() {
        correo = ""
        clave = ""
        onVolver()
    }

    BackHandler { if (!esperando) abandonarFormulario() }
    GradientScreen(ime = true) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MarcaSafeHome(invertida = true, compacta = true)
            WhiteCard {
                Text("Bienvenido", style = MaterialTheme.typography.headlineSmall)
                Text("Inicia sesión para cuidar tu hogar.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
                val colores = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SafeHomeInk, unfocusedTextColor = SafeHomeInk,
                    focusedBorderColor = SafeHomeBlue, unfocusedBorderColor = SafeHomeMuted,
                    focusedLabelColor = SafeHomeBlue, unfocusedLabelColor = SafeHomeMuted,
                    cursorColor = SafeHomeBlue, errorBorderColor = SafeHomeCritical,
                    errorLabelColor = SafeHomeCritical, errorSupportingTextColor = SafeHomeCritical
                )
                OutlinedTextField(
                    value = correo,
                    onValueChange = { correo = it; errorCorreo = if (it.length > 254) "Máximo 254 caracteres." else null },
                    label = { Text("Correo electrónico") }, singleLine = true,
                    enabled = !esperando, isError = errorCorreo != null,
                    supportingText = { errorCorreo?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = limitarLongitudContrasena(it); errorClave = null },
                    label = { Text("Contraseña") }, singleLine = true,
                    enabled = !esperando, isError = errorClave != null,
                    supportingText = { errorClave?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth()
                )
                estado.mensaje?.let { MensajeOperacion(it, SafeHomeCritical) }
                when (val recuperacion = estado.recuperacion) {
                    is EstadoRecuperacionUi.Mensaje -> MensajeOperacion(recuperacion.texto, if (recuperacion.esError) SafeHomeCritical else SafeHomeSuccess)
                    else -> Unit
                }
                BotonCoral("Ingresar", ::ingresar, enabled = !esperando, cargando = esperando)
                TextButton(
                    onClick = {
                        correo = normalizarCorreo(correo)
                        errorCorreo = validarCorreoRecuperacion(correo)
                        controlSesion.recuperarContrasena(correo)
                    },
                    enabled = !recuperando,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(if (recuperando) "Enviando instrucciones…" else "¿Olvidaste tu contraseña?", color = SafeHomeBlue) }
                TextButton(onClick = onCrearEstacion, enabled = !esperando, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Crear cuenta de Estación", color = SafeHomeBlue)
                }
                TextButton(onClick = onRegistrarMovil, enabled = !esperando, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Registrar Móvil", color = SafeHomeBlue) }
                TextButton(onClick = ::abandonarFormulario, enabled = !esperando, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Volver", color = SafeHomeBlue)
                }
            }
        }
    }
}

@Composable
private fun PantallaRegistroEstacion(
    controlSesion: ControlSesion,
    reinicioFormulario: Int,
    onVolver: () -> Unit
) {
    var nombre by rememberSaveable(reinicioFormulario) { mutableStateOf("") }
    var correo by rememberSaveable(reinicioFormulario) { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var confirmacion by remember { mutableStateOf("") }
    var errores by remember { mutableStateOf(ErroresRegistroEstacion()) }
    val estado by controlSesion.estado.collectAsState()
    val pendiente = estado.registroEstacion is EstadoRegistroEstacionUi.EnCurso || estado.registroEstacion is EstadoRegistroEstacionUi.EsperaAgotada
    val foco = LocalFocusManager.current

    fun registrar() {
        if (pendiente) return
        foco.clearFocus()
        correo = normalizarCorreo(correo)
        errores = controlSesion.registrarEstacion(nombre, correo, clave, confirmacion)
        if (errores.esValido) {
            clave = ""
            confirmacion = ""
        }
    }
    BackHandler(enabled = pendiente) {}
    GradientScreen(ime = true) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MarcaSafeHome(invertida = true, compacta = true)
            WhiteCard {
                Text("Crear cuenta de Estación", style = MaterialTheme.typography.headlineSmall)
                Text("Crea tu hogar y recibe un código de vinculación.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
                val colores = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SafeHomeInk, unfocusedTextColor = SafeHomeInk,
                    focusedBorderColor = SafeHomeBlue, unfocusedBorderColor = SafeHomeMuted,
                    focusedLabelColor = SafeHomeBlue, unfocusedLabelColor = SafeHomeMuted,
                    cursorColor = SafeHomeBlue, errorBorderColor = SafeHomeCritical,
                    errorLabelColor = SafeHomeCritical, errorSupportingTextColor = SafeHomeCritical
                )
                OutlinedTextField(nombre, { nombre = it; errores = errores.copy(nombre = null) }, label = { Text("Nombre") }, singleLine = true, enabled = !pendiente, isError = errores.nombre != null, supportingText = { errores.nombre?.let { Text(it) } }, shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(correo, { correo = it; errores = errores.copy(correo = null) }, label = { Text("Correo electrónico") }, singleLine = true, enabled = !pendiente, isError = errores.correo != null, supportingText = { errores.correo?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clave, { clave = limitarLongitudContrasena(it); errores = errores.copy(clave = null) }, label = { Text("Contraseña") }, singleLine = true, enabled = !pendiente, isError = errores.clave != null, supportingText = { AyudaContrasenaNueva(clave, errores.clave) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirmacion, { confirmacion = limitarLongitudContrasena(it); errores = errores.copy(confirmacion = null) }, label = { Text("Confirmar contraseña") }, singleLine = true, enabled = !pendiente, isError = errores.confirmacion != null, supportingText = { ContadorContrasena(confirmacion, errores.confirmacion) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                when (val registro = estado.registroEstacion) {
                    is EstadoRegistroEstacionUi.Error -> MensajeOperacion(registro.mensaje, SafeHomeCritical)
                    EstadoRegistroEstacionUi.EsperaAgotada -> MensajeOperacion("Se agotó la espera. La solicitud sigue protegida mientras termina.", SafeHomeCritical)
                    else -> Unit
                }
                BotonCoral("Crear Estación", ::registrar, enabled = !pendiente, cargando = estado.registroEstacion is EstadoRegistroEstacionUi.EnCurso)
                TextButton(onClick = { nombre = ""; correo = ""; clave = ""; confirmacion = ""; onVolver() }, enabled = !pendiente, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Volver", color = SafeHomeBlue) }
            }
        }
    }
}

@Composable
private fun PantallaRegistroMovil(controlSesion: ControlSesion, reinicioFormulario: Int, onVolver: () -> Unit) {
    var nombre by rememberSaveable(reinicioFormulario) { mutableStateOf("") }
    var correo by rememberSaveable(reinicioFormulario) { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var confirmacion by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var errores by remember { mutableStateOf(ErroresRegistroMovil(ErroresRegistroEstacion())) }
    val estado by controlSesion.estado.collectAsState()
    val pendiente = estado.registroMovil is EstadoRegistroMovilUi.EnCurso || estado.registroMovil is EstadoRegistroMovilUi.EsperaAgotada
    fun registrar() {
        errores = controlSesion.registrarMovil(nombre, correo, clave, confirmacion, codigo)
        if (errores.esValido) { clave = ""; confirmacion = ""; codigo = "" }
    }
    BackHandler(enabled = pendiente) {}
    GradientScreen(ime = true) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            MarcaSafeHome(invertida = true, compacta = true)
            WhiteCard {
                Text("Registrar Móvil", style = MaterialTheme.typography.headlineSmall)
                Text("Solicita la aprobación de una Estación con su código de vinculación.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
                val colores = OutlinedTextFieldDefaults.colors(focusedTextColor = SafeHomeInk, unfocusedTextColor = SafeHomeInk, focusedBorderColor = SafeHomeBlue, unfocusedBorderColor = SafeHomeMuted, focusedLabelColor = SafeHomeBlue, unfocusedLabelColor = SafeHomeMuted, cursorColor = SafeHomeBlue, errorBorderColor = SafeHomeCritical, errorLabelColor = SafeHomeCritical, errorSupportingTextColor = SafeHomeCritical)
                OutlinedTextField(nombre, { nombre = it; errores = errores.copy(base = errores.base.copy(nombre = null)) }, label = { Text("Nombre") }, singleLine = true, enabled = !pendiente, isError = errores.base.nombre != null, supportingText = { errores.base.nombre?.let { Text(it) } }, shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(correo, { correo = it; errores = errores.copy(base = errores.base.copy(correo = null)) }, label = { Text("Correo electrónico") }, singleLine = true, enabled = !pendiente, isError = errores.base.correo != null, supportingText = { errores.base.correo?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clave, { clave = limitarLongitudContrasena(it); errores = errores.copy(base = errores.base.copy(clave = null)) }, label = { Text("Contraseña") }, singleLine = true, enabled = !pendiente, isError = errores.base.clave != null, supportingText = { AyudaContrasenaNueva(clave, errores.base.clave) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirmacion, { confirmacion = limitarLongitudContrasena(it); errores = errores.copy(base = errores.base.copy(confirmacion = null)) }, label = { Text("Confirmar contraseña") }, singleLine = true, enabled = !pendiente, isError = errores.base.confirmacion != null, supportingText = { ContadorContrasena(confirmacion, errores.base.confirmacion) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(codigo, { codigo = normalizarCodigoVinculacion(it); errores = errores.copy(codigo = null) }, label = { Text("Código de vinculación") }, singleLine = true, enabled = !pendiente, isError = errores.codigo != null, supportingText = { errores.codigo?.let { Text(it) } }, shape = RoundedCornerShape(18.dp), colors = colores, modifier = Modifier.fillMaxWidth())
                when (val registro = estado.registroMovil) { is EstadoRegistroMovilUi.Error -> MensajeOperacion(registro.mensaje, SafeHomeCritical); EstadoRegistroMovilUi.EsperaAgotada -> MensajeOperacion("Se agotó la espera. La solicitud sigue protegida mientras termina.", SafeHomeCritical); else -> Unit }
                BotonCoral("Solicitar vinculación", ::registrar, enabled = !pendiente, cargando = estado.registroMovil is EstadoRegistroMovilUi.EnCurso)
                TextButton(onClick = onVolver, enabled = !pendiente, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Volver", color = SafeHomeBlue) }
            }
        }
    }
}

@Composable
private fun PantallaComprobandoPerfil() = PantallaEstadoAcceso(
    titulo = "Comprobando acceso…",
    detalle = "Estamos verificando tu perfil.",
    cargando = true
)

@Composable
private fun PantallaCuentaEliminada(onVolverAlInicio: () -> Unit) {
    PantallaIntermediaCentrada {
        WhiteCard {
            Text("Cuenta eliminada", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tu cuenta fue eliminada correctamente.",
                color = SafeHomeMuted
            )
            BotonCoral("Volver al inicio", onVolverAlInicio)
        }
    }
}

@Composable
private fun PantallaErrorPerfil(
    mensaje: String,
    puedeReintentar: Boolean,
    onReintentar: () -> Unit,
    onCerrarSesion: () -> Unit
) = PantallaEstadoAcceso(
    titulo = "Acceso no disponible",
    detalle = mensaje,
    accion = if (puedeReintentar) "Reintentar" to onReintentar else null,
    cierre = onCerrarSesion
)

@Composable
private fun PantallaEstadoAcceso(
    titulo: String,
    detalle: String,
    cargando: Boolean = false,
    accion: Pair<String, () -> Unit>? = null,
    cierre: (() -> Unit)? = null
) {
    PantallaIntermediaCentrada {
        WhiteCard {
            if (cargando) CircularProgressIndicator(color = SafeHomeBlue, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(titulo, style = MaterialTheme.typography.headlineSmall)
            Text(detalle, style = MaterialTheme.typography.bodyLarge, color = SafeHomeMuted)
            accion?.let { BotonCoral(it.first, it.second) }
            cierre?.let {
                TextButton(onClick = it, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cerrar sesión", color = SafeHomeBlue) }
            }
        }
    }
}

@Composable
private fun PantallaMovilDesvinculado(
    nombre: String,
    fueRechazada: Boolean,
    operacion: EstadoOperacionMovilUi,
    onVincular: (String) -> Unit,
    onEliminar: (String) -> Unit,
    onCerrarSesion: () -> Unit
) {
    var codigo by remember { mutableStateOf("") }
    var mostrarEliminar by remember { mutableStateOf(false) }
    var clave by remember { mutableStateOf("") }
    val ocupado = operacion is EstadoOperacionMovilUi.EnCurso
    PantallaIntermediaCentrada(ime = true) {
        WhiteCard {
            Text(if (fueRechazada) "Vinculación no aprobada" else "Tu cuenta ya no está vinculada", style = MaterialTheme.typography.headlineSmall)
            Text(if (fueRechazada) "Puedes solicitar acceso a otra Estación o eliminar tu cuenta." else "Puedes solicitar acceso a otro hogar o eliminar tu cuenta.", color = SafeHomeMuted)
            Text("$nombre", style = MaterialTheme.typography.bodyMedium, color = SafeHomeViolet)
            OutlinedTextField(codigo, { codigo = normalizarCodigoVinculacion(it) }, label = { Text("Código de vinculación") }, singleLine = true, enabled = !ocupado, modifier = Modifier.fillMaxWidth())
            BotonCoral("Vincularme a otra Estación", { onVincular(codigo); codigo = "" }, enabled = !ocupado && codigoVinculacionEsValido(codigo), cargando = ocupado)
            TextButton(onClick = { mostrarEliminar = true }, enabled = !ocupado, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Eliminar mi cuenta", color = SafeHomeCritical) }
            TextButton(onClick = onCerrarSesion, enabled = !ocupado, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cerrar sesión", color = SafeHomeBlue) }
            (operacion as? EstadoOperacionMovilUi.Error)?.let { MensajeOperacion(it.mensaje, SafeHomeCritical) }
        }
    }
    if (mostrarEliminar) AlertDialog(
        onDismissRequest = { clave = ""; mostrarEliminar = false },
        title = { Text("¿Eliminar tu cuenta?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Esta acción es permanente. Confirma tu contraseña para eliminar tu cuenta."); OutlinedTextField(clave, { clave = limitarLongitudContrasena(it) }, label = { Text("Contraseña actual") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) } },
        confirmButton = { TextButton(onClick = { onEliminar(clave); clave = ""; mostrarEliminar = false }, enabled = clave.isNotEmpty() && !ocupado) { Text("Eliminar cuenta", color = SafeHomeCritical) } },
        dismissButton = { TextButton(onClick = { clave = ""; mostrarEliminar = false }, enabled = !ocupado) { Text("Cancelar") } }
    )
}

@Composable
private fun PantallaPerfilValido(
    perfil: PerfilUsuario,
    estadoHogar: EstadoHogarUi,
    configuracion: EstadoConfiguracionHogarUi,
    escritura: EstadoEscrituraUi,
    comando: EstadoComandoUi?,
    envioComando: EstadoEnvioComandoUi,
    historial: EstadoHistorialUi?,
    correoCuenta: String?,
    codigoVinculacion: String?,
    nombreHogar: String,
    moviles: EstadoMovilesUi,
    listaMoviles: EstadoListaMovilesUi,
    solicitudes: EstadoSolicitudesUi,
    presenciasMoviles: Map<String, EstadoPresenciaMovilUi>,
    presenciasUi: List<PresenciaMovilUi>,
    presenciaPropia: EstadoPresenciaServicio,
    desfaseHoraServidor: Long,
    onReintentar: () -> Unit,
    onReintentarConfiguracion: () -> Unit,
    onReintentarComando: () -> Unit,
    onSeleccionarActividad: (Boolean) -> Unit,
    onActualizarHistorial: () -> Unit,
    onPaginaAnterior: () -> Unit,
    onPaginaSiguiente: () -> Unit,
    onCambiarSistema: (Boolean) -> Unit,
    onReconocerAlerta: () -> Unit,
    onCargarMoviles: () -> Unit,
    onCargarSolicitudes: () -> Unit,
    onAprobarMovil: (String) -> Unit,
    onRechazarMovil: (String) -> Unit,
    onDesvincularMovil: (String) -> Unit,
    operacionMovil: EstadoOperacionMovilUi,
    onEliminarEstacion: (String) -> Unit,
    onEliminarMovil: (String) -> Unit,
    onCerrarSesion: () -> Unit
) {
    var sistemaPorConfirmar by remember { mutableStateOf<Boolean?>(null) }
    var seccion by rememberSaveable { mutableStateOf(SeccionPanel.INICIO) }
    val simulador = perfil.rol.esEstacion()
    val esMovil = perfil.rol == RolUsuario.MOVIL
    val subtitulo = if (simulador) "Estación" else if (perfil.rol == RolUsuario.MOVIL) "Móvil" else "Hogar"
    val estadoConfirmado = (estadoHogar as? EstadoHogarUi.Valido)?.estado
    val envioPendiente = envioComando.estaPendiente
    BackHandler(enabled = escritura.estaPendiente || envioPendiente || operacionMovil is EstadoOperacionMovilUi.EnCurso) {}
    LaunchedEffect(seccion) { onSeleccionarActividad(seccion == SeccionPanel.HISTORIAL) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SafeHomeCanvas,
        topBar = { EncabezadoDashboard(subtitulo, nombreHogar) },
        bottomBar = {
            BarraNavegacionFija(simulador, esMovil, seccion) {
                seccion = it
            }
        }
    ) { paddingValores ->
        DashboardSurface(modifier = Modifier.padding(paddingValores), conDesplazamiento = !(simulador || esMovil)) {
            when (seccion) {
                SeccionPanel.INICIO -> {
                    if (simulador) {
                        PanelInicioSimulador(perfil, nombreHogar, if (estadoHogar is EstadoHogarUi.Valido) "Disponible" else "No disponible", configuracion, listaMoviles, historial)
                    } else if (esMovil) {
                        PanelInicioMovil(perfil, nombreHogar, presenciaPropia)
                    } else {
                        PanelInicioCuidador(estadoHogar, perfil, correoCuenta, nombreHogar) { seccion = SeccionPanel.PANEL }
                    }
                }
                SeccionPanel.PANEL -> if (simulador) PanelSimulador(configuracion, escritura, onReintentarConfiguracion, { sistemaPorConfirmar = it }, listaMoviles, presenciasUi, onCargarMoviles, onDesvincularMovil, operacionMovil) else if (perfil.rol == RolUsuario.MOVIL) PanelMonitoreoMovil(presenciaPropia) else PanelMonitoreoCuidador(estadoHogar, configuracion, estadoConfirmado, envioComando, onReintentar, onReintentarConfiguracion, onReconocerAlerta)
                SeccionPanel.HISTORIAL -> PantallaActividad(
                    historial ?: EstadoHistorialUi.Cargando(0), onActualizarHistorial, onPaginaAnterior, onPaginaSiguiente
                )
                SeccionPanel.CUENTA -> PanelCuenta(perfil, correoCuenta, codigoVinculacion, nombreHogar, solicitudes, onCargarSolicitudes, onAprobarMovil, onRechazarMovil, onCerrarSesion, operacionMovil, onEliminarEstacion, onEliminarMovil)
            }
        }
    }
    sistemaPorConfirmar?.let { activo ->
        AlertDialog(
            onDismissRequest = { sistemaPorConfirmar = null },
            title = { Text(if (activo) "¿Encender sistema?" else "¿Apagar sistema?") },
            text = { Text(if (activo) "La generación de alertas volverá a estar disponible." else "La generación de alertas quedará suspendida.") },
            confirmButton = { TextButton(onClick = { onCambiarSistema(activo); sistemaPorConfirmar = null }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { sistemaPorConfirmar = null }) { Text("Cancelar") } }
        )
    }
}

private enum class SeccionPanel { INICIO, PANEL, HISTORIAL, CUENTA }

@Composable
private fun BarraNavegacionFija(simulador: Boolean, esMovil: Boolean, seleccion: SeccionPanel, onSeleccionar: (SeccionPanel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (esMovil) {
                ItemNavegacion("Inicio", Icons.Filled.Home, seleccion == SeccionPanel.INICIO, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.INICIO) }
                ItemNavegacion("Cuenta", Icons.Filled.Person, seleccion == SeccionPanel.CUENTA, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.CUENTA) }
            } else {
                ItemNavegacion("Inicio", Icons.Filled.Home, seleccion == SeccionPanel.INICIO, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.INICIO) }
                ItemNavegacion("Panel", Icons.Filled.Visibility, seleccion == SeccionPanel.PANEL, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.PANEL) }
                ItemNavegacion("Historial", Icons.Filled.History, seleccion == SeccionPanel.HISTORIAL, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.HISTORIAL) }
                ItemNavegacion("Cuenta", Icons.Filled.Person, seleccion == SeccionPanel.CUENTA, Modifier.weight(1f)) { onSeleccionar(SeccionPanel.CUENTA) }
            }
        }
    }
}

@Composable
private fun PanelInicioMovil(perfil: PerfilUsuario, nombreHogar: String, presencia: EstadoPresenciaServicio) {
    val (titulo, detalle, color) = when (presencia) {
        EstadoPresenciaServicio.ACTIVA -> Triple("Presencia activa", "Tu dispositivo está conectado al hogar.", SafeHomeSuccess)
        EstadoPresenciaServicio.CONECTANDO -> Triple("Restableciendo conexión", "Estamos verificando la presencia de este dispositivo.", SafeHomeBlue)
        EstadoPresenciaServicio.SIN_CONEXION -> Triple("Sin conexión", "La presencia se actualizará cuando vuelva la conexión.", SafeHomeCritical)
        EstadoPresenciaServicio.ERROR -> Triple("No se pudo verificar la presencia", "Inténtalo de nuevo cuando haya conexión.", SafeHomeCritical)
        EstadoPresenciaServicio.NO_DISPONIBLE -> Triple("Presencia no disponible", "La presencia estará disponible al confirmar el acceso.", SafeHomeMuted)
    }
    EncabezadoVista("TU MÓVIL", "Hola, ${perfil.nombreVisible}", "Tu conexión personal al hogar.")
    WhiteCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(104.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(76.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Phone, contentDescription = "Estado de presencia", tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }
            Text(titulo, style = MaterialTheme.typography.headlineSmall, color = SafeHomeInk, textAlign = TextAlign.Center)
            Text(detalle, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, textAlign = TextAlign.Center)
        }
    }
    WhiteCard(titulo = "Hogar vinculado") {
        Text(nombreHogar, style = MaterialTheme.typography.titleMedium)
        Text(if (presencia == EstadoPresenciaServicio.ACTIVA) "Tu presencia se comparte con este hogar." else "La conexión se actualizará automáticamente cuando esté disponible.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
    }
}

@Composable
private fun ItemNavegacion(texto: String, icono: androidx.compose.ui.graphics.vector.ImageVector, seleccionado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (seleccionado) SafeHomeLilac else Color.Transparent,
            contentColor = if (seleccionado) SafeHomeBlue else SafeHomeMuted
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icono, contentDescription = texto, modifier = Modifier.size(20.dp))
            Text(texto, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

@Composable
private fun PantallaActividad(
    estado: EstadoHistorialUi,
    onActualizar: () -> Unit,
    onAnterior: () -> Unit,
    onSiguiente: () -> Unit
) {
    val cargando = estado is EstadoHistorialUi.Cargando
    val pagina = when (estado) {
        is EstadoHistorialUi.Cargando -> estado.pagina
        is EstadoHistorialUi.Valido -> estado.pagina
        is EstadoHistorialUi.Error -> estado.pagina
    }
    val eventos = when (estado) {
        is EstadoHistorialUi.Cargando -> estado.eventosVisibles
        is EstadoHistorialUi.Valido -> estado.eventos
        is EstadoHistorialUi.Error -> estado.eventosVisibles
    }
    val haySiguiente = when (estado) {
        is EstadoHistorialUi.Cargando -> estado.haySiguienteVisible
        is EstadoHistorialUi.Valido -> estado.haySiguiente
        is EstadoHistorialUi.Error -> estado.haySiguienteVisible
    }
    EncabezadoVista("HOGAR ASIGNADO", "Historial", "Eventos y acciones del hogar.")
    TarjetaHistorial(titulo = "Actividad", detalle = "") {
        BotonCoral("Actualizar", onActualizar, enabled = !cargando)
        when {
            estado is EstadoHistorialUi.Error -> {
                TarjetaInterna(estado.mensaje, esError = true, accion = if (estado.puedeReintentar) onActualizar else null)
                eventos.forEach { EventoActividad(it) }
            }
            eventos.isEmpty() -> TarjetaInterna(if (cargando) "Cargando actividad…" else "Aún no hay actividad registrada.", cargando = cargando)
            else -> {
                eventos.forEach { EventoActividad(it) }
                repeat((EVENTOS_POR_PAGINA - eventos.size).coerceAtLeast(0)) { Spacer(Modifier.height(80.dp)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BotonCoral("Anterior", onAnterior, enabled = !cargando && puedeIrPaginaAnterior(pagina), modifier = Modifier.weight(1f))
            Text("Página ${pagina + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.72f), textAlign = TextAlign.Center)
            BotonCoral("Siguiente", onSiguiente, enabled = !cargando && haySiguiente, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EventoActividad(evento: EventoHistorial) {
    val presencia = evento as? EventoHistorial.PresenciaMovilActualizada
    val conectado = presencia?.conectado == true
    val color = if (conectado) SafeHomeSuccess else SafeHomeCritical
    val titulo = presencia?.let { "${it.nombre} ${if (it.conectado) "conectó su Móvil" else "desconectó su Móvil"}" } ?: textoEventoHistorial(evento).first()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, SafeHomeOutline)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(36.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(if (conectado) Icons.Filled.Phone else Icons.Filled.PhoneDisabled, contentDescription = if (conectado) "Móvil conectado" else "Móvil desconectado", tint = color)
            }
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(evento.horaLocal, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
            }
        }
    }
}

@Composable
private fun PanelInicioSimulador(
    perfil: PerfilUsuario,
    nombreHogar: String,
    conexion: String,
    configuracion: EstadoConfiguracionHogarUi,
    moviles: EstadoListaMovilesUi,
    historial: EstadoHistorialUi?
) {
    EncabezadoVista("TU SISTEMA", "Hola, ${perfil.nombreVisible}", "Todo está en orden en tu hogar.")
    val cantidad = (moviles as? EstadoListaMovilesUi.Valido)?.moviles?.size
    val sistema = (configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo
    WhiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(SafeHomeLilac, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Home, contentDescription = "Hogar", tint = SafeHomeViolet) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(nombreHogar, style = MaterialTheme.typography.titleMedium); Text(if (sistema == true) "Sistema operativo" else "Estado del sistema sin confirmar", color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium) }
            Text(conexion, color = if (conexion == "Disponible") SafeHomeSuccess else SafeHomeMuted, style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TarjetaMetrica(cantidad?.toString() ?: "—", "Móviles vinculados", Modifier.weight(1f))
            TarjetaMetrica(if (sistema == true) "Activo" else "—", "Estado del sistema", Modifier.weight(1f))
        }
        Text("Actividad reciente", style = MaterialTheme.typography.titleMedium)
        val eventos = (historial as? EstadoHistorialUi.Valido)?.eventos?.take(2).orEmpty()
        if (eventos.isEmpty()) Text("Aún no hay eventos de conexión.", color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium)
        else eventos.forEach { EventoActividad(it) }
    }
}

@Composable
private fun TarjetaMetrica(valor: String, etiqueta: String, modifier: Modifier = Modifier) {
    Column(modifier.background(SafeHomeSoftSurface, RoundedCornerShape(17.dp)).padding(11.dp)) {
        Text(valor, style = MaterialTheme.typography.titleLarge, color = SafeHomeInk)
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PanelSimulador(
    configuracion: EstadoConfiguracionHogarUi,
    escritura: EstadoEscrituraUi,
    onReintentarConfiguracion: () -> Unit,
    onCambiarSistema: (Boolean) -> Unit,
    moviles: EstadoListaMovilesUi,
    presencias: List<PresenciaMovilUi>,
    onCargarMoviles: () -> Unit,
    onDesvincular: (String) -> Unit,
    operacion: EstadoOperacionMovilUi
) {
    EncabezadoVista("ESTACIÓN", "Panel", "Administra el sistema y los móviles vinculados.")
    val sistemaActivo = (configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo
    WhiteCard(containerColor = Color.White) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(70.dp).background(when (sistemaActivo) {
                true -> SafeHomeSuccess
                false -> SafeHomeMuted.copy(alpha = 0.2f)
                null -> SafeHomeLilac
            }, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Estado del sistema", tint = if (sistemaActivo == false) SafeHomeMuted else Color.White, modifier = Modifier.size(34.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(when (sistemaActivo) {
                    true -> "Sistema activo"
                    false -> "Sistema apagado"
                    null -> "Estado sin verificar"
                }, style = MaterialTheme.typography.titleLarge)
                Text(when (sistemaActivo) {
                    true -> "La estación está funcionando correctamente."
                    false -> "La generación de alertas está suspendida."
                    null -> "Esperando la confirmación del sistema."
                }, color = SafeHomeMuted)
            }
        }
    }
    EstadoConfiguracionSistema(configuracion, onReintentarConfiguracion)
    if (configuracion is EstadoConfiguracionHogarUi.Valida) {
        BotonSecundario(if (sistemaActivo == true) "Apagar sistema" else "Encender sistema", onClick = { onCambiarSistema(sistemaActivo != true) })
    }
    when (escritura) {
        EstadoEscrituraUi.Aplicando -> MensajeOperacion("Aplicando cambio…", SafeHomeMuted)
        EstadoEscrituraUi.EsperaAgotada -> MensajeOperacion("No se pudo confirmar el cambio. Puedes cerrar sesión mientras termina la operación.", SafeHomeCritical)
        is EstadoEscrituraUi.Error -> MensajeOperacion(escritura.mensaje, SafeHomeCritical)
        EstadoEscrituraUi.Inactiva -> Unit
    }
    PanelMoviles(moviles, presencias, onCargarMoviles, onDesvincular, operacion)
}

@Composable
private fun PanelInicioCuidador(estadoHogar: EstadoHogarUi, perfil: PerfilUsuario, correoCuenta: String?, nombreHogar: String, onIrMonitoreo: () -> Unit) {
    EncabezadoVista("TU SISTEMA", "Hola, ${perfil.nombreVisible}", "Todo lo que necesitas para revisar el hogar.")
    TarjetaResumenHogar(
        perfil,
        correoCuenta,
        nombreHogar,
        if (estadoHogar is EstadoHogarUi.Valido) "Disponible" else "No se puede verificar la conexión"
    )
    BotonCoral("Abrir monitoreo", onIrMonitoreo)
}

@Composable
private fun PanelMonitoreoCuidador(
    estadoHogar: EstadoHogarUi,
    configuracion: EstadoConfiguracionHogarUi,
    estadoConfirmado: EstadoHogar?,
    envioComando: EstadoEnvioComandoUi,
    onReintentar: () -> Unit,
    onReintentarConfiguracion: () -> Unit,
    onReconocerAlerta: () -> Unit
) {
    EncabezadoVista("HOGAR", "Panel", "Estado confirmado del hogar asignado.")
    EstadoConfiguracionSistema(configuracion, onReintentarConfiguracion)
    EstadoHogarPrincipal(estadoHogar, onReintentar, (configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo != false)
    if ((configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo == true && estadoConfirmado?.nivel == NivelEstadoHogar.NORMAL) {
        TarjetaSinAlertasPendientes()
    }
    ControlesCuidador(estadoConfirmado, (configuracion as? EstadoConfiguracionHogarUi.Valida)?.configuracion?.sistemaActivo == true, envioComando, onReconocerAlerta)
}

@Composable
private fun PanelCuenta(perfil: PerfilUsuario, correoCuenta: String?, codigoVinculacion: String?, nombreHogar: String, solicitudes: EstadoSolicitudesUi, onCargar: () -> Unit, onAprobar: (String) -> Unit, onRechazar: (String) -> Unit, onCerrarSesion: () -> Unit, operacion: EstadoOperacionMovilUi, onEliminarEstacion: (String) -> Unit, onEliminarMovil: (String) -> Unit) {
    var mostrarEliminar by remember { mutableStateOf(false) }
    var clave by remember { mutableStateOf("") }
    EncabezadoVista("TU CUENTA", "Configuración", "Información de acceso y del sistema.")
    WhiteCard(containerColor = Color.White) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(45.dp).background(SafeHomeLilac, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, contentDescription = "Perfil", tint = SafeHomeViolet) }
            Column { Text(perfil.nombreVisible, style = MaterialTheme.typography.titleMedium); Text(correoCuenta ?: "Correo no disponible", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        FilaResumen("Rol asignado", when {
            perfil.rol.esEstacion() -> "Estación"
            perfil.rol == RolUsuario.MOVIL -> "Móvil"
            else -> "Remoto"
        })
        FilaResumen("Hogar", nombreHogar)
        if (perfil.rol == RolUsuario.ESTACION) FilaResumen("Código de vinculación", codigoVinculacion ?: "No disponible")
    }
    if (perfil.rol.esEstacion()) SolicitudesVinculacion(solicitudes, onCargar, onAprobar, onRechazar)
    BotonSecundario("Cerrar sesión", onCerrarSesion)
    if (perfil.rol.esEstacion() || perfil.rol == RolUsuario.MOVIL) TextButton(onClick = { mostrarEliminar = true }, enabled = operacion !is EstadoOperacionMovilUi.EnCurso) { Text("Eliminar mi cuenta", color = SafeHomeCritical) }
    (operacion as? EstadoOperacionMovilUi.Error)?.let { MensajeOperacion(it.mensaje, SafeHomeCritical) }
    if (mostrarEliminar) AlertDialog(
        onDismissRequest = { clave = ""; mostrarEliminar = false },
        title = { Text(if (perfil.rol.esEstacion()) "¿Eliminar tu cuenta de Estación?" else "¿Eliminar tu cuenta?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(if (perfil.rol.esEstacion()) "Esta acción es permanente. Primero debes desvincular móviles y resolver solicitudes pendientes." else "Esta acción es permanente. Confirma tu contraseña para eliminar tu cuenta."); OutlinedTextField(clave, { clave = limitarLongitudContrasena(it) }, label = { Text("Contraseña actual") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) } },
        confirmButton = { TextButton(onClick = { if (perfil.rol.esEstacion()) onEliminarEstacion(clave) else onEliminarMovil(clave); clave = ""; mostrarEliminar = false }, enabled = clave.isNotEmpty() && operacion !is EstadoOperacionMovilUi.EnCurso) { Text("Eliminar cuenta", color = SafeHomeCritical) } },
        dismissButton = { TextButton(onClick = { clave = ""; mostrarEliminar = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun PanelMoviles(estado: EstadoListaMovilesUi, presencias: List<PresenciaMovilUi>, onCargar: () -> Unit, onDesvincular: (String) -> Unit, operacion: EstadoOperacionMovilUi) {
    var movilADesvincular by remember { mutableStateOf<PresenciaMovilUi?>(null) }
    LaunchedEffect(Unit) { onCargar() }
    EncabezadoVista("MÓVILES", "Móviles vinculados", "Presencia actual de los móviles aprobados.")
    when (estado) {
        EstadoListaMovilesUi.Inactivo, EstadoListaMovilesUi.Cargando -> TarjetaInterna("Cargando móviles…", cargando = true)
        is EstadoListaMovilesUi.Error -> TarjetaInterna(estado.mensaje, esError = true, accion = onCargar)
        is EstadoListaMovilesUi.Valido -> {
            WhiteCard(titulo = "Móviles vinculados") {
                if (estado.moviles.isEmpty()) Text("No hay móviles vinculados.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
                presencias.forEach { presencia -> FilaPresenciaMovil(presencia, { movilADesvincular = presencia }, operacion is EstadoOperacionMovilUi.EnCurso) }
            }
        }
    }
    movilADesvincular?.let { movil -> AlertDialog(
        onDismissRequest = { if (operacion !is EstadoOperacionMovilUi.EnCurso) movilADesvincular = null },
        title = { Text("¿Desvincular a ${movil.nombreVisible}?") },
        text = { Text("Perderá el acceso a este hogar. Podrá vincularse a otra Estación o eliminar su cuenta.") },
        confirmButton = { TextButton(onClick = { onDesvincular(movil.uidInterno); movilADesvincular = null }, enabled = operacion !is EstadoOperacionMovilUi.EnCurso) { Text("Desvincular", color = SafeHomeCritical) } },
        dismissButton = { TextButton(onClick = { movilADesvincular = null }, enabled = operacion !is EstadoOperacionMovilUi.EnCurso) { Text("Cancelar") } }
    ) }
}

@Composable
private fun SolicitudesVinculacion(estado: EstadoSolicitudesUi, onCargar: () -> Unit, onAprobar: (String) -> Unit, onRechazar: (String) -> Unit) {
    var solicitudARechazar by remember { mutableStateOf<SolicitudVinculacion?>(null) }
    var solicitudesEnProceso by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { onCargar() }
    WhiteCard(titulo = "Solicitudes de vinculación") {
        when (estado) {
            is EstadoSolicitudesUi.Valido -> if (estado.solicitudes.isEmpty()) Text("No hay solicitudes pendientes.", color = SafeHomeMuted) else estado.solicitudes.forEach { solicitud ->
                TarjetaInterna(solicitud.nombre)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    BotonCoral("Aceptar", { solicitudesEnProceso = solicitudesEnProceso + solicitud.uidMovil; onAprobar(solicitud.uidMovil) }, enabled = solicitud.uidMovil !in solicitudesEnProceso, modifier = Modifier.weight(1f))
                    BotonSecundario("Rechazar", { solicitudARechazar = solicitud }, enabled = solicitud.uidMovil !in solicitudesEnProceso, modifier = Modifier.weight(1f))
                }
            }
            EstadoSolicitudesUi.Cargando, EstadoSolicitudesUi.Inactivo -> TarjetaInterna("Cargando solicitudes…", cargando = true)
            is EstadoSolicitudesUi.Error -> TarjetaInterna(estado.mensaje, esError = true, accion = onCargar)
        }
    }
    solicitudARechazar?.let { solicitud ->
        AlertDialog(
            onDismissRequest = { solicitudARechazar = null },
            title = { Text("¿Rechazar vinculación?") },
            text = { Text("El Móvil no podrá acceder al hogar.") },
            confirmButton = { TextButton(onClick = { solicitudesEnProceso = solicitudesEnProceso + solicitud.uidMovil; onRechazar(solicitud.uidMovil); solicitudARechazar = null }) { Text("Rechazar") } },
            dismissButton = { TextButton(onClick = { solicitudARechazar = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun PanelMonitoreoMovil(presencia: EstadoPresenciaServicio) {
    EncabezadoVista("MÓVIL", "Panel", "Conexión de presencia del dispositivo.")
    WhiteCard(titulo = "Presencia") {
        Text(
            when (presencia) {
                EstadoPresenciaServicio.ACTIVA -> "Presencia activa"
                EstadoPresenciaServicio.CONECTANDO -> "Conectando"
                EstadoPresenciaServicio.SIN_CONEXION -> "Sin conexión"
                EstadoPresenciaServicio.ERROR, EstadoPresenciaServicio.NO_DISPONIBLE -> "Sin conexión"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (presencia == EstadoPresenciaServicio.ACTIVA) SafeHomeSuccess else SafeHomeMuted
        )
    }
}

@Composable
private fun FilaPresenciaMovil(presencia: PresenciaMovilUi, onDesvincular: () -> Unit = {}, bloqueado: Boolean = false) {
    val visual = presencia.estadoFinal
    val color = when (visual) {
        EstadoVisualPresencia.ACTIVO -> SafeHomeSuccess
        EstadoVisualPresencia.DESCONECTADO -> SafeHomeCritical
        EstadoVisualPresencia.SIN_DATOS -> SafeHomeMuted
        EstadoVisualPresencia.ERROR -> SafeHomeWarning
    }
    Card(colors = CardDefaults.cardColors(containerColor = SafeHomeLilac), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Column(Modifier.weight(1f)) { Text(presencia.nombreVisible, style = MaterialTheme.typography.bodyLarge); Text(textoEstadoVisualPresencia(estadoVisual = visual), style = MaterialTheme.typography.bodyMedium, color = color) }
            TextButton(onClick = onDesvincular, enabled = !bloqueado) { Text("Desvincular", color = SafeHomeCritical) }
        }
    }
}

@Composable
private fun EncabezadoDashboard(subtitulo: String, hogarId: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(SafeHomeBlue, SafeHomeViolet))).statusBarsPadding().padding(horizontal = 22.dp, vertical = 15.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) { Text("SH", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text("SafeHome", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("$subtitulo · $hogarId", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EncabezadoVista(etiqueta: String, titulo: String, detalle: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = SafeHomeViolet, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(titulo, style = MaterialTheme.typography.headlineSmall, color = SafeHomeInk)
        Text(detalle, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
    }
}

@Composable
private fun TarjetaResumenHogar(perfil: PerfilUsuario, correoCuenta: String?, nombreHogar: String, conexion: String) {
    WhiteCard(containerColor = Color.White) {
        Text("Hogar asignado", style = MaterialTheme.typography.titleMedium)
        Text(nombreHogar, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
        Text(correoCuenta ?: "Cuenta autorizada", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FilaResumen("Rol", if (perfil.rol.esEstacion()) "Estación" else "Remoto")
        FilaResumen("Conexión", conexion)
    }
}

@Composable
private fun FilaResumen(etiqueta: String, valor: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(SafeHomeOutline))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, modifier = Modifier.weight(1f))
            Text(valor, style = MaterialTheme.typography.bodyMedium, color = SafeHomeViolet, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1.25f))
        }
    }
}

@Composable
private fun TarjetaSinAlertasPendientes() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = SafeHomeLilac),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sin alertas pendientes", style = MaterialTheme.typography.titleMedium, color = SafeHomeViolet)
            Text("La información del hogar está confirmada por el servidor.", style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
        }
    }
}

@Composable
private fun EstadoHogarPrincipal(estado: EstadoHogarUi, onReintentar: () -> Unit, sistemaActivo: Boolean = true) {
    when (estado) {
        is EstadoHogarUi.Valido -> if (sistemaActivo) EstadoHogarConfirmado(estado.estado) else {
            TarjetaInterna("Último estado confirmado", "Puerta: ${if (estado.estado.puertaAbierta) "abierta" else "cerrada"} · Presencia: ${if (estado.estado.personaPresente) "detectada" else "sin señal"}")
        }
        EstadoHogarUi.Cargando -> TarjetaInformativa("Cargando estado del hogar…", cargando = true)
        EstadoHogarUi.EsperandoConfirmacionServidor -> TarjetaInformativa("Esperando confirmación del servidor")
        is EstadoHogarUi.Error -> TarjetaInformativa(estado.mensaje, esError = true, accion = if (estado.puedeReintentar) onReintentar else null)
    }
}

@Composable
private fun EstadoConfiguracionSistema(estado: EstadoConfiguracionHogarUi, onReintentar: () -> Unit) {
    when (estado) {
        is EstadoConfiguracionHogarUi.Valida -> if (!estado.configuracion.sistemaActivo) {
            TarjetaInterna("Sistema apagado", "La generación de alertas está suspendida.")
        }
        EstadoConfiguracionHogarUi.Cargando -> TarjetaInterna("Comprobando configuración del sistema…", cargando = true)
        EstadoConfiguracionHogarUi.EsperandoConfirmacionServidor -> TarjetaInterna("Esperando confirmación de la configuración")
        is EstadoConfiguracionHogarUi.Error -> TarjetaInterna(estado.mensaje, esError = true, accion = if (estado.puedeReintentar) onReintentar else null)
    }
}

@Composable
private fun EstadoHogarConfirmado(estado: EstadoHogar) {
    val (etiqueta, color) = when (estado.nivel) {
        NivelEstadoHogar.NORMAL -> "Normal" to SafeHomeSuccess
        NivelEstadoHogar.ADVERTENCIA -> "Advertencia" to SafeHomeWarning
        NivelEstadoHogar.CRITICO -> "Crítico" to SafeHomeCritical
    }
    val simbolo = if (estado.nivel == NivelEstadoHogar.NORMAL) "✓" else "!"
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = SafeHomeViolet),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(94.dp).background(color.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(70.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text(simbolo, color = color, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("Estado del hogar", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        etiqueta,
                        color = Color.White,
                        fontSize = if (estado.nivel == NivelEstadoHogar.ADVERTENCIA) 18.sp else 25.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text("Información confirmada por servidor", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            DatosEstado(estado)
        }
    }
}

@Composable
private fun DatosEstado(estado: EstadoHogar) {
    val puerta = if (estado.puertaAbierta) "Abierta" else "Cerrada"
    val presencia = if (estado.personaPresente) "Detectada" else "Sin señal"
    BoxWithConstraints {
        val formatoEstrecho = maxWidth < 340.dp
        Column {
            if (formatoEstrecho) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DatoEstado("Puerta", puerta, Modifier.weight(1f))
                    DatoEstado("Presencia", presencia, Modifier.weight(1f))
                }
                DatoEstado("Hora", estado.horaLocal, Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DatoEstado("Puerta", puerta, Modifier.weight(1f))
                    DatoEstado("Presencia", presencia, Modifier.weight(1f))
                    DatoEstado("Hora", estado.horaLocal, Modifier.weight(0.72f))
                }
            }
        }
    }
}

@Composable
private fun DatoEstado(etiqueta: String, valor: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(10.dp)) {
        Text(etiqueta, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        Text(valor, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ControlesCuidador(estado: EstadoHogar?, sistemaActivo: Boolean, envio: EstadoEnvioComandoUi, onReconocerAlerta: () -> Unit) {
    when (envio) {
        EstadoEnvioComandoUi.Enviando -> MensajeOperacion("Enviando solicitud…", SafeHomeMuted)
        EstadoEnvioComandoUi.EsperaAgotada -> MensajeOperacion("No se pudo confirmar la solicitud. Puedes cerrar sesión mientras termina la operación.", SafeHomeCritical)
        EstadoEnvioComandoUi.Confirmado -> MensajeOperacion("Solicitud de reconocimiento enviada", SafeHomeSuccess)
        is EstadoEnvioComandoUi.Error -> MensajeOperacion(envio.mensaje, SafeHomeCritical)
        EstadoEnvioComandoUi.Inactivo -> Unit
    }
    if (sistemaActivo && estado != null && estado.nivel != NivelEstadoHogar.NORMAL) {
        BotonCoral("Reconocer alerta", onReconocerAlerta, envioReconocimientoHabilitado(RolUsuario.CUIDADOR, estado, envio.estaPendiente))
    }
}

@Composable
private fun TarjetaInformativa(texto: String, cargando: Boolean = false, esError: Boolean = false, accion: (() -> Unit)? = null) {
    WhiteCard {
        if (cargando) CircularProgressIndicator(color = SafeHomeBlue, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(texto, style = MaterialTheme.typography.titleMedium, color = if (esError) SafeHomeCritical else SafeHomeInk)
        accion?.let { BotonCoral("Reintentar", it) }
    }
}

@Composable
private fun TarjetaInterna(texto: String, detalle: String? = null, cargando: Boolean = false, esError: Boolean = false, accion: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = if (esError) SafeHomeCritical.copy(alpha = 0.08f) else SafeHomeLilac), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cargando) CircularProgressIndicator(color = SafeHomeBlue, modifier = Modifier.size(24.dp))
            Text(texto, style = MaterialTheme.typography.bodyLarge, color = if (esError) SafeHomeCritical else SafeHomeInk)
            detalle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted) }
            accion?.let { TextButton(onClick = it) { Text("Reintentar", color = SafeHomeBlue) } }
        }
    }
}

@Composable
private fun MensajeOperacion(texto: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(texto, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(13.dp))
    }
}

@Composable
private fun DashboardSurface(
    modifier: Modifier = Modifier,
    conDesplazamiento: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val contenido = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = 640.dp)
    Column(
        modifier = if (conDesplazamiento) contenido.verticalScroll(rememberScrollState()) else contenido,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun PantallaIntermediaCentrada(
    ime: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val contenedor = Modifier
        .fillMaxSize()
        .background(SafeHomeCanvas)
        .safeDrawingPadding()
        .padding(horizontal = 20.dp, vertical = 24.dp)
    Box(
        modifier = if (ime) contenedor.imePadding() else contenedor,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun GradientScreen(ime: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SafeHomeBlue, SafeHomeViolet))).safeDrawingPadding()
    Box(modifier = if (ime) modifier.imePadding() else modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun WhiteCard(
    titulo: String? = null,
    detalle: String? = null,
    containerColor: Color = SafeHomeSoftSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = SafeHomeInk),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, SafeHomeOutline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = {
            titulo?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            detalle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted) }
            content()
        })
    }
}

@Composable
private fun TarjetaHistorial(titulo: String, detalle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = SafeHomeSoftSurface, contentColor = SafeHomeInk),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            if (detalle.isNotBlank()) Text(detalle, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
            content()
        }
    }
}

@Composable
private fun BotonCoral(texto: String, onClick: () -> Unit, enabled: Boolean = true, cargando: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(25.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SafeHomeCoral, contentColor = SafeHomeInk, disabledContainerColor = SafeHomeCoral.copy(alpha = 0.45f), disabledContentColor = SafeHomeInk.copy(alpha = 0.55f))
    ) {
        if (cargando) CircularProgressIndicator(color = SafeHomeInk, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 6.dp))
        Text(if (cargando) "Ingresando…" else texto, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BotonSecundario(texto: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(25.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SafeHomeLilac, contentColor = SafeHomeViolet, disabledContainerColor = SafeHomeLilac, disabledContentColor = SafeHomeMuted),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) { Text(texto, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun MarcaSafeHome(invertida: Boolean, compacta: Boolean = false) {
    val color = if (invertida) Color.White else SafeHomeBlue
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(if (compacta) 46.dp else 64.dp).background(color.copy(alpha = if (invertida) 0.18f else 0.12f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Text("SH", color = color, style = MaterialTheme.typography.titleLarge)
        }
        Column {
            Text("SafeHome", color = color, style = if (compacta) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall)
            if (!compacta) Text("Tu hogar, siempre cerca", color = color.copy(alpha = 0.84f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7FF)
@Composable
private fun PreviewEstadoNormal() = PreviewPanel(NivelEstadoHogar.NORMAL)

@Preview(showBackground = true, backgroundColor = 0xFFF6F7FF)
@Composable
private fun PreviewEstadoAdvertencia() = PreviewPanel(NivelEstadoHogar.ADVERTENCIA)

@Preview(showBackground = true, backgroundColor = 0xFFF6F7FF)
@Composable
private fun PreviewEstadoCritico() = PreviewPanel(NivelEstadoHogar.CRITICO)

@Composable
private fun PreviewPanel(nivel: NivelEstadoHogar) {
    val estado = when (nivel) {
        NivelEstadoHogar.NORMAL -> EstadoHogar(false, true, "10:30", nivel)
        NivelEstadoHogar.ADVERTENCIA -> EstadoHogar(false, false, "10:30", nivel)
        NivelEstadoHogar.CRITICO -> EstadoHogar(true, false, "10:30", nivel)
    }
    SafeHomeTheme { DashboardSurface { EstadoHogarConfirmado(estado) } }
}
