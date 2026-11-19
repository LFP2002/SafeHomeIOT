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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.LocalDensity
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ritmo compartido por las pantallas de producto. */
object EspacioSafeHome {
    val seccion = 24.dp
    val tarjetas = 12.dp
    val interior = 16.dp
    val radio = 18.dp
}

@Composable fun NavegacionSafeHome(seleccion: Int, seleccionar: (Int) -> Unit) {
    val fuente = LocalDensity.current.fontScale
    NavigationBar(modifier = Modifier.heightIn(min = if (fuente > 1.3f) 104.dp else 80.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Brush.horizontalGradient(listOf(SafeHomeBlue, SafeHomeViolet)), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
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
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(EspacioSafeHome.radio),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(EspacioSafeHome.interior), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    Row(Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconoSafeHome(estado.icono, tint = color)
        Text(if (desdeCache) "Último estado conocido: ${estado.texto}" else estado.texto,
            modifier = Modifier.weight(1f, fill = false), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun AvisoSafeHome(texto: String, tono: TonoEstado = TonoEstado.INFORMATIVO,
    icono: IconoSafeHome = IconoSafeHome.INFO) {
    val color = tono.color()
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        .background(color.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconoSafeHome(icono, tint = color)
        Text(texto, modifier = Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun EncabezadoSeccion(etiqueta: String, titulo: String, detalle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (etiqueta.isNotBlank()) Text(etiqueta, style = MaterialTheme.typography.labelMedium, color = SafeHomeViolet)
        Text(titulo, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (detalle.isNotBlank()) Text(detalle, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
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
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("es")).format(Date(it.millis))
} ?: "Sin datos"

@Composable fun <T> ContenidoLectura(lectura: Lectura<T>, contenido: @Composable (T) -> Unit) {
    when (lectura) {
        Lectura.Cargando -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando datos…", style = MaterialTheme.typography.bodyMedium) }
        Lectura.SinDatos -> AvisoSafeHome("Sin datos disponibles.", TonoEstado.NEUTRO, IconoSafeHome.DESCONOCIDO)
        Lectura.Invalida -> AvisoSafeHome("No se pudo mostrar esta información.", TonoEstado.CRITICO, IconoSafeHome.ERROR)
        is Lectura.Error -> AvisoSafeHome(lectura.mensaje, TonoEstado.CRITICO, IconoSafeHome.ERROR)
        is Lectura.Dato -> {
            if (lectura.desdeCache) AvisoSafeHome("Último estado conocido; puede haber cambiado.", TonoEstado.NEUTRO, IconoSafeHome.HISTORIAL)
            contenido(lectura.valor)
        }
    }
}

/** Dos columnas solo cuando ambas tarjetas tienen anchura útil, también con letra grande. */
@Composable fun ParejaEstados(primero: @Composable () -> Unit, segundo: @Composable () -> Unit) {
    val fuente = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 328.dp && maxWidth / fuente >= 300.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(EspacioSafeHome.tarjetas)) {
                Box(Modifier.weight(1f)) { primero() }
                Box(Modifier.weight(1f)) { segundo() }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(EspacioSafeHome.tarjetas)) { primero(); segundo() }
    }
}

@Composable fun TarjetaEstado(titulo: String, estado: EstadoVisual, anterior: Boolean = false) {
    val tono = if (anterior) TonoEstado.NEUTRO else estado.tono
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(EspacioSafeHome.radio),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, SafeHomeOutline)) {
        Column(Modifier.padding(EspacioSafeHome.interior), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconoSafeHome(estado.icono, tint = tono.color())
                Text(titulo, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted, modifier = Modifier.weight(1f))
            }
            Text(estado.texto, style = MaterialTheme.typography.titleMedium, color = tono.color())
            if (anterior) Text("Último estado conocido", style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
        }
    }
}

@Composable fun AccionPrincipal(texto: String, icono: IconoSafeHome, habilitada: Boolean = true, accion: () -> Unit) {
    Button(onClick = accion, enabled = habilitada, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        IconoSafeHome(icono)
        Spacer(Modifier.width(10.dp))
        Text(texto, modifier = Modifier.weight(1f))
    }
}

@Composable fun AccionSecundaria(texto: String, icono: IconoSafeHome, habilitada: Boolean = true, accion: () -> Unit) {
    OutlinedButton(onClick = accion, enabled = habilitada, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SafeHomeViolet),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        IconoSafeHome(icono)
        Spacer(Modifier.width(10.dp))
        Text(texto, modifier = Modifier.weight(1f))
    }
}

@Composable fun EstadoVacio(titulo: String = "Aún no hay eventos", detalle: String = "Aquí aparecerá la actividad de tu sistema") {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MedallonIcono(IconoSafeHome.HISTORIAL, TonoEstado.NEUTRO)
        Text(titulo, style = MaterialTheme.typography.titleMedium)
        Text(detalle, color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
