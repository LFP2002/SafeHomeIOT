package com.example.safehome.presentacion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*

@Composable fun PantallaHistorial(lectura: Lectura<HistorialSupervision>, reintentar: () -> Unit,
    contexto: String = "historial", offline: Boolean = false) {
    var solicitada by rememberSaveable(contexto) { mutableIntStateOf(0) }
    val fuente = (lectura as? Lectura.Dato)?.valor
    val eventos = remember(fuente?.eventos) {
        val lista = fuente?.eventos.orEmpty()
        if (lista.zipWithNext().all { (a, b) -> a.ocurridoEn.millis >= b.ocurridoEn.millis }) lista
        else lista.sortedByDescending { it.ocurridoEn.millis }
    }
    val pagina = remember(eventos, solicitada) { paginarHistorial(eventos, solicitada) }
    val scroll = rememberLazyListState()
    // No perder una página durante una carga transitoria; sí ajustarla al disminuir la lista.
    LaunchedEffect(pagina.indice, contexto, fuente != null) {
        if (fuente != null || lectura == Lectura.SinDatos) solicitada = pagina.indice
    }
    LaunchedEffect(pagina.indice, contexto) { scroll.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll,
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "encabezado") { EncabezadoSeccion("", "Historial", "Actividad registrada en tu hogar") }
        if (offline) item(key = "offline") { EtiquetaEstado(EstadoVisual("Disponible sin conexión", IconoSafeHome.HISTORIAL, TonoEstado.NEUTRO)) }
        when (lectura) {
            Lectura.Cargando -> item(key = "cargando") { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando actividad…") }
            is Lectura.Error -> item(key = "error") {
                AvisoSafeHome("No se pudo leer la actividad guardada. Vuelve a abrir el historial para intentarlo de nuevo.", TonoEstado.CRITICO, IconoSafeHome.ERROR)
            }
            Lectura.Invalida -> item(key = "invalida") { AvisoSafeHome("No se pudo mostrar la actividad guardada.", TonoEstado.AVISO) }
            else -> {
                if (pagina.total == 0) item(key = "vacio") { EstadoVacio() }
                else {
                    item(key = "total") {
                        Text("${pagina.total} ${if (pagina.total == 1) "evento" else "eventos"}", style = MaterialTheme.typography.titleMedium)
                    }
                    items(pagina.elementos, key = { "evento:${it.id}" }) { FilaHistorial(it) }
                    if (pagina.paginas > 1) item(key = "paginador") {
                        ControlesPaginacion(pagina.indice, pagina.paginas, pagina.anterior, pagina.siguiente,
                            { solicitada = pagina.indice - 1 }, { solicitada = pagina.indice + 1 })
                    }
                }
                if ((fuente?.incompatibles ?: 0) > 0) item(key = "omitidos") {
                    AvisoSafeHome("Algunos registros no se pudieron mostrar.", TonoEstado.AVISO)
                }
                if (fuente?.limiteAlcanzado == true) item(key = "limite") {
                    Text("Se muestran los registros disponibles más recientes.", color = SafeHomeMuted)
                }
            }
        }
    }
}

@Composable private fun FilaHistorial(evento: EventoSupervision) {
    val (icono, tono) = visualEvento(evento.tipo)
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, SafeHomeOutline)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MedallonIcono(icono, tono)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tituloEvento(evento.tipo), style = MaterialTheme.typography.titleMedium)
                Text(fechaHora(evento.ocurridoEn), style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
                if (evento.anterior) Text("Registro anterior", style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
            }
        }
    }
}

@Composable fun ControlesPaginacion(indice: Int, paginas: Int, anterior: Boolean, siguiente: Boolean,
    retroceder: () -> Unit, avanzar: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Página ${indice + 1} de $paginas", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ParejaEstados(
            { OutlinedButton(retroceder, enabled = anterior, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Anterior") } },
            { OutlinedButton(avanzar, enabled = siguiente, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Siguiente") } })
    }
}
