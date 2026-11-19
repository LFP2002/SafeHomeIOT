package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safehome.PerfilUsuario
import com.example.safehome.RolUsuario
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.SafeHomeTheme

/** Fixtures exclusivos del render de previews: sin ViewModel, Firebase, Room ni cliente de red. */
@Preview(name = "Panel · 320 dp", widthDp = 320, heightDp = 760, showBackground = true)
@Preview(name = "Panel · 360 dp", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "Panel · 412 dp", widthDp = 412, heightDp = 860, showBackground = true)
@Preview(name = "Panel · amplia horizontal", widthDp = 840, heightDp = 500, showBackground = true)
@Preview(name = "Panel · fuente 1.8", widthDp = 360, heightDp = 800, fontScale = 1.8f, showBackground = true)
@Composable private fun PanelPreview() {
    MarcoPreview(0) {
        val estado = EstadoMqtt(true, Puerta.CERRADA, PresenciaPortatil.PRESENTE, Alarma.NORMAL,
            System.currentTimeMillis(), "preview", "")
        PantallaSupervision(PanelSupervision(mqtt = PanelMqtt(conectado = true,
            estacion = Disponibilidad.ONLINE, portatil = Disponibilidad.ONLINE, estado = estado, retenido = false)), {})
    }
}

@Preview(name = "Alerta crítica", widthDp = 390, heightDp = 840, showBackground = true)
@Composable private fun AlarmaPreview() {
    MarcoPreview(0) {
        val estado = EstadoMqtt(true, Puerta.ABIERTA, PresenciaPortatil.AUSENTE, Alarma.CRITICA,
            System.currentTimeMillis(), "preview", "")
        PantallaSupervision(PanelSupervision(mqtt = PanelMqtt(conectado = true, estacion = Disponibilidad.ONLINE,
            estado = estado, retenido = false)), {})
    }
}

@Preview(name = "Historial · 12 eventos", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "Historial · amplia", widthDp = 800, heightDp = 650, showBackground = true)
@Composable private fun HistorialPreview() {
    MarcoPreview(1) {
        val tipos = listOf("PUERTA_ABIERTA", "ALARMA_CRITICA", "ALARMA_RECONOCIDA", "SISTEMA_DESACTIVADO")
        val eventos = (0..11).map { EventoSupervision("preview-$it", tipos[it % tipos.size], "",
            Instante(1_795_000_000_000L - it * 60_000), "") }
        PantallaHistorial(Lectura.Dato(HistorialSupervision(eventos, 0, false)), {})
    }
}

@Preview(name = "Historial vacío · 320 dp", widthDp = 320, heightDp = 700, showBackground = true)
@Composable private fun HistorialVacioPreview() {
    MarcoPreview(1) { PantallaHistorial(Lectura.SinDatos, {}) }
}

@Preview(name = "Cuenta", widthDp = 360, heightDp = 800, showBackground = true)
@Composable private fun CuentaPreview() {
    MarcoPreview(2) { PantallaCuenta(PerfilUsuario(RolUsuario.ESTACION, "Hogar de ejemplo", "Ana"), {}) }
}

@Composable private fun MarcoPreview(tab: Int, contenido: @Composable () -> Unit) {
    SafeHomeTheme {
        Scaffold(bottomBar = { NavegacionSafeHome(tab) {} }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                CabeceraSafeHome("Ana")
                Box(Modifier.weight(1f)) { contenido() }
            }
        }
    }
}
