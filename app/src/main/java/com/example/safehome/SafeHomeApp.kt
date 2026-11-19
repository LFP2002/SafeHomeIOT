package com.example.safehome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.safehome.presentacion.*
import com.example.safehome.ui.theme.*
import com.example.safehome.iot.*

/** Navegación solamente; cada pantalla y coordinador vive en su propio archivo. */
@Composable
fun SafeHomeApp(controlSesion: ControlSesion) {
    val sesion by controlSesion.estado.collectAsState()
    val panel by controlSesion.panel.collectAsState()
    val pantallas = key(sesion.uid) { rememberSaveableStateHolder() }
    val perfil = sesion.perfil
    if (perfil == null) {
        var verLocal by remember(sesion.uid) { mutableStateOf(false) }
        val uid = sesion.uid
        if (verLocal && uid != null) {
            val fuente = remember(uid) { controlSesion.historialOffline(uid) }
            val local by fuente.collectAsState(initial = Lectura.Cargando)
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
                CabeceraSafeHome("Actividad guardada")
                TextButton(onClick = { verLocal = false }) { Text("Volver al acceso") }
                pantallas.SaveableStateProvider("historial-offline:$uid") {
                    PantallaHistorial(local, {}, contexto = "offline:$uid", offline = true)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                PantallaAcceso(sesion, controlSesion::iniciarSesion, controlSesion::registrarEstacion,
                    controlSesion::recuperarContrasena, controlSesion::reintentarPerfil, controlSesion::cerrarSesion)
            }
            if (uid != null) TextButton(onClick = { verLocal = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Ver actividad guardada")
            }
        }
        return
    }
    var seleccion by rememberSaveable(sesion.uid, perfil.hogarId) { mutableStateOf(0) }
    Scaffold(containerColor = SafeHomeCanvas, bottomBar = {
        NavegacionSafeHome(seleccion) { seleccion = it }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                CabeceraSafeHome(perfil.nombreVisible)
                Box(Modifier.weight(1f)) {
                    when (seleccion) {
                        0 -> PantallaSupervision(panel, controlSesion::reintentarSupervision,
                            controlSesion::enviarComando, controlSesion::reconocerAlarma)
                        1 -> pantallas.SaveableStateProvider("historial:${sesion.uid}:${perfil.hogarId}") {
                            PantallaHistorial(panel.historial, controlSesion::reintentarSupervision,
                                contexto = "${sesion.uid}:${perfil.hogarId}", offline = !panel.mqtt.let { it?.conectado == true })
                        }
                        else -> PantallaCuenta(perfil, controlSesion::cerrarSesion)
                    }
                }
            }
        }
    }
}
