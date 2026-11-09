package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safehome.PerfilUsuario

@Composable fun PantallaCuenta(perfil: PerfilUsuario, cerrar: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TarjetaSafeHome("Cuenta Estación") {
            Text(perfil.nombreVisible)
            Text("Rol: estacion")
            Text("Panel de lectura de los ESP32 del hogar asignado.")
            Text("No hay cuentas Móvil, vinculación de teléfonos ni comandos físicos en esta versión.")
        }
        Button(onClick = cerrar, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
    }
}
