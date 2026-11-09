package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*

@Composable fun PantallaSupervision(panel: PanelSupervision, reintentar: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Panel de supervisión", style = MaterialTheme.typography.headlineSmall)
        Text("Conexión MQTT no equivale a presencia física. Una desconexión no demuestra que alguien salió del hogar.", color = SafeHomeMuted)
        TarjetaDispositivo("ESP32 Estación", panel.estacion)
        TarjetaDispositivo("ESP32 Portátil", panel.portatil)
        TarjetaSafeHome("Puerta y alarma") {
            ContenidoLectura(panel.fisico) { fisico ->
                Text("Puerta: " + when (fisico.puerta) { Puerta.ABIERTA -> "Abierta"; Puerta.CERRADA -> "Cerrada"; Puerta.SIN_DATOS -> "Sin datos" })
                Text("Último cambio confirmado: ${fechaHora(fisico.puertaCambiadaEn)}", style = MaterialTheme.typography.bodySmall)
                Text("Alarma: " + when (fisico.alarma) {
                    Alarma.NORMAL -> "Normal"; Alarma.ADVERTENCIA -> "Advertencia"; Alarma.CRITICA -> "Crítica"; Alarma.SIN_DATOS -> "Sin datos"
                }, color = when (fisico.alarma) { Alarma.CRITICA -> SafeHomeCritical; Alarma.ADVERTENCIA -> SafeHomeWarning; else -> SafeHomeInk })
                Text("Última actualización: ${fechaHora(fisico.actualizadoEn)}", style = MaterialTheme.typography.bodySmall)
                if (puertaPuedeEstarDesactualizada(panel.estacion, panel.fisico))
                    Text("La Estación no tiene conexión MQTT confirmada o los datos son de caché. El estado conocido de la puerta puede estar desactualizado.", color = SafeHomeWarning)
            }
        }
        Text("Lectura desde Firebase. La publicación física EMQX → Firebase está pendiente de integración.", color = SafeHomeMuted)
        OutlinedButton(onClick = reintentar) { Text("Reintentar consultas") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun TarjetaDispositivo(titulo: String, lectura: Lectura<EstadoDispositivo>) {
    TarjetaSafeHome(titulo) {
        ContenidoLectura(lectura) { dispositivo ->
            Text(when (dispositivo.mqtt) {
                ConexionMqtt.CONECTADO -> "Conectado a MQTT"
                ConexionMqtt.DESCONECTADO -> "Desconectado de MQTT"
                ConexionMqtt.DESCONOCIDO -> "Estado desconocido"
            } + if (lectura is Lectura.Dato && lectura.desdeCache) " (último dato guardado)" else "")
            Text("Última actualización: ${fechaHora(dispositivo.actualizadoEn)}", style = MaterialTheme.typography.bodySmall)
            Text("Último heartbeat conocido: ${fechaHora(dispositivo.ultimoHeartbeatEn)}", style = MaterialTheme.typography.bodySmall)
        }
        if (lectura !is Lectura.Dato) Text("Estado MQTT desconocido", color = SafeHomeMuted)
    }
}
