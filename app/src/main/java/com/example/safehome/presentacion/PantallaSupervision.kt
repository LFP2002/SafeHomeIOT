package com.example.safehome.presentacion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.ui.theme.*
import kotlinx.coroutines.delay

@Composable fun PantallaSupervision(panel: PanelSupervision, reintentar: () -> Unit,
    enviar: (Boolean) -> Unit = {}, reconocer: () -> Unit = {}) {
    // Reloj solo visual: no persiste estado ni altera disponibilidad o permisos.
    val ahora by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(EspacioSafeHome.seccion)) {
        EncabezadoSeccion("", "Panel de control", "Supervisa y controla tu hogar")
        val mqtt = panel.mqtt
        if (mqtt == null) {
            PanelSinConexion(panel, reintentar)
        } else {
            ResumenSistema(mqtt, ahora)
            Column(verticalArrangement = Arrangement.spacedBy(EspacioSafeHome.tarjetas)) {
                ParejaEstados(
                    { TarjetaEstado("Conexión", conexionVisual(mqtt)) },
                    { TarjetaEstado("Estación", disponibilidadVisual(mqtt.estacion, mqtt.conectado)) })
                ParejaEstados(
                    { TarjetaEstado("Portátil", disponibilidadVisual(mqtt.portatil, mqtt.conectado)) },
                    { TarjetaEstado("Vigilancia", vigilanciaVisual(mqtt, ahora)) })
            }
            SensoresSistema(mqtt, ahora)
            ControlesVigilancia(mqtt, enviar, reconocer)
            mqtt.almacenamiento?.let {
                AvisoSafeHome("No se pudo guardar parte de la actividad. El historial puede estar incompleto.",
                    TonoEstado.AVISO, IconoSafeHome.ADVERTENCIA)
            }
            if (!mqtt.conectado) {
                when {
                    conexionVisual(mqtt).tono == TonoEstado.AVISO -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    mqtt.conexion.contains("configurar") -> AvisoSafeHome("La conexión necesita configuración. Contacta con la persona que administra el sistema.", TonoEstado.AVISO)
                    else -> {
                        if (mqtt.conexion.startsWith("No conectado")) AvisoSafeHome("No se pudo conectar. Revisa tu conexión o contacta con quien administra el sistema.", TonoEstado.AVISO)
                        BotonActualizar("Reintentar conexión", reintentar)
                    }
                }
            }
        }
    }
}

@Composable private fun ResumenSistema(panel: PanelMqtt, ahora: Long) {
    val resumen = resumenVisual(panel, ahora)
    val color = resumen.tono.color()
    Surface(color = color.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MedallonIcono(resumen.icono, resumen.tono)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(resumen.texto, color = color, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(when {
                    !panel.conectado -> if (conexionVisual(panel).tono == TonoEstado.AVISO) "Estableciendo conexión con tu hogar" else "Los controles no están disponibles"
                    panel.estacion == Disponibilidad.OFFLINE -> "No se puede confirmar el estado de tu hogar"
                    panel.estado == null || panel.estacion != Disponibilidad.ONLINE -> "Esperando información de la Estación"
                    !estadoVigente(panel, ahora) -> "La información disponible no confirma la situación actual"
                    else -> "Actualizado ${fechaHora(Instante(panel.estado.actualizadoEn))}"
                }, style = MaterialTheme.typography.bodyMedium, color = SafeHomeMuted)
            }
        }
    }
}

@Composable private fun SensoresSistema(panel: PanelMqtt, ahora: Long) {
    val estado = panel.estado
    val anterior = estado != null && !estadoVigente(panel, ahora)
    Column(verticalArrangement = Arrangement.spacedBy(EspacioSafeHome.tarjetas)) {
        Text("En tu hogar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (anterior) AvisoSafeHome("Último estado conocido · ${fechaHora(estado?.let { Instante(it.actualizadoEn) })}",
            TonoEstado.NEUTRO, IconoSafeHome.HISTORIAL)
        ParejaEstados(
            { TarjetaEstado("Puerta", visualPuerta(estado?.puerta ?: Puerta.SIN_DATOS), anterior) },
            { TarjetaEstado("Alarma", visualAlarma(estado?.alarma ?: Alarma.SIN_DATOS), anterior) })
        // Esta señal procede de Estación: no se confunde con la conexión del Portátil.
        val presencia = estado?.portatil ?: PresenciaPortatil.SIN_DATOS
        val senal = when (presencia) {
            PresenciaPortatil.PRESENTE -> EstadoVisual("Señal recibida", IconoSafeHome.CONECTADO, TonoEstado.POSITIVO)
            PresenciaPortatil.AUSENTE -> EstadoVisual("Sin señal", IconoSafeHome.ADVERTENCIA, TonoEstado.AVISO)
            PresenciaPortatil.SIN_DATOS -> EstadoVisual("Sin datos", IconoSafeHome.DESCONOCIDO, TonoEstado.NEUTRO)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconoSafeHome(senal.icono, tint = (if (anterior) TonoEstado.NEUTRO else senal.tono).color())
            Column(Modifier.weight(1f)) {
                Text("Señal del Portátil: ${senal.texto.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                Text(if (anterior) "Último reporte de la Estación" else "Reportada por la Estación",
                    style = MaterialTheme.typography.bodySmall, color = SafeHomeMuted)
            }
        }
    }
}

@Composable private fun ControlesVigilancia(panel: PanelMqtt, enviar: (Boolean) -> Unit, reconocer: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Control de vigilancia", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        AccionPrincipal("Activar vigilancia", IconoSafeHome.NORMAL, panel.permiteControl) { enviar(true) }
        AccionSecundaria("Desactivar vigilancia", IconoSafeHome.SISTEMA, panel.permiteControl) { enviar(false) }
        AccionSecundaria("Silenciar alarma", IconoSafeHome.SILENCIAR, panel.permiteReconocer, reconocer)
        resultadoVisual(panel)?.let {
            AvisoSafeHome(it.texto, it.tono, it.icono)
        }
    }
}

/** Compatibilidad de lectura: no construye una conexión ni controles desde datos anteriores. */
@Composable private fun PanelSinConexion(panel: PanelSupervision, reintentar: () -> Unit) {
    AvisoSafeHome("Supervisión sin conexión", TonoEstado.NEUTRO, IconoSafeHome.DESCONECTADO)
    ParejaEstados(
        { TarjetaSafeHome("Estación", icono = IconoSafeHome.PANEL) { ContenidoLectura(panel.estacion) { EtiquetaEstado(visualConexion(it.mqtt), true) } } },
        { TarjetaSafeHome("Portátil", icono = IconoSafeHome.DISPOSITIVO) { ContenidoLectura(panel.portatil) { EtiquetaEstado(visualConexion(it.mqtt), true) } } })
    ContenidoLectura(panel.fisico) {
        ParejaEstados({ TarjetaEstado("Puerta", visualPuerta(it.puerta), true) },
            { TarjetaEstado("Alarma", visualAlarma(it.alarma), true) })
    }
    ControlesVigilancia(PanelMqtt(), {}, {})
    BotonActualizar("Reintentar conexión", reintentar)
}
