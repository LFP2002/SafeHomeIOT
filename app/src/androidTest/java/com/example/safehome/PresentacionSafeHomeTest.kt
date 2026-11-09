package com.example.safehome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.presentacion.*
import com.example.safehome.ui.theme.SafeHomeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Datos de prueba aislados del APK de producción. No utiliza Firebase ni una sesión real. */
class PresentacionSafeHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun todosLosVectorDrawableSePuedenRenderizar() {
        compose.setContent {
            SafeHomeTheme {
                Column { IconoSafeHome.entries.forEach { IconoSafeHome(it, descripcion = "icono-${it.name}") } }
            }
        }
        compose.onAllNodesWithContentDescription("icono-", substring = true)
            .assertCountEquals(IconoSafeHome.entries.size)
    }

    @Test fun navegacionConservaEtiquetasSeleccionYAccion() {
        compose.setContent {
            SafeHomeTheme {
                val seleccionado = remember { mutableIntStateOf(0) }
                NavegacionSafeHome(seleccionado.intValue) { seleccionado.intValue = it }
            }
        }
        compose.onNodeWithText("Panel").assertIsSelected()
        compose.onNodeWithText("Historial").performClick().assertIsSelected()
        compose.onNodeWithText("Panel").assertIsNotSelected()
        compose.onNodeWithText("Cuenta").performClick().assertIsSelected()
    }

    @Test fun cargaNoMuestraConexionConfirmada() {
        compose.setContent { SafeHomeTheme { Surface { PantallaSupervision(PanelSupervision()) {} } } }
        compose.onNodeWithText("ESP32 Estación").assertIsDisplayed()
        compose.onNodeWithText("Conectado a MQTT").assertDoesNotExist()
        compose.onAllNodesWithText("Estado MQTT desconocido").assertCountEquals(2)
    }

    @Test fun erroresYDatosAusentesSeDistinguenYReintentoFunciona() {
        var reintentos = 0
        compose.setContent {
            SafeHomeTheme { Surface {
                PantallaSupervision(PanelSupervision(estacion = Lectura.Error("Consulta no disponible"),
                    portatil = Lectura.SinDatos, fisico = Lectura.Invalida)) { reintentos++ }
            } }
        }
        compose.onNodeWithText("Consulta no disponible").assertExists()
        compose.onNodeWithText("Sin datos publicados por el backend.").assertExists()
        compose.onAllNodesWithText("Datos incompatibles con el contrato de supervisión.").assertCountEquals(2)
        compose.onNodeWithText("Reintentar consultas").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reintentos) }
    }

    @Test fun cacheNuncaSePresentaComoConexionActualConfirmada() {
        val instante = Instante(1_700_000_000_000)
        compose.setContent {
            SafeHomeTheme { Surface {
                PantallaSupervision(PanelSupervision(
                    estacion = Lectura.Dato(EstadoDispositivo(ConexionMqtt.CONECTADO, instante, null), desdeCache = true),
                    fisico = Lectura.Dato(EstadoFisico(Puerta.CERRADA, Alarma.NORMAL, instante, null)))) {}
            } }
        }
        compose.onNodeWithText("Último dato guardado: Conectado a MQTT").assertExists()
        compose.onNodeWithText("Datos de caché: sin confirmación actual del servidor.").assertExists()
        compose.onNodeWithText("La Estación no tiene conexión MQTT confirmada o los datos son de caché. El estado conocido de la puerta puede estar desactualizado.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun historialMantieneAvisoDeRegistroAnteriorYFecha() {
        val evento = EventoSupervision("anterior", "ALARMA_RECONOCIDA", "Estación (registro anterior)",
            Instante(1_700_000_000_000), "Alarma reconocida", anterior = true)
        compose.setContent {
            SafeHomeTheme { Surface {
                PantallaHistorial(Lectura.Dato(HistorialSupervision(listOf(evento), 2, true))) {}
            } }
        }
        compose.onNodeWithText("2 registros incompatibles omitidos.").assertExists()
        compose.onNodeWithText("Registro histórico anterior; no define el estado actual.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(fechaHora(evento.ocurridoEn)).assertExists()
    }

    @Test fun pantallaEstrechaConFuenteAmpliadaPermiteLlegarALaAccion() {
        compose.setContent {
            SafeHomeTheme {
                val densidad = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(densidad, fontScale = 1.8f)) {
                    Surface(Modifier.width(320.dp).fillMaxHeight()) {
                        PantallaSupervision(PanelSupervision(estacion = Lectura.SinDatos,
                            portatil = Lectura.SinDatos, fisico = Lectura.SinDatos)) {}
                    }
                }
            }
        }
        compose.onNodeWithText("ESP32 Estación").assertExists()
        compose.onNodeWithText("Reintentar consultas").performScrollTo().assertIsDisplayed()
    }

    @Test fun panelAnchoDistribuyeLosDispositivosEnDosColumnas() {
        compose.setContent {
            SafeHomeTheme {
                Surface(Modifier.requiredWidth(800.dp).fillMaxHeight()) {
                    PantallaSupervision(PanelSupervision()) {}
                }
            }
        }
        val estacion = compose.onNodeWithText("ESP32 Estación").getUnclippedBoundsInRoot()
        val portatil = compose.onNodeWithText("ESP32 Portátil").getUnclippedBoundsInRoot()
        assertEquals(estacion.top, portatil.top)
        assertTrue(portatil.left > estacion.left)
    }
}
