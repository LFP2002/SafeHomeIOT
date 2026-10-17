package com.example.safehome

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class DecisionNotificacion(
    val nivelRecordado: NivelEstadoHogar?,
    val debeNotificar: Boolean
)

fun decidirNotificacionHogar(
    nivelAnterior: NivelEstadoHogar?,
    nivelActual: NivelEstadoHogar,
    confirmadoPorServidor: Boolean = true
): DecisionNotificacion {
    if (!confirmadoPorServidor) return DecisionNotificacion(nivelAnterior, false)
    return DecisionNotificacion(
        nivelRecordado = nivelActual,
        debeNotificar = nivelAnterior != null && nivelAnterior != nivelActual && nivelActual != NivelEstadoHogar.NORMAL
    )
}

class NotificadorAlertasHogar(context: Context) {
    private val contexto = context.applicationContext
    private var ultimoNivel: NivelEstadoHogar? = null

    fun requierePermisoNotificaciones(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun procesarEstadoConfirmado(nivel: NivelEstadoHogar) {
        val decision = decidirNotificacionHogar(ultimoNivel, nivel)
        ultimoNivel = decision.nivelRecordado
        if (!decision.debeNotificar || requierePermisoNotificaciones()) return
        prepararCanales()
        val alerta = when (nivel) {
            NivelEstadoHogar.ADVERTENCIA -> AlertaLocal(
                CANAL_ADVERTENCIA, "SafeHome: advertencia",
                "No hay señal de presencia y la puerta está cerrada.", 2001
            )
            NivelEstadoHogar.CRITICO -> AlertaLocal(
                CANAL_CRITICO, "SafeHome: alerta crítica",
                "No hay señal de presencia y la puerta está abierta.", 2002
            )
            NivelEstadoHogar.NORMAL -> return
        }
        val notificacion = Notification.Builder(contexto, alerta.canal)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alerta.titulo)
            .setContentText(alerta.mensaje)
            .setAutoCancel(true)
            .build()
        contexto.getSystemService(NotificationManager::class.java).notify(alerta.id, notificacion)
    }

    fun reiniciarSesion() {
        ultimoNivel = null
    }

    fun prepararCanales() {
        crearCanales()
    }

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val administrador = contexto.getSystemService(NotificationManager::class.java)
        val advertencia = NotificationChannel(
            CANAL_ADVERTENCIA,
            "Advertencias SafeHome",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 100, 180)
        }
        val critico = NotificationChannel(
            CANAL_CRITICO,
            "Alertas críticas SafeHome",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 120, 350, 120, 350)
        }
        administrador.createNotificationChannels(listOf(advertencia, critico))
    }

    private companion object {
        const val CANAL_ADVERTENCIA = "safehome_advertencias"
        const val CANAL_CRITICO = "safehome_criticas"
    }
}

private data class AlertaLocal(val canal: String, val titulo: String, val mensaje: String, val id: Int)
