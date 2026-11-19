package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safehome.PerfilUsuario
import com.example.safehome.ui.theme.SafeHomeMuted

@Composable fun PantallaCuenta(perfil: PerfilUsuario, cerrar: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(EspacioSafeHome.seccion)) {
        EncabezadoSeccion("", "Cuenta", "Tu perfil en SafeHome")
        TarjetaSafeHome(perfil.nombreVisible, icono = IconoSafeHome.CUENTA) {
            EtiquetaEstado(EstadoVisual("Estación", IconoSafeHome.PANEL, TonoEstado.INFORMATIVO))
            if (perfil.hogarId.isNotBlank()) {
                Text("Hogar asignado", color = SafeHomeMuted, style = MaterialTheme.typography.bodyMedium)
                Text(perfil.hogarId, style = MaterialTheme.typography.bodyLarge)
            }
        }
        AccionSecundaria("Cerrar sesión", IconoSafeHome.SALIR, accion = cerrar)
    }
}
