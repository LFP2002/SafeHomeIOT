package com.example.safehome.presentacion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun NavegacionSafeHome(seleccion: Int, seleccionar: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf("Panel", "Historial", "Cuenta").forEachIndexed { indice, titulo ->
            NavigationBarItem(selected = seleccion == indice, onClick = { seleccionar(indice) },
                icon = { IconoSafeHome(listOf(IconoSafeHome.PANEL, IconoSafeHome.HISTORIAL, IconoSafeHome.CUENTA)[indice]) },
                label = { Text(titulo) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SafeHomeViolet, selectedTextColor = SafeHomeViolet,
                    indicatorColor = SafeHomeLilac, unselectedIconColor = SafeHomeMuted,
                    unselectedTextColor = SafeHomeMuted))
        }
    }
}

@Composable fun CabeceraSafeHome(subtitulo: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .background(Brush.horizontalGradient(listOf(SafeHomeBlue, SafeHomeViolet)), RoundedCornerShape(24.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(46.dp).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            IconoSafeHome(IconoSafeHome.PANEL, tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text("SafeHome", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(subtitulo, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable fun TarjetaSafeHome(
    titulo: String,
    modifier: Modifier = Modifier,
    icono: IconoSafeHome? = null,
    tono: TonoEstado = TonoEstado.INFORMATIVO,
    contenido: @Composable ColumnScope.() -> Unit
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icono?.let { MedallonIcono(it, tono) }
                Text(titulo, modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
            }
            contenido()
        }
    }
}

@Composable fun MedallonIcono(icono: IconoSafeHome, tono: TonoEstado = TonoEstado.INFORMATIVO) {
    val color = tono.color()
    Box(Modifier.size(44.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center) { IconoSafeHome(icono, tint = color) }
}

@Composable fun EtiquetaEstado(estado: EstadoVisual, desdeCache: Boolean = false) {
    val color = if (desdeCache) TonoEstado.AVISO.color() else estado.tono.color()
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconoSafeHome(estado.icono, tint = color)
        Text(if (desdeCache) "Último dato guardado: ${estado.texto}" else estado.texto,
            modifier = Modifier.weight(1f), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun AvisoSafeHome(texto: String, tono: TonoEstado = TonoEstado.INFORMATIVO,
    icono: IconoSafeHome = IconoSafeHome.INFO) {
    val color = tono.color()
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconoSafeHome(icono, tint = color)
        Text(texto, modifier = Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun EncabezadoSeccion(etiqueta: String, titulo: String, detalle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium, color = SafeHomeViolet)
        Text(titulo, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(detalle, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
    }
}

@Composable fun DatoTemporal(etiqueta: String, instante: Instante?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(etiqueta, style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
        Text(fechaHora(instante), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun BotonActualizar(texto: String, accion: () -> Unit) {
    FilledTonalButton(onClick = accion, colors = ButtonDefaults.filledTonalButtonColors(
        containerColor = SafeHomeLilac, contentColor = SafeHomeViolet)) {
        IconoSafeHome(IconoSafeHome.ACTUALIZAR)
        Spacer(Modifier.width(8.dp))
        Text(texto)
    }
}

fun fechaHora(instante: Instante?): String = instante?.let {
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", Locale.getDefault()).format(Date(it.millis))
} ?: "Sin datos"

@Composable fun <T> ContenidoLectura(lectura: Lectura<T>, contenido: @Composable (T) -> Unit) {
    when (lectura) {
        Lectura.Cargando -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Consultando Firestore…", style = MaterialTheme.typography.bodyMedium) }
        Lectura.SinDatos -> AvisoSafeHome("Sin datos publicados por el backend.", TonoEstado.NEUTRO, IconoSafeHome.DESCONOCIDO)
        Lectura.Invalida -> AvisoSafeHome("Datos incompatibles con el contrato de supervisión.", TonoEstado.CRITICO, IconoSafeHome.ERROR)
        is Lectura.Error -> AvisoSafeHome(lectura.mensaje, TonoEstado.CRITICO, IconoSafeHome.ERROR)
        is Lectura.Dato -> {
            if (lectura.desdeCache) AvisoSafeHome("Datos de caché: sin confirmación actual del servidor.", TonoEstado.AVISO, IconoSafeHome.HISTORIAL)
            contenido(lectura.valor)
        }
    }
}
