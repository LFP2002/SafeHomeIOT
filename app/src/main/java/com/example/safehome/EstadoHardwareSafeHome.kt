package com.example.safehome

enum class ConexionEstacionIoT {
    SIN_PERMISO, BLUETOOTH_NO_DISPONIBLE, BLUETOOTH_APAGADO, DESCONECTADO,
    CONECTANDO, CONECTADO_NO_VERIFICADO, CONECTADO_VERIFICADO, ERROR
}
enum class SistemaHardware { DESCONOCIDO, ENCENDIDO, APAGADO }
enum class PuertaHardware { DESCONOCIDA, ABIERTA, CERRADA }
enum class PortatilHardware { DESCONOCIDO, PRESENTE, FUERA_RANGO }
enum class AlarmaHardware { DESCONOCIDA, NINGUNA, ADVERTENCIA, CRITICA }
enum class PruebaComunicacion { SIN_PROBAR, ESPERANDO, RESPUESTA_VALIDA, SIN_RESPUESTA }

enum class AccionHardware(val dato: String, val valor: String) {
    ENCENDER("SYSTEM", "ON"), APAGAR("SYSTEM", "OFF"), RECONOCER_ALARMA("ALARM", "ACK")
}

data class OperacionHardware(val secuencia: Long, val accion: AccionHardware, val ackRecibido: Boolean = false)

data class DispositivoBluetoothVinculado(val nombre: String, val direccion: String)

data class EstadoHardwareSafeHome(
    val conexion: ConexionEstacionIoT = ConexionEstacionIoT.DESCONECTADO,
    val nombreBluetooth: String? = null,
    val direccionBluetooth: String? = null,
    val idDispositivo: String? = null,
    val rolDispositivo: String? = null,
    val ultimoMensajeValido: MensajeSafeHome? = null,
    val error: String? = null,
    val dispositivos: List<DispositivoBluetoothVinculado> = emptyList(),
    val dispositivosConsultados: Boolean = false,
    val sistema: SistemaHardware = SistemaHardware.DESCONOCIDO,
    val puerta: PuertaHardware = PuertaHardware.DESCONOCIDA,
    val portatil: PortatilHardware = PortatilHardware.DESCONOCIDO,
    val alarma: AlarmaHardware = AlarmaHardware.DESCONOCIDA,
    val prueba: PruebaComunicacion = PruebaComunicacion.SIN_PROBAR,
    val operacion: OperacionHardware? = null,
    val errorOperacion: String? = null,
    val errorSincronizacion: String? = null
) {
    val verificado: Boolean get() = conexion == ConexionEstacionIoT.CONECTADO_VERIFICADO
    val conectado: Boolean get() = conexion == ConexionEstacionIoT.CONECTADO_NO_VERIFICADO ||
        conexion == ConexionEstacionIoT.CONECTADO_VERIFICADO
}

/** Invalida también operaciones: desconectar jamás envía SYSTEM OFF al ESP32. */
fun invalidarEstadoHardware(estado: EstadoHardwareSafeHome): EstadoHardwareSafeHome = EstadoHardwareSafeHome(
    nombreBluetooth = estado.nombreBluetooth, direccionBluetooth = estado.direccionBluetooth,
    dispositivos = estado.dispositivos, dispositivosConsultados = estado.dispositivosConsultados
)
