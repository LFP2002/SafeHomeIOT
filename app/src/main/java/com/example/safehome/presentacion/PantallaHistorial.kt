package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.SafeHomeMuted

@Composable fun PantallaHistorial(lectura: Lectura<HistorialSupervision>, reintentar: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Historial", style = MaterialTheme.typography.headlineSmall)
        Text("Hechos publicados; no se generan eventos desde Android.", color = SafeHomeMuted)
        TextButton(onClick = reintentar) { Text("Actualizar consultas") }
        ContenidoLectura(lectura) { historial ->
            if (historial.eventos.isEmpty()) Text("Sin eventos válidos disponibles.")
            if (historial.incompatibles > 0) Text("${historial.incompatibles} registros incompatibles omitidos.", color = SafeHomeMuted)
            if (historial.limiteAlcanzado) Text("Se consultan los 100 registros más recientes; puede haber historial anterior.", color = SafeHomeMuted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(historial.eventos, key = { it.id }) { evento ->
                    TarjetaSafeHome(evento.descripcion) {
                        Text(evento.dispositivo)
                        Text(fechaHora(evento.ocurridoEn))
                        Text("Tipo: ${evento.tipo}", style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
                        if (evento.anterior) Text("Registro histórico anterior; no define el estado actual.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
