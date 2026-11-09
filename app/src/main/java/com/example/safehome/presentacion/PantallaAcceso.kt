package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.safehome.*
import com.example.safehome.autenticacion.EstadoSesionUi
import com.example.safehome.ui.theme.*

@Composable fun PantallaAcceso(estado: EstadoSesionUi,
    iniciar: (String, String) -> ErroresFormulario,
    registrar: (String, String, String, String) -> ErroresRegistroEstacion,
    recuperar: (String) -> String?, reintentar: () -> Unit, cerrar: () -> Unit) {
    var modo by rememberSaveable { mutableStateOf("login") }
    var nombre by rememberSaveable { mutableStateOf("") }
    var correo by rememberSaveable { mutableStateOf("") }
    // Contraseñas solo en memoria, nunca en SavedState/Bundle.
    var clave by remember { mutableStateOf("") }
    var confirmacion by remember { mutableStateOf("") }
    var errorLocal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(estado.uid, modo) { clave = ""; confirmacion = ""; errorLocal = null }
    Surface(color = SafeHomeCanvas, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CabeceraSafeHome("Supervisión IoT · Cuenta Estación")
            if (estado.uid != null) {
                TarjetaSafeHome(if (estado.comprobando) "Verificando acceso" else "Acceso no disponible") {
                    if (estado.comprobando) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(estado.mensaje ?: "Comprobando que la cuenta sea una Estación activa.")
                    Button(onClick = reintentar, enabled = !estado.ocupado) { Text(if (estado.registroPendiente) "Reintentar preparar hogar" else "Reintentar perfil") }
                    TextButton(onClick = cerrar) { Text("Cerrar sesión") }
                }
            } else {
                Text(when (modo) { "registro" -> "Crear cuenta Estación"; "recuperar" -> "Recuperar contraseña"; else -> "Iniciar sesión" },
                    style = MaterialTheme.typography.headlineSmall)
                Text("El Portátil es un dispositivo físico, no una cuenta de usuario.", color = SafeHomeMuted)
                if (modo == "registro") CampoAcceso("Nombre", nombre, !estado.ocupado) { nombre = it }
                OutlinedTextField(value = correo, onValueChange = { correo = it }, label = { Text("Correo electrónico") },
                    enabled = !estado.ocupado, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (modo != "recuperar") {
                    OutlinedTextField(value = clave, onValueChange = { clave = it }, label = { Text("Contraseña") },
                        enabled = !estado.ocupado, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                if (modo == "registro") {
                    Text("12–32 caracteres, con mayúscula, minúscula y número.", color = SafeHomeMuted)
                    OutlinedTextField(value = confirmacion, onValueChange = { confirmacion = it }, label = { Text("Confirmar contraseña") },
                        enabled = !estado.ocupado, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                errorLocal?.let { Text(it, color = SafeHomeCritical) }
                estado.mensaje?.let { Text(it, color = SafeHomeInk) }
                if (estado.ocupado) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Esperando respuesta de Firebase…") }
                Button(onClick = {
                    errorLocal = when (modo) {
                        "registro" -> registrar(nombre, correo, clave, confirmacion).let { listOfNotNull(it.nombre, it.correo, it.clave, it.confirmacion).firstOrNull() }
                        "recuperar" -> recuperar(correo)
                        else -> iniciar(correo, clave).let { it.correo ?: it.clave }
                    }
                }, enabled = !estado.ocupado, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeHomeViolet)) {
                    Text(when (modo) { "registro" -> "Registrar Estación"; "recuperar" -> "Enviar instrucciones"; else -> "Entrar" })
                }
                if (modo == "login") {
                    TextButton(onClick = { modo = "registro" }, enabled = !estado.ocupado) { Text("Crear cuenta Estación") }
                    TextButton(onClick = { modo = "recuperar" }, enabled = !estado.ocupado) { Text("Olvidé mi contraseña") }
                } else TextButton(onClick = { modo = "login" }, enabled = !estado.ocupado) { Text("Volver a iniciar sesión") }
                if (estado.ocupado) TextButton(onClick = cerrar) { Text("Cancelar acceso / cerrar sesión") }
            }
        }
    }
}

@Composable private fun CampoAcceso(etiqueta: String, valor: String, habilitado: Boolean, cambiar: (String) -> Unit) {
    OutlinedTextField(value = valor, onValueChange = cambiar, label = { Text(etiqueta) }, singleLine = true,
        enabled = habilitado, modifier = Modifier.fillMaxWidth())
}
