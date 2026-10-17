package com.example.safehome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Mantiene priorizado el proceso de la Estación mientras supervisa móviles vinculados. */
class ServicioSupervisionEstacion : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        crearCanal()
        startForeground(ID_NOTIFICACION, notificacion())
        return START_NOT_STICKY
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
        private const val CANAL_SUPERVISION = "safehome_supervision_estacion"
        private const val ID_NOTIFICACION = 42
    }
}
