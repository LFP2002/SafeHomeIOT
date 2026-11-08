package com.example.safehome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.safehome.ui.theme.SafeHomeCoral
import com.example.safehome.ui.theme.SafeHomeCritical
import com.example.safehome.ui.theme.SafeHomeInk
import com.example.safehome.ui.theme.SafeHomeLilac
import com.example.safehome.ui.theme.SafeHomeMuted
import com.example.safehome.ui.theme.SafeHomeSuccess
import com.example.safehome.ui.theme.SafeHomeViolet

@Composable
fun TarjetaDispositivoPortatil(controlSesion: ControlSesion) {
    val estado by controlSesion.estadoHardwarePortatil.collectAsState()
    var selector by remember { mutableStateOf(false) }
    val propietario = LocalLifecycleOwner.current
    DisposableEffect(propietario, controlSesion) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) controlSesion.listarDispositivosUsb()
        }
        propietario.lifecycle.addObserver(observador)
        controlSesion.listarDispositivosUsb()
        onDispose { propietario.lifecycle.removeObserver(observador) }
    }
    val ocupado = estado.conectado || estado.conexion == ConexionUsbPortatil.ABRIENDO || estado.solicitandoPermiso
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dispositivo portátil", style = MaterialTheme.typography.titleLarge, color = SafeHomeInk)
            Text(when (estado.conexion) {
                ConexionUsbPortatil.USB_NO_DISPONIBLE -> "USB OTG no disponible"
                ConexionUsbPortatil.SIN_DISPOSITIVO -> "No hay dispositivo conectado"
                ConexionUsbPortatil.PERMISO_REQUERIDO -> "Permiso USB requerido"
                ConexionUsbPortatil.ABRIENDO -> "Conectando…"
                ConexionUsbPortatil.CONECTADO_NO_VERIFICADO -> "Conectado · verificando dispositivo"
                ConexionUsbPortatil.CONECTADO_VERIFICADO -> "Dispositivo SafeHome conectado"
                ConexionUsbPortatil.DESCONECTADO -> "Dispositivo portátil desconectado."
                ConexionUsbPortatil.ERROR -> "No se pudo mantener la conexión USB"
            }, color = SafeHomeViolet)
            if (estado.verificado) {
                Text("Dispositivo · ${estado.idSafeHome}", style = MaterialTheme.typography.titleMedium)
                Text("WiFi del hogar · ${textoRedPortatil(estado.wifi)}")
                Text("Señal WiFi · ${estado.rssi?.let { "$it dBm" } ?: "No disponible"}")
                Text("MQTT seguro · ${textoRedPortatil(estado.mqtt)}")
                Text("Heartbeat · ${when (estado.heartbeat) {
                    HeartbeatPortatil.ACTIVO -> "Activo"
                    HeartbeatPortatil.INACTIVO -> "Inactivo"
                    HeartbeatPortatil.DESCONOCIDO -> "No disponible"
                }}")
                Text("Este diagnóstico corresponde al dispositivo portátil; la presencia digital de la app se muestra por separado.",
                    style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
            }
            estado.error?.let { Text(it, color = SafeHomeCritical) }
            if (!ocupado && estado.conexion != ConexionUsbPortatil.USB_NO_DISPONIBLE) {
                Button(onClick = { controlSesion.listarDispositivosUsb(); selector = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SafeHomeCoral, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()) { Text("Conectar dispositivo") }
            }
            if (ocupado) TextButton(onClick = controlSesion::desconectarPortatil) { Text("Desconectar", color = SafeHomeViolet) }
            Button(onClick = controlSesion::probarConexionPortatil, enabled = estado.conectado && estado.operacion == null,
                colors = ButtonDefaults.buttonColors(containerColor = SafeHomeLilac, contentColor = SafeHomeViolet),
                modifier = Modifier.fillMaxWidth()) { Text("Probar comunicación") }
            TextButton(onClick = controlSesion::actualizarEstadoPortatil, enabled = estado.verificado && estado.operacion == null) {
                Text("Actualizar estado")
            }
            if (estado.operacion?.comando == ComandoPortatil.STATUS) Text("Actualizando diagnóstico…", color = SafeHomeMuted)
            when (estado.prueba) {
                PruebaPortatil.ESPERANDO -> Text("Esperando respuesta…", color = SafeHomeMuted)
                PruebaPortatil.RESPUESTA_VALIDA -> Text("Respuesta válida del dispositivo recibida.", color = SafeHomeSuccess)
                PruebaPortatil.SIN_RESPUESTA -> Text("No se confirmó la comunicación.", color = SafeHomeMuted)
                PruebaPortatil.SIN_PROBAR -> Unit
            }
        }
    }
    if (selector) AlertDialog(onDismissRequest = { selector = false }, title = { Text("Dispositivo portátil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (estado.dispositivos.isEmpty()) Text("Conecta el ESP32 al teléfono mediante USB OTG. No se detectaron dispositivos compatibles.")
                estado.dispositivos.forEachIndexed { indice, dispositivo ->
                    TextButton(onClick = { selector = false; controlSesion.solicitarConexionPortatil(dispositivo.clave) }) {
                        Text("${indice + 1}. ${dispositivo.nombre}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { selector = false }) { Text("Cerrar") } })
}

private fun textoRedPortatil(estado: ConexionRedPortatil): String = when (estado) {
    ConexionRedPortatil.CONECTADO -> "Conectado"
    ConexionRedPortatil.DESCONECTADO -> "Desconectado"
    ConexionRedPortatil.DESCONOCIDA -> "No disponible"
}
