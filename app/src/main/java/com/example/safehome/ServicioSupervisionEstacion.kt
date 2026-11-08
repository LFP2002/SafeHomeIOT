package com.example.safehome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Mantiene priorizado el proceso de la Estación mientras supervisa móviles vinculados. */
class ServicioSupervisionEstacion : Service() {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val presencias = mutableMapOf<String, EstadoPresenciaMovilUi>()
    private val nombres = mutableMapOf<String, String>()
    private val listeners = mutableMapOf<String, Pair<DatabaseReference, ValueEventListener>>()
    private var roster: ListenerRegistration? = null
    private var reloj: ValueEventListener? = null
    private var conexion: ValueEventListener? = null
    private var uid = ""
    private var hogar = ""
    private var generacion = 0L
    private var desfase = 0L
    private var servidorConectado = false
    private lateinit var notificador: NotificadorAlertasConexion
    private val sesion = FirebaseAuth.AuthStateListener {
        if (uid.isNotBlank() && it.currentUser?.uid != uid) stopSelf()
    }
    private val tick = object : Runnable {
        override fun run() {
            publicar()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificador = NotificadorAlertasConexion(this)
        auth.addAuthStateListener(sesion)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nuevoUid = intent?.getStringExtra(EXTRA_UID).orEmpty()
        val nuevoHogar = intent?.getStringExtra(EXTRA_HOGAR).orEmpty()
        if (nuevoUid.isBlank() || nuevoHogar.isBlank() || auth.currentUser?.uid != nuevoUid) {
            stopSelf()
            return START_NOT_STICKY
        }
        crearCanal()
        startForeground(ID_NOTIFICACION, notificacion())
        if (uid != nuevoUid || hogar != nuevoHogar) {
            limpiar()
            uid = nuevoUid
            hogar = nuevoHogar
            observar()
        }
        return START_NOT_STICKY
    }

    private fun observar() {
        val actual = generacion
        fun vigente() = actual == generacion && auth.currentUser?.uid == uid
        reloj = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (vigente()) desfase = (snapshot.value as? Number)?.toLong() ?: 0L
            }
            override fun onCancelled(error: DatabaseError) { Log.w("SafeHomeSupervision", "No se pudo obtener el desfase codigo=${error.code}") }
        }.also { database.getReference(".info/serverTimeOffset").addValueEventListener(it) }
        conexion = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (vigente()) servidorConectado = snapshot.getValue(Boolean::class.java) == true
            }
            override fun onCancelled(error: DatabaseError) { servidorConectado = false }
        }.also { database.getReference(".info/connected").addValueEventListener(it) }
        roster = FirebaseFirestore.getInstance().collection("hogares").document(hogar)
            .collection("moviles").addSnapshotListener { snapshot, error ->
                if (!vigente()) return@addSnapshotListener
                if (error != null) {
                    Log.w("SafeHomeSupervision", "Error al observar móviles tipo=${error.javaClass.simpleName}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val aprobados = snapshot.documents.mapNotNull { documento ->
                    val id = documento.getString("uidMovil") ?: return@mapNotNull null
                    val nombre = documento.getString("nombre") ?: return@mapNotNull null
                    if (id != documento.id || validarNombreVisible(nombre) != null) null else id to nombre
                }.toMap()
                (listeners.keys - aprobados.keys).forEach { id ->
                    listeners.remove(id)?.let { (ref, listener) -> ref.removeEventListener(listener) }
                    presencias.remove(id)
                    notificador.olvidar(id)
                }
                nombres.clear()
                nombres.putAll(aprobados)
                aprobados.keys.filter { it !in listeners }.forEach { id ->
                    val ref = database.getReference("presencia").child(hogar).child(id)
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!vigente() || id !in nombres) return
                            presencias[id] = clasificarPresenciaMovil(snapshot.value)
                            publicar()
                        }
                        override fun onCancelled(error: DatabaseError) {
                            if (!vigente() || id !in nombres) return
                            presencias[id] = EstadoPresenciaMovilUi.Error("Error de conexión")
                            publicar()
                        }
                    }
                    listeners[id] = ref to listener
                    ref.addValueEventListener(listener)
                }
                publicar()
            }
        handler.post(tick)
    }

    private fun publicar() {
        if (uid.isBlank() || auth.currentUser?.uid != uid) return
        val hora = System.currentTimeMillis() + desfase
        val ui = nombres.map { (id, nombre) -> PresenciaMovilUi(id, nombre, estadoVisualPresencia(presencias[id], hora)) }
        estadoInterno.value = EstadoSupervisionEstacion(uid, hogar, presencias.toMap(), ui, desfase)
        // Sin conexión de la estación no podemos atribuir la falta de datos al móvil.
        if (servidorConectado) ui.forEach(notificador::procesar)
    }

    private fun limpiar() {
        generacion++
        handler.removeCallbacks(tick)
        roster?.remove()
        roster = null
        reloj?.let { database.getReference(".info/serverTimeOffset").removeEventListener(it) }
        conexion?.let { database.getReference(".info/connected").removeEventListener(it) }
        reloj = null
        conexion = null
        listeners.values.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        listeners.clear()
        nombres.clear()
        presencias.clear()
        notificador.reiniciar()
        uid = ""
        hogar = ""
        desfase = 0L
        servidorConectado = false
        estadoInterno.value = EstadoSupervisionEstacion()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("SafeHomeSupervision", "Android agotó el tiempo de supervisión dataSync en segundo plano")
        limpiar()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        auth.removeAuthStateListener(sesion)
        limpiar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun crearCanal() {
        val canal = NotificationChannel(
            CANAL_SUPERVISION,
            "Supervisión de la estación",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = "Mantiene activa la supervisión de móviles vinculados."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacion() = NotificationCompat.Builder(this, CANAL_SUPERVISION)
        .setSmallIcon(R.drawable.ic_supervision)
        .setContentTitle("SafeHome · Supervisión activa")
        .setContentText("La Estación supervisa los móviles vinculados.")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_HOGAR = "hogar"
        private val estadoInterno = MutableStateFlow(EstadoSupervisionEstacion())
        val estado: StateFlow<EstadoSupervisionEstacion> = estadoInterno
        private const val CANAL_SUPERVISION = "safehome_supervision_estacion"
        private const val ID_NOTIFICACION = 42
    }
}

data class EstadoSupervisionEstacion(
    val uid: String = "",
    val hogarId: String = "",
    val presencias: Map<String, EstadoPresenciaMovilUi> = emptyMap(),
    val ui: List<PresenciaMovilUi> = emptyList(),
    val desfase: Long = 0L
)
