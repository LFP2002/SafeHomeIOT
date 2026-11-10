package com.example.safehome.presentacion

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.safehome.R
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*

/** SVG oficiales de Bootstrap Icons convertidos a VectorDrawable; sin dependencias web. */
enum class IconoSafeHome(@param:DrawableRes val recurso: Int) {
    PANEL(R.drawable.bi_house_door), HISTORIAL(R.drawable.bi_clock_history),
    CUENTA(R.drawable.bi_person_circle), DISPOSITIVO(R.drawable.bi_cpu),
    PUERTA_ABIERTA(R.drawable.bi_door_open), PUERTA_CERRADA(R.drawable.bi_door_closed),
    NORMAL(R.drawable.bi_shield_check), ADVERTENCIA(R.drawable.bi_exclamation_triangle),
    ERROR(R.drawable.bi_x_circle), DESCONOCIDO(R.drawable.bi_question_circle),
    CONECTADO(R.drawable.bi_wifi), DESCONECTADO(R.drawable.bi_wifi_off),
    ACTUALIZAR(R.drawable.bi_arrow_clockwise), ALARMA(R.drawable.bi_bell),
    SISTEMA(R.drawable.bi_power), SALIR(R.drawable.bi_box_arrow_right), INFO(R.drawable.bi_info_circle)
}

@Composable
fun IconoSafeHome(
    icono: IconoSafeHome,
    modifier: Modifier = Modifier,
    descripcion: String? = null,
    tint: Color = LocalContentColor.current
) {
    Icon(painterResource(icono.recurso), contentDescription = descripcion,
        modifier = modifier.size(24.dp), tint = tint)
}

enum class TonoEstado { POSITIVO, AVISO, CRITICO, NEUTRO, INFORMATIVO }

@Composable
fun TonoEstado.color(): Color = when (this) {
    TonoEstado.POSITIVO -> SafeHomeSuccess
    TonoEstado.AVISO -> SafeHomeWarning
    TonoEstado.CRITICO -> MaterialTheme.colorScheme.error
    TonoEstado.NEUTRO -> SafeHomeMuted
    TonoEstado.INFORMATIVO -> SafeHomeViolet
}

data class EstadoVisual(val texto: String, val icono: IconoSafeHome, val tono: TonoEstado)

/** Solo presentación: no infiere presencia física ni modifica estados del dominio. */
fun visualConexion(conexion: ConexionMqtt): EstadoVisual = when (conexion) {
    ConexionMqtt.CONECTADO -> EstadoVisual("Conectado a MQTT", IconoSafeHome.CONECTADO, TonoEstado.POSITIVO)
    ConexionMqtt.DESCONECTADO -> EstadoVisual("Desconectado de MQTT", IconoSafeHome.DESCONECTADO, TonoEstado.AVISO)
    ConexionMqtt.DESCONOCIDO -> EstadoVisual("Estado MQTT desconocido", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
}

fun visualPuerta(puerta: Puerta): EstadoVisual = when (puerta) {
    Puerta.ABIERTA -> EstadoVisual("Abierta", IconoSafeHome.PUERTA_ABIERTA, TonoEstado.INFORMATIVO)
    Puerta.CERRADA -> EstadoVisual("Cerrada", IconoSafeHome.PUERTA_CERRADA, TonoEstado.INFORMATIVO)
    Puerta.SIN_DATOS -> EstadoVisual("Estado desconocido", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
}

fun visualAlarma(alarma: Alarma): EstadoVisual = when (alarma) {
    Alarma.NORMAL -> EstadoVisual("Normal", IconoSafeHome.NORMAL, TonoEstado.POSITIVO)
    Alarma.ADVERTENCIA -> EstadoVisual("Advertencia", IconoSafeHome.ADVERTENCIA, TonoEstado.AVISO)
    Alarma.CRITICA -> EstadoVisual("Crítica", IconoSafeHome.ALARMA, TonoEstado.CRITICO)
    Alarma.SIN_DATOS -> EstadoVisual("Estado desconocido", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
}

fun visualEvento(tipo: String): Pair<IconoSafeHome, TonoEstado> = when (tipo) {
    "ESTACION_CONECTADA", "PORTATIL_CONECTADO" -> IconoSafeHome.CONECTADO to TonoEstado.POSITIVO
    "ESTACION_DESCONECTADA", "PORTATIL_DESCONECTADO" -> IconoSafeHome.DESCONECTADO to TonoEstado.AVISO
    "PUERTA_ABIERTA" -> IconoSafeHome.PUERTA_ABIERTA to TonoEstado.INFORMATIVO
    "PUERTA_CERRADA" -> IconoSafeHome.PUERTA_CERRADA to TonoEstado.INFORMATIVO
    "ALARMA_ADVERTENCIA", "PORTATIL_FUERA_RANGO" -> IconoSafeHome.ADVERTENCIA to TonoEstado.AVISO
    "ALARMA_CRITICA" -> IconoSafeHome.ALARMA to TonoEstado.CRITICO
    "ALARMA_RECONOCIDA" -> IconoSafeHome.NORMAL to TonoEstado.INFORMATIVO
    "RECONOCIMIENTO_SOLICITADO" -> IconoSafeHome.ALARMA to TonoEstado.INFORMATIVO
    "SISTEMA_ACTIVADO", "SISTEMA_DESACTIVADO" -> IconoSafeHome.SISTEMA to TonoEstado.INFORMATIVO
    "MOVIL_CONECTADO", "MOVIL_DESCONECTADO" -> IconoSafeHome.CUENTA to TonoEstado.NEUTRO
    "PORTATIL_RECONECTADO" -> IconoSafeHome.DISPOSITIVO to TonoEstado.NEUTRO
    "ESTADO_ACTUALIZADO" -> IconoSafeHome.HISTORIAL to TonoEstado.NEUTRO
    else -> IconoSafeHome.INFO to TonoEstado.NEUTRO
}
