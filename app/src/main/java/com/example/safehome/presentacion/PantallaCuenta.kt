package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safehome.PerfilUsuario
import com.example.safehome.ui.theme.*

@Composable fun PantallaCuenta(perfil: PerfilUsuario, cerrar: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        EncabezadoSeccion("TU CUENTA", "Cuenta", "Información de acceso y supervisión.")
        TarjetaSafeHome("Cuenta Estación", icono = IconoSafeHome.CUENTA) {
            Text(perfil.nombreVisible, style = MaterialTheme.typography.titleLarge)
            EtiquetaEstado(EstadoVisual("Rol: Estación", IconoSafeHome.DISPOSITIVO, TonoEstado.INFORMATIVO))
            Text("Panel de lectura de los ESP32 del hogar asignado.")
            Text("No hay cuentas Móvil, vinculación de teléfonos ni comandos físicos en esta versión.")
        }
        Button(onClick = cerrar, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
            containerColor = SafeHomeLilac, contentColor = SafeHomeViolet)) {
            IconoSafeHome(IconoSafeHome.SALIR)
            Spacer(Modifier.width(8.dp))
            Text("Cerrar sesión")
        }
        Spacer(Modifier.height(12.dp))
    }
}
