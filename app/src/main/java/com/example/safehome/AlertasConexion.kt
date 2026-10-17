package com.example.safehome

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val CANAL_CONEXION = "safehome_conexion"
private const val CANAL_RECONEXION = "safehome_reconexion"
private const val ID_ALERTA_CONEXION_BASE = 6_000
private val PATRON_VIBRACION_CONEXION = longArrayOf(0, 120, 80, 120)

class VibradorDesconexionMovil(context: Context) {
    private val contexto = context.applicationContext
    private var estadoAnterior: EstadoPresenciaServicio? = null

    fun procesar(estadoActual: EstadoPresenciaServicio) {
        val debeVibrar = debeVibrarPorDesconexionMovil(estadoAnterior, estadoActual)
        estadoAnterior = estadoActual
        if (!debeVibrar) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = contexto.getSystemService(VibratorManager::class.java)
                val vibrador = manager?.defaultVibrator
                if (vibrador?.hasVibrator() == true) vibrador.vibrate(VibrationEffect.createWaveform(PATRON_VIBRACION_CONEXION, -1)) else return
            } else {
                @Suppress("DEPRECATION")
                val vibrador = contexto.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrador?.hasVibrator() == true) {
                    @Suppress("DEPRECATION")
                    vibrador.vibrate(PATRON_VIBRACION_CONEXION, -1)
                } else return
            }
            diagnosticarAlertaConexion(contexto, "movil_vibracion_desconexion")
        } catch (_: Exception) {
            diagnosticarAlertaConexion(contexto, "alerta_error")
        }
    }
}

class NotificadorAlertasConexion(context: Context) {
    private val contexto = context.applicationContext
    private val seguimientoPorMovil = mutableMapOf<String, SeguimientoAlertaConexion>()

    fun requierePermisoNotificaciones(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun prepararCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(CANAL_CONEXION, "Conexión de móviles", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = PATRON_VIBRACION_CONEXION
        }
        val reconexion = NotificationChannel(CANAL_RECONEXION, "Reconexión de móviles", NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(false)
            setShowBadge(false)
        }
        contexto.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(canal)
            createNotificationChannel(reconexion)
        }
    }

    fun procesar(presencia: PresenciaMovilUi) {
        val anterior = seguimientoPorMovil[presencia.uidInterno] ?: SeguimientoAlertaConexion()
        val decision = decidirAlertaConexion(anterior, presencia.estadoFinal)
        seguimientoPorMovil[presencia.uidInterno] = decision.seguimiento
        when {
            decision.esEstadoInicial -> diagnosticarAlertaConexion(contexto, "alerta_descartada_estado_inicial")
            decision.esDuplicada -> diagnosticarAlertaConexion(contexto, "alerta_descartada_duplicada")
            decision.debeCancelarPorReconexión -> {
                publicarReconexion(presencia)
            }
            decision.debeAlertarDesconexion -> publicarDesconexion(presencia)
        }
    }

    fun reiniciar() {
        seguimientoPorMovil.keys.forEach { contexto.getSystemService(NotificationManager::class.java).cancel(idNotificacion(it)) }
        seguimientoPorMovil.clear()
    }

    private fun publicarDesconexion(presencia: PresenciaMovilUi) {
        if (requierePermisoNotificaciones()) return
        try {
            prepararCanal()
            val notificacion = Notification.Builder(contexto, CANAL_CONEXION)
                .setSmallIcon(R.drawable.ic_phone_disabled)
                .setContentTitle("Móvil desconectado")
                .setContentText("${presencia.nombreVisible} ya no tiene conexión.")
                .setAutoCancel(true)
                .build()
            contexto.getSystemService(NotificationManager::class.java).notify(idNotificacion(presencia.uidInterno), notificacion)
            diagnosticarAlertaConexion(contexto, "estacion_alerta_desconexion")
        } catch (_: Exception) {
            diagnosticarAlertaConexion(contexto, "alerta_error")
        }
    }

    private fun publicarReconexion(presencia: PresenciaMovilUi) {
        if (requierePermisoNotificaciones()) return
        try {
            prepararCanal()
            val notificacion = Notification.Builder(contexto, CANAL_RECONEXION)
                .setSmallIcon(R.drawable.ic_supervision)
                .setContentTitle("Móvil reconectado")
                .setContentText("${presencia.nombreVisible} volvió a estar activo.")
                .setAutoCancel(true)
                .build()
            contexto.getSystemService(NotificationManager::class.java).notify(idNotificacion(presencia.uidInterno), notificacion)
            diagnosticarAlertaConexion(contexto, "estacion_alerta_reconectado")
        } catch (_: Exception) {
            diagnosticarAlertaConexion(contexto, "alerta_error")
        }
    }

    private fun idNotificacion(uidInterno: String): Int = ID_ALERTA_CONEXION_BASE + (uidInterno.hashCode() and 0x0FFF)
}

private fun diagnosticarAlertaConexion(context: Context, etapa: String) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    Log.d("SafeHomeAlertasConexion", etapa)
}
