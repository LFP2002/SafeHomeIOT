package com.example.safehome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.safehome.iot.*
import com.example.safehome.presentacion.*
import com.example.safehome.ui.theme.SafeHomeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

/** Solo composables y fixtures en RAM. Nunca crea ControlSesion ni publica mensajes. */
class PresentacionSafeHomeTest {
    @get:Rule val compose = createComposeRule()
    private fun panel(alarma: Alarma = Alarma.NORMAL) = PanelSupervision(mqtt = PanelMqtt(conectado = true,
        estacion = Disponibilidad.ONLINE, portatil = Disponibilidad.ONLINE, retenido = false,
        estado = EstadoMqtt(true, Puerta.CERRADA, PresenciaPortatil.PRESENTE, alarma, System.currentTimeMillis(), "test", "")))
    private fun eventos(cantidad: Int) = (0 until cantidad).map { EventoSupervision("test-$it", "PUERTA_ABIERTA", "",
        Instante(1_795_000_000_000L - it * 60_000), "") }

    @Test fun todosLosVectorDrawableSePuedenRenderizar() {
        compose.setContent { SafeHomeTheme { Column { IconoSafeHome.entries.forEach { IconoSafeHome(it, descripcion = "icono-${it.name}") } } } }
        compose.onAllNodesWithContentDescription("icono-", substring = true).assertCountEquals(IconoSafeHome.entries.size)
    }
    @Test fun navegacionConservaEtiquetasSeleccionYAccion() {
        compose.setContent { SafeHomeTheme {
            var seleccionado by remember { mutableIntStateOf(0) }
            NavegacionSafeHome(seleccionado) { seleccionado = it }
        } }
        compose.onNodeWithText("Panel").assertIsSelected()
        compose.onNodeWithText("Historial").performClick().assertIsSelected()
        compose.onNodeWithText("Cuenta").performClick().assertIsSelected()
    }
    @Test fun navegacionCompactaConFuenteAmpliadaConservaLasTresPestanas() {
        compose.setContent { SafeHomeTheme {
            val densidad = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(densidad, 1.8f)) {
                Surface(Modifier.width(320.dp)) {
                    var tab by remember { mutableIntStateOf(0) }
                    NavegacionSafeHome(tab) { tab = it }
                }
            }
        } }
        listOf("Panel", "Historial", "Cuenta").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().performClick().assertIsSelected()
        }
        guardarCaptura("navegacion-320-fuente-ampliada")
    }
    @Test fun tresAccionesMantienenSusCallbacksSinRed() {
        var activar = 0; var desactivar = 0; var silenciar = 0
        compose.setContent { SafeHomeTheme { Surface {
            PantallaSupervision(panel(Alarma.CRITICA), {}, { if (it) activar++ else desactivar++ }, { silenciar++ })
        } } }
        compose.onNodeWithText("Activar vigilancia").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Desactivar vigilancia").performScrollTo().performClick()
        compose.onNodeWithText("Silenciar alarma").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, activar); assertEquals(1, desactivar); assertEquals(1, silenciar) }
    }
    @Test fun comandoPendienteDeshabilitaControlesYNoConfirma() {
        compose.setContent { SafeHomeTheme { Surface {
            PantallaSupervision(panel().copy(mqtt = panel().mqtt!!.copy(esperando = true)), {})
        } } }
        compose.onNodeWithText("Activar vigilancia").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Desactivar vigilancia").assertIsNotEnabled()
        compose.onNodeWithText("Silenciar alarma").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Esperando respuesta…").assertExists()
    }
    @Test fun sinConexionOfreceReintentoYNoControl() {
        var veces = 0
        compose.setContent { SafeHomeTheme { Surface {
            PantallaSupervision(PanelSupervision(mqtt = PanelMqtt()), { veces++ })
        } } }
        compose.onNodeWithText("Supervisión sin conexión").assertExists()
        compose.onNodeWithText("Activar vigilancia").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reintentar conexión").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, veces) }
    }
    @Test fun alarmaNormalNoHabilitaSilenciar() {
        compose.setContent { SafeHomeTheme { Surface { PantallaSupervision(panel(), {}) } } }
        compose.onNodeWithText("Sistema normal").assertExists()
        compose.onNodeWithText("Silenciar alarma").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reintentar conexión").assertDoesNotExist()
    }
    @Test fun historialDoceEventosPaginaCincoCincoDos() {
        compose.setContent { SafeHomeTheme { Surface {
            PantallaHistorial(Lectura.Dato(HistorialSupervision(eventos(12), 0, false)), {})
        } } }
        compose.onNodeWithText("12 eventos").assertExists()
        compose.onNodeWithText("Página 1 de 3").performScrollTo().assertExists()
        compose.onNodeWithText("Anterior").assertIsNotEnabled()
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText(fechaHora(eventos(12)[5].ocurridoEn)).performScrollTo().assertExists()
        compose.onNodeWithText(fechaHora(eventos(12)[0].ocurridoEn)).assertDoesNotExist()
        compose.onNodeWithText("Página 2 de 3").performScrollTo().assertExists()
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText("Página 3 de 3").performScrollTo().assertExists()
        compose.onAllNodesWithText("Puerta abierta").assertCountEquals(2)
        compose.onNodeWithText("Siguiente").assertIsNotEnabled()
        compose.onNodeWithText("Anterior").performClick()
        compose.onNodeWithText("Página 2 de 3").performScrollTo().assertExists()
    }
    @Test fun historialVacioYErrorSonDistintos() {
        val lectura = mutableStateOf<Lectura<HistorialSupervision>>(Lectura.SinDatos)
        compose.setContent { SafeHomeTheme { Surface { PantallaHistorial(lectura.value, {}) } } }
        compose.onNodeWithText("Aún no hay eventos").assertExists()
        compose.onNodeWithText("Siguiente").assertDoesNotExist()
        compose.runOnIdle { lectura.value = Lectura.Error("error local") }
        compose.onNodeWithText("Aún no hay eventos").assertDoesNotExist()
        compose.onNodeWithText("No se pudo leer la actividad guardada.", substring = true).assertExists()
    }
    @Test fun paginaSeAjustaCuandoDisminuyeElHistorial() {
        val lista = mutableStateOf(eventos(12))
        compose.setContent { SafeHomeTheme { Surface { PantallaHistorial(Lectura.Dato(HistorialSupervision(lista.value, 0, false)), {}) } } }
        repeat(2) { compose.onNodeWithText("Siguiente").performScrollTo().performClick() }
        compose.runOnIdle { lista.value = eventos(1) }
        compose.onNodeWithText("1 evento").assertExists()
        compose.onNodeWithText("Página", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Puerta abierta").assertExists()
    }
    @Test fun paginaPersisteAlVolverYSeSeparaPorContexto() {
        val contexto = mutableStateOf("cuenta-a:hogar-a")
        val visible = mutableStateOf(true)
        compose.setContent { SafeHomeTheme {
            val guardado = rememberSaveableStateHolder()
            if (visible.value) guardado.SaveableStateProvider(contexto.value) {
                PantallaHistorial(Lectura.Dato(HistorialSupervision(eventos(12), 0, false)), {}, contexto.value, offline = true)
            }
        } }
        compose.onNodeWithText("Siguiente").performScrollTo().performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("Página 2 de 3").performScrollTo().assertExists()
        compose.runOnIdle { contexto.value = "cuenta-b:hogar-b" }
        compose.onNodeWithText("Página 1 de 3").performScrollTo().assertExists()
        compose.onNodeWithText("Disponible sin conexión").performScrollTo().assertExists()
    }
    private fun comprobarAdaptacion(ancho: Int, fuente: Float) {
        compose.setContent { SafeHomeTheme {
            val densidad = LocalDensity.current.density
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Viewport sintético: escala el render ancho para no recortarlo en el emulador físico.
                val escala = minOf(1f, maxWidth.value / ancho)
                CompositionLocalProvider(LocalDensity provides Density(densidad * escala, fuente)) {
                    Surface(Modifier.requiredWidth(ancho.dp).fillMaxHeight()) { PantallaSupervision(panel(Alarma.CRITICA), {}) }
                }
            }
        } }
        compose.onNodeWithText("Panel de control").assertExists()
        guardarCaptura("panel-${ancho}-inicio")
        compose.onNodeWithText("Activar vigilancia").performScrollTo().assertIsDisplayed()
        val boton = compose.onNodeWithText("Silenciar alarma").performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(boton.bottom - boton.top >= 48.dp)
        guardarCaptura("panel-${ancho}-controles")
    }
    private fun guardarCaptura(nombre: String) {
        val carpeta = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("ui-review")!!
        carpeta.mkdirs()
        File(carpeta, "$nombre.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun panelCompacto320FuenteAmpliada() = comprobarAdaptacion(320, 1.8f)
    @Test fun panelMediano360() = comprobarAdaptacion(360, 1f)
    @Test fun panelAmplio800() = comprobarAdaptacion(800, 1f)
    @Test fun marcoCompletoConCabeceraNavegacionYActividad() {
        compose.setContent { SafeHomeTheme {
            var tab by remember { mutableIntStateOf(0) }
            val paginas = rememberSaveableStateHolder()
            Scaffold(bottomBar = { NavegacionSafeHome(tab) { tab = it } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    CabeceraSafeHome("Ana")
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> PantallaSupervision(panel(), {})
                            1 -> paginas.SaveableStateProvider("fixture") {
                                PantallaHistorial(Lectura.Dato(HistorialSupervision(eventos(12), 0, false)), {})
                            }
                            else -> PantallaCuenta(PerfilUsuario(RolUsuario.ESTACION, "hogar-prueba", "Ana"), {})
                        }
                    }
                }
            }
        } }
        guardarCaptura("pantalla-panel")
        compose.onNode(hasText("Historial") and hasClickAction()).performClick()
        compose.onNodeWithText("Página 1 de 3").performScrollTo().assertExists()
        guardarCaptura("pantalla-historial")
        compose.onNode(hasText("Cuenta") and hasClickAction()).performClick()
        compose.onNodeWithText("Cerrar sesión").performScrollTo().assertIsDisplayed()
        guardarCaptura("pantalla-cuenta")
    }
    @Test fun cuentaSoloMuestraDatosRealesYCierraSesion() {
        var cerrada = false
        compose.setContent { SafeHomeTheme { Surface { PantallaCuenta(PerfilUsuario(RolUsuario.ESTACION, "hogar-real", "Ana")) { cerrada = true } } } }
        compose.onNodeWithText("hogar-real").assertExists()
        compose.onNodeWithText("9f21", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cerrada) }
    }
}
