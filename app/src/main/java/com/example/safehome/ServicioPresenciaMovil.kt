package com.example.safehome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ServicioPresenciaMovil : Service() {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var uid = ""
    private var hogarId = ""
    private var generacion = 0L
    private var conectado = false
    private var listenerConexion: ValueEventListener? = null
    private var callbackRed: ConnectivityManager.NetworkCallback? = null
    private var redValida = false
    private var recuperacionForzadaEjecutada = false
    private var rtdbConfirmado = false
    private var republicacionEnCurso = false
    private lateinit var vibradorDesconexion: VibradorDesconexionMovil
    private val latido = object : Runnable {
        override fun run() {
            val actual = generacion
            if (!contextoVigente(actual) || !conectado) return
            database.getReference("presencia").child(hogarId).child(uid)
                .updateChildren(mapOf("ultimoLatido" to ServerValue.TIMESTAMP))
                .addOnCompleteListener { tarea ->
                    if (!contextoVigente(actual) || !conectado) return@addOnCompleteListener
                    if (tarea.isSuccessful) {
                        diagnosticar("heartbeat_enviado", null)
                        handler.postDelayed(this, INTERVALO_LATIDO_MS)
                    } else diagnosticar("presencia_error", tarea.exception)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nuevoUid = intent?.getStringExtra(EXTRA_UID).orEmpty()
        val nuevoHogar = intent?.getStringExtra(EXTRA_HOGAR).orEmpty()
        if (nuevoUid.isBlank() || nuevoHogar.isBlank() || auth.currentUser?.uid != nuevoUid) { estadoInterno.value = EstadoPresenciaServicio.NO_DISPONIBLE; stopSelf(); return START_NOT_STICKY }
        if (uid != nuevoUid || hogarId != nuevoHogar || listenerConexion == null) {
            detenerConexion(false)
            uid = nuevoUid
            hogarId = nuevoHogar
            generacion++
            iniciarListenerConexion()
        }
        asegurarCallbackRed()
        crearNotificacion()
        startForeground(ID_NOTIFICACION, notificacion())
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        vibradorDesconexion = VibradorDesconexionMovil(this)
    }

    private fun iniciarListenerConexion() {
        if (!debeInstalarListenerConexion(contextoVigente(generacion), listenerConexion != null)) {
            if (!contextoVigente(generacion)) stopSelf()
            return
        }
        val info = database.getReference(".info/connected")
        val contexto = generacion
        listenerConexion = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!contextoVigente(contexto)) return
                val estaConectado = snapshot.getValue(Boolean::class.java) == true
                diagnosticar("rtdb_info_connected valor=$estaConectado", null)
                if (!estaConectado) {
                    rtdbConfirmado = false
                    conectado = false
                    handler.removeCallbacks(latido)
                    actualizarEstado(EstadoPresenciaServicio.SIN_CONEXION)
                    return
                }
                rtdbConfirmado = true
                cancelarWatchdogsRecuperacion()
                if (!debeRepublicarTrasConfirmacionRtdb(contextoVigente(contexto), rtdbConfirmado, conectado, republicacionEnCurso)) return
                actualizarEstado(EstadoPresenciaServicio.CONECTANDO)
                reconectar(contexto)
            }
            override fun onCancelled(error: DatabaseError) {
                if (contextoVigente(contexto)) {
                    rtdbConfirmado = false
                    conectado = false
                    handler.removeCallbacks(latido)
                    actualizarEstado(EstadoPresenciaServicio.ERROR)
                    diagnosticar("presencia_error", error.toException())
                }
            }
        }
        info.addValueEventListener(listenerConexion!!)
        diagnosticar("rtdb_listener_instalado", null)
    }

    private fun reconectar(contexto: Long) {
        handler.removeCallbacks(latido)
        conectado = false
        republicacionEnCurso = true
        val referencia = database.getReference("presencia").child(hogarId).child(uid)
        val fuera = mapOf("conectado" to false, "ultimaConexion" to ServerValue.TIMESTAMP, "ultimoLatido" to ServerValue.TIMESTAMP)
        referencia.onDisconnect().setValue(fuera).addOnCompleteListener { preparada ->
            if (!contextoVigente(contexto) || !rtdbConfirmado) { republicacionEnCurso = false; return@addOnCompleteListener }
            if (!preparada.isSuccessful) { republicacionEnCurso = false; actualizarEstado(EstadoPresenciaServicio.ERROR); diagnosticar("presencia_error", preparada.exception); return@addOnCompleteListener }
            referencia.setValue(mapOf("conectado" to true, "ultimaConexion" to ServerValue.TIMESTAMP, "ultimoLatido" to ServerValue.TIMESTAMP)).addOnCompleteListener { publicada ->
                if (!contextoVigente(contexto) || !rtdbConfirmado) { republicacionEnCurso = false; return@addOnCompleteListener }
                if (!publicada.isSuccessful) { republicacionEnCurso = false; actualizarEstado(EstadoPresenciaServicio.ERROR); diagnosticar("presencia_error", publicada.exception); return@addOnCompleteListener }
                conectado = true
                republicacionEnCurso = false
                actualizarEstado(EstadoPresenciaServicio.ACTIVA)
                diagnosticar("presencia_republicada", null)
                handler.removeCallbacks(latido)
                handler.postDelayed(latido, INTERVALO_LATIDO_MS)
            }
        }
    }

    private fun contextoVigente(contexto: Long): Boolean = contexto == generacion && uid.isNotBlank() && hogarId.isNotBlank() && auth.currentUser?.uid == uid

    private fun asegurarCallbackRed() {
        if (callbackRed != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callbackRed = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val valida = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (valida) recuperarRed() else perderRed()
            }
            override fun onLost(network: Network) { perderRed() }
        }
        try { manager.registerDefaultNetworkCallback(callbackRed!!) } catch (error: Exception) { callbackRed = null; diagnosticar("recuperacion_error", error) }
    }

    private fun perderRed() {
        if (!redValida && estadoInterno.value == EstadoPresenciaServicio.SIN_CONEXION) return
        redValida = false
        recuperacionForzadaEjecutada = false
        rtdbConfirmado = false
        conectado = false
        handler.removeCallbacks(latido)
        cancelarWatchdogsRecuperacion()
        actualizarEstado(EstadoPresenciaServicio.SIN_CONEXION)
        diagnosticar("red_perdida", null)
    }

    private fun recuperarRed() {
        if (!contextoVigente(generacion)) return
        if (redValida) return
        redValida = true
        recuperacionForzadaEjecutada = false
        actualizarEstado(EstadoPresenciaServicio.CONECTANDO)
        diagnosticar("red_validada", null)
        try { database.goOnline() } catch (error: Exception) { diagnosticar("presencia_error", error); return }
        diagnosticar("recuperacion_solicitada", null)
        handler.removeCallbacks(watchdogRecuperacion)
        handler.postDelayed(watchdogRecuperacion, ESPERA_WATCHDOG_RECUPERACION_MS)
    }

    private val watchdogRecuperacion: Runnable = Runnable {
        if (!debeEjecutarRecuperacionForzada(contextoVigente(generacion), redValida, rtdbConfirmado, recuperacionForzadaEjecutada)) return@Runnable
        recuperacionForzadaEjecutada = true
        actualizarEstado(EstadoPresenciaServicio.CONECTANDO)
        handler.removeCallbacks(latido)
        diagnosticar("recuperacion_forzada_inicio", null)
        try { database.goOffline() } catch (error: Exception) { diagnosticar("presencia_error", error); actualizarEstado(EstadoPresenciaServicio.SIN_CONEXION); return@Runnable }
        handler.postDelayed({
            if (!contextoVigente(generacion) || !redValida || rtdbConfirmado) return@postDelayed
            try {
                database.goOnline()
                diagnosticar("recuperacion_forzada_fin", null)
                // Algunos dispositivos no vuelven a emitir /.info/connected para el listener
                // creado antes de goOffline(). Se reinstala sin cambiar generación ni contexto.
                handler.postDelayed({ reinstalarListenerConexionTrasRecuperacion() }, PAUSA_REINSTALACION_LISTENER_MS)
                handler.postDelayed(verificacionRecuperacionForzada, ESPERA_WATCHDOG_RECUPERACION_MS)
            } catch (error: Exception) { diagnosticar("presencia_error", error); actualizarEstado(EstadoPresenciaServicio.SIN_CONEXION) }
        }, PAUSA_GO_OFFLINE_MS)
    }

    private fun reinstalarListenerConexionTrasRecuperacion() {
        if (!contextoVigente(generacion) || !redValida || rtdbConfirmado) return
        listenerConexion?.let { database.getReference(".info/connected").removeEventListener(it) }
        listenerConexion = null
        iniciarListenerConexion()
        diagnosticar("rtdb_listener_reinstalado", null)
    }

    private val verificacionRecuperacionForzada: Runnable = Runnable {
        if (contextoVigente(generacion) && redValida && !rtdbConfirmado) actualizarEstado(EstadoPresenciaServicio.SIN_CONEXION)
    }

    private fun cancelarWatchdogsRecuperacion() {
        handler.removeCallbacks(watchdogRecuperacion)
        handler.removeCallbacks(verificacionRecuperacionForzada)
    }

    private fun detenerConexion(publicarDesconexion: Boolean) {
        generacion++
        conectado = false
        estadoInterno.value = EstadoPresenciaServicio.NO_DISPONIBLE
        handler.removeCallbacks(latido)
        cancelarWatchdogsRecuperacion()
        listenerConexion?.let { database.getReference(".info/connected").removeEventListener(it); diagnosticar("rtdb_listener_retirado", null) }
        listenerConexion = null
        callbackRed?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callbackRed = null
        redValida = false
        rtdbConfirmado = false
        republicacionEnCurso = false
        if (publicarDesconexion && uid.isNotBlank() && hogarId.isNotBlank()) database.getReference("presencia").child(hogarId).child(uid).setValue(mapOf("conectado" to false, "ultimaConexion" to ServerValue.TIMESTAMP, "ultimoLatido" to ServerValue.TIMESTAMP))
    }

    override fun onDestroy() { detenerConexion(true); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun crearNotificacion() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CANAL, "Presencia SafeHome", NotificationManager.IMPORTANCE_LOW)) }
    private fun actualizarEstado(nuevo: EstadoPresenciaServicio) { vibradorDesconexion.procesar(nuevo); estadoInterno.value = nuevo; getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, notificacion()) }
    private fun notificacion() = NotificationCompat.Builder(this, CANAL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("SafeHome").setContentText(textoNotificacion(estadoInterno.value)).setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).build()
    private fun textoNotificacion(estado: EstadoPresenciaServicio): String = when (estado) { EstadoPresenciaServicio.ACTIVA -> "Presencia activa"; EstadoPresenciaServicio.SIN_CONEXION -> "Esperando conexión"; EstadoPresenciaServicio.CONECTANDO -> "Restableciendo presencia"; EstadoPresenciaServicio.ERROR, EstadoPresenciaServicio.NO_DISPONIBLE -> "Restableciendo presencia" }
    private fun diagnosticar(etapa: String, error: Exception?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Log.d("SafeHomePresencia", "etapa=$etapa tipo=${error?.javaClass?.simpleName ?: "sin_error"} mensaje=${error?.message ?: "sin_mensaje"}")
    }
    companion object { const val EXTRA_UID = "uid"; const val EXTRA_HOGAR = "hogar"; private const val CANAL = "presencia"; private const val ID_NOTIFICACION = 41; private const val INTERVALO_LATIDO_MS = 30_000L; private const val ESPERA_WATCHDOG_RECUPERACION_MS = 15_000L; private const val PAUSA_GO_OFFLINE_MS = 1_000L; private const val PAUSA_REINSTALACION_LISTENER_MS = 500L; private val estadoInterno = MutableStateFlow(EstadoPresenciaServicio.NO_DISPONIBLE); val estado: StateFlow<EstadoPresenciaServicio> = estadoInterno }
}

enum class EstadoPresenciaServicio { CONECTANDO, ACTIVA, SIN_CONEXION, ERROR, NO_DISPONIBLE }
