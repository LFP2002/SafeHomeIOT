package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*

@Composable fun PantallaSupervision(panel: PanelSupervision, reintentar: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EncabezadoSeccion("TU HOGAR", "Panel de supervisión", "Estado publicado de tus dispositivos y sensores.")
        AvisoSafeHome("Conexión MQTT no equivale a presencia física. Una desconexión no demuestra que alguien salió del hogar.")
        ParejaAdaptable(
            primero = { TarjetaDispositivo("ESP32 Estación", "Nodo fijo del hogar", panel.estacion) },
            segundo = { TarjetaDispositivo("ESP32 Portátil", "Dispositivo físico portátil", panel.portatil) }
        )
        ParejaAdaptable(
            primero = { TarjetaPuerta(panel) },
            segundo = { TarjetaAlarma(panel.fisico) }
        )
        AvisoSafeHome("Lectura desde Firebase. La publicación física EMQX → Firebase está pendiente de integración.", TonoEstado.NEUTRO)
        BotonActualizar("Reintentar consultas", reintentar)
        Spacer(Modifier.height(12.dp))
    }
}

/** Una columna en móviles y con texto ampliado; dos columnas cuando hay espacio real. */
@Composable private fun ParejaAdaptable(primero: @Composable () -> Unit, segundo: @Composable () -> Unit) {
    val escalaFuente = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 640.dp && escalaFuente <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { primero() }
                Box(Modifier.weight(1f)) { segundo() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { primero(); segundo() }
        }
    }
}

@Composable private fun TarjetaDispositivo(titulo: String, detalle: String, lectura: Lectura<EstadoDispositivo>) {
    TarjetaSafeHome(titulo, icono = IconoSafeHome.DISPOSITIVO) {
        Text(detalle, color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium)
        ContenidoLectura(lectura) { dispositivo ->
            EtiquetaEstado(visualConexion(dispositivo.mqtt), (lectura as? Lectura.Dato)?.desdeCache == true)
            DatoTemporal("Última actualización", dispositivo.actualizadoEn)
            DatoTemporal("Último heartbeat conocido", dispositivo.ultimoHeartbeatEn)
        }
        if (lectura !is Lectura.Dato) Text("Estado MQTT desconocido", color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun TarjetaPuerta(panel: PanelSupervision) {
    val fisico = (panel.fisico as? Lectura.Dato)?.valor
    TarjetaSafeHome("Puerta", icono = fisico?.let { visualPuerta(it.puerta).icono } ?: IconoSafeHome.DESCONOCIDO) {
        ContenidoLectura(panel.fisico) {
            EtiquetaEstado(visualPuerta(it.puerta), (panel.fisico as? Lectura.Dato)?.desdeCache == true)
            DatoTemporal("Último cambio confirmado", it.puertaCambiadaEn)
            DatoTemporal("Última actualización", it.actualizadoEn)
            if (puertaPuedeEstarDesactualizada(panel.estacion, panel.fisico)) {
                AvisoSafeHome("La Estación no tiene conexión MQTT confirmada o los datos son de caché. El estado conocido de la puerta puede estar desactualizado.",
                    TonoEstado.AVISO, IconoSafeHome.ADVERTENCIA)
            }
        }
    }
}

@Composable private fun TarjetaAlarma(lectura: Lectura<EstadoFisico>) {
    TarjetaSafeHome("Alarma", icono = IconoSafeHome.ALARMA) {
        ContenidoLectura(lectura) {
            EtiquetaEstado(visualAlarma(it.alarma), (lectura as? Lectura.Dato)?.desdeCache == true)
            DatoTemporal("Última actualización", it.actualizadoEn)
            Text("Nivel publicado por el backend. No se envían comandos desde este panel.",
                style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
        }
    }
}
