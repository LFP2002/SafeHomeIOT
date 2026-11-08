package com.example.safehome

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Disparadores Android; la decisión y la cola son independientes y comprobables en JVM. */
class GestorHistorialHardware(context: Context, firestore: FirebaseFirestore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val motor = SincronizadorEventosHardware(ColaEventosHardwareLocal(PreferenciasEventosHardware(context), alSaturar = {
        android.util.Log.w("SafeHomeHistorial", "Cola local de historial saturada.")
    }),
        DestinoFirestoreHistorialHardware(firestore))
    val estado = motor.estado
    private val solicitudes = Channel<Unit>(Channel.CONFLATED)
    private val manual = AtomicBoolean(false)
    @Volatile private var tarea: Job? = null
    @Volatile private var cerrado = false
    @Volatile private var redValidada = false
    private val conectividad = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val disponible = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (disponible && !redValidada) solicitar()
            redValidada = disponible
        }
        override fun onLost(network: Network) { redValidada = false }
    }
    private var callbackRegistrado = false

    init {
        try { conectividad?.registerDefaultNetworkCallback(callback); callbackRegistrado = conectividad != null }
        catch (_: Exception) { /* Foreground, evento y acción manual siguen disponibles. */ }
        scope.launch {
            // Espera señales, no hace polling. Cada señal admite solo tres intentos acotados.
            for (solicitud in solicitudes) {
                val esManual = manual.getAndSet(false)
                val trabajo = launch {
                    motor.refrescar()
                    for (intento in 0..2) {
                        val resultado = motor.sincronizar(manual = esManual && intento == 0)
                        if (resultado != ResultadoDestinoHardware.TRANSITORIO) break
                        if (intento < 2) delay(if (intento == 0) 30_000L else 60_000L)
                    }
                }
                tarea = trabajo
                trabajo.join()
            }
        }
    }

    fun establecerHogar(hogarId: String?) {
        manual.set(false)
        tarea?.cancel()
        motor.establecerHogar(hogarId)
        solicitar()
    }

    suspend fun registrar(hogarId: String, tipo: EventoFisico, ocurridoEnMillis: Long): Boolean {
        val guardado = motor.registrar(hogarId, tipo, ocurridoEnMillis)
        if (guardado) solicitar()
        return guardado
    }

    fun solicitar(esManual: Boolean = false) {
        if (cerrado) return
        if (esManual) manual.set(true)
        solicitudes.trySend(Unit)
    }

    fun cerrar() {
        cerrado = true
        motor.establecerHogar(null)
        if (callbackRegistrado) runCatching { conectividad?.unregisterNetworkCallback(callback) }
        callbackRegistrado = false
        solicitudes.close()
        scope.cancel()
    }
}
