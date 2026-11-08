package com.example.safehome

enum class ConexionUsbPortatil {
    USB_NO_DISPONIBLE, SIN_DISPOSITIVO, PERMISO_REQUERIDO, ABRIENDO,
    CONECTADO_NO_VERIFICADO, CONECTADO_VERIFICADO, DESCONECTADO, ERROR
}
enum class ConexionRedPortatil { DESCONOCIDA, CONECTADO, DESCONECTADO }
enum class HeartbeatPortatil { DESCONOCIDO, ACTIVO, INACTIVO }
enum class PruebaPortatil { SIN_PROBAR, ESPERANDO, RESPUESTA_VALIDA, SIN_RESPUESTA }

/** Lista cerrada: la cuenta Móvil no dispone de comandos de actuadores. */
enum class ComandoPortatil(val valor: String) { HELLO("APP_MOBILE"), PING("1"), STATUS("ALL") }
enum class CampoDiagnosticoPortatil { WIFI, RSSI, MQTT, HEARTBEAT }
data class OperacionPortatil(val comando: ComandoPortatil, val secuencia: Long,
    val camposRecibidos: Set<CampoDiagnosticoPortatil> = emptySet())

data class DispositivoUsbPortatil(val clave: String, val nombre: String, val vendorId: Int, val productId: Int)

data class EstadoHardwarePortatil(
    val conexion: ConexionUsbPortatil = ConexionUsbPortatil.SIN_DISPOSITIVO,
    val dispositivos: List<DispositivoUsbPortatil> = emptyList(),
    val claveUsb: String? = null,
    val nombreUsb: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val idSafeHome: String? = null,
    val rolSafeHome: String? = null,
    val wifi: ConexionRedPortatil = ConexionRedPortatil.DESCONOCIDA,
    val rssi: Int? = null,
    val mqtt: ConexionRedPortatil = ConexionRedPortatil.DESCONOCIDA,
    val heartbeat: HeartbeatPortatil = HeartbeatPortatil.DESCONOCIDO,
    val ultimoMensajeValido: MensajeSafeHome? = null,
    val error: String? = null,
    val operacion: OperacionPortatil? = null,
    val prueba: PruebaPortatil = PruebaPortatil.SIN_PROBAR,
    val solicitandoPermiso: Boolean = false
) {
    val conectado get() = conexion in setOf(ConexionUsbPortatil.CONECTADO_NO_VERIFICADO, ConexionUsbPortatil.CONECTADO_VERIFICADO)
    val verificado get() = conexion == ConexionUsbPortatil.CONECTADO_VERIFICADO
}

fun invalidarEstadoPortatil(estado: EstadoHardwarePortatil) = EstadoHardwarePortatil(
    conexion = ConexionUsbPortatil.DESCONECTADO, dispositivos = estado.dispositivos,
    error = "Dispositivo portátil desconectado."
)

/** Se usa bajo el monitor del transporte; también permite probar callbacks tardíos en JVM. */
class SesionUsbPortatil {
    var generacion: Long = 0
        private set
    var estado = EstadoHardwarePortatil()
        private set

    fun reiniciar(nuevo: EstadoHardwarePortatil): Long {
        generacion++
        estado = nuevo
        return generacion
    }

    fun actualizar(id: Long, cambio: (EstadoHardwarePortatil) -> EstadoHardwarePortatil): Boolean {
        if (id != generacion) return false
        estado = cambio(estado)
        return true
    }

    fun finalizarSesion() = reiniciar(EstadoHardwarePortatil())
}
