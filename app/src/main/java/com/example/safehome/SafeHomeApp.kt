package com.example.safehome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.safehome.presentacion.*
import com.example.safehome.ui.theme.*

/** Navegación solamente; cada pantalla y coordinador vive en su propio archivo. */
@Composable
fun SafeHomeApp(controlSesion: ControlSesion) {
    val sesion by controlSesion.estado.collectAsState()
    val panel by controlSesion.panel.collectAsState()
    val perfil = sesion.perfil
    if (perfil == null) {
        PantallaAcceso(sesion, controlSesion::iniciarSesion, controlSesion::registrarEstacion,
            controlSesion::recuperarContrasena, controlSesion::reintentarPerfil, controlSesion::cerrarSesion)
        return
    }
    var seleccion by rememberSaveable(sesion.uid) { mutableStateOf(0) }
    Scaffold(containerColor = SafeHomeCanvas, bottomBar = {
        NavegacionSafeHome(seleccion) { seleccion = it }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize().padding(horizontal = 18.dp)) {
                CabeceraSafeHome("Estación · ${perfil.nombreVisible}")
                Box(Modifier.weight(1f)) {
                    when (seleccion) {
                        0 -> PantallaSupervision(panel, controlSesion::reintentarSupervision)
                        1 -> PantallaHistorial(panel.historial, controlSesion::reintentarSupervision)
                        else -> PantallaCuenta(perfil, controlSesion::cerrarSesion)
                    }
                }
            }
        }
    }
}
