package com.example.safehome.presentacion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun CabeceraSafeHome(subtitulo: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .background(Brush.horizontalGradient(listOf(SafeHomeBlue, SafeHomeViolet)), RoundedCornerShape(22.dp)).padding(18.dp)) {
        Text("SafeHome", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text(subtitulo, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun TarjetaSafeHome(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleLarge, color = SafeHomeViolet)
            contenido()
        }
    }
}

fun fechaHora(instante: Instante?): String = instante?.let {
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", Locale.getDefault()).format(Date(it.millis))
} ?: "Sin datos"

@Composable fun <T> ContenidoLectura(lectura: Lectura<T>, contenido: @Composable (T) -> Unit) {
    when (lectura) {
        Lectura.Cargando -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Consultando Firestore…") }
        Lectura.SinDatos -> Text("Sin datos publicados por el backend.", color = SafeHomeMuted)
        Lectura.Invalida -> Text("Datos incompatibles con el contrato de supervisión.", color = SafeHomeCritical)
        is Lectura.Error -> Text(lectura.mensaje, color = SafeHomeCritical)
        is Lectura.Dato -> {
            if (lectura.desdeCache) Text("Datos de caché: sin confirmación actual del servidor.", color = SafeHomeWarning)
            contenido(lectura.valor)
        }
    }
}
