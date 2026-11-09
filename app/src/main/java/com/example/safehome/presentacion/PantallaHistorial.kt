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
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            EncabezadoSeccion("ACTIVIDAD DEL HOGAR", "Historial", "Hechos publicados; no se generan eventos desde Android.")
        }
        item { BotonActualizar("Actualizar consultas", reintentar) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ContenidoLectura(lectura) { historial ->
                    Text("${historial.eventos.size} eventos disponibles · Más recientes primero",
                        style = MaterialTheme.typography.labelLarge)
                    if (historial.eventos.isEmpty()) AvisoSafeHome("Sin eventos válidos disponibles.", TonoEstado.NEUTRO, IconoSafeHome.HISTORIAL)
                    if (historial.incompatibles > 0) AvisoSafeHome("${historial.incompatibles} registros incompatibles omitidos.", TonoEstado.NEUTRO)
                    if (historial.limiteAlcanzado) AvisoSafeHome("Se consultan los 100 registros más recientes; puede haber historial anterior.", TonoEstado.NEUTRO)
                }
            }
        }
        if (lectura is Lectura.Dato) {
            items(lectura.valor.eventos, key = { it.id }) { evento -> TarjetaEvento(evento) }
        }
    }
}

@Composable private fun TarjetaEvento(evento: EventoSupervision) {
    val (icono, tono) = visualEvento(evento.tipo)
    TarjetaSafeHome(evento.descripcion, icono = icono, tono = tono) {
        Text(fechaHora(evento.ocurridoEn), style = MaterialTheme.typography.labelLarge)
        Text(evento.dispositivo, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
        Text("Tipo: ${evento.tipo}", style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
        if (evento.anterior) {
            AvisoSafeHome("Registro histórico anterior; no define el estado actual.", TonoEstado.NEUTRO, IconoSafeHome.HISTORIAL)
        }
    }
}
