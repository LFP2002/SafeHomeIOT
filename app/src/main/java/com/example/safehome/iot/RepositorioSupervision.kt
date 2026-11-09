package com.example.safehome.iot

fun interface Suscripcion { fun cancelar() }

/** Contrato de solo lectura. No expone publicación de eventos ni comandos físicos. */
interface RepositorioSupervision {
    fun observarDispositivo(hogar: String, dispositivo: Dispositivo, recibir: (Lectura<EstadoDispositivo>) -> Unit): Suscripcion
    fun observarEstadoFisico(hogar: String, recibir: (Lectura<EstadoFisico>) -> Unit): Suscripcion
    fun observarHistorial(hogar: String, recibir: (Lectura<HistorialSupervision>) -> Unit): Suscripcion
}

data class SesionSupervision(val uid: String, val hogar: String)
data class PanelSupervision(
    val estacion: Lectura<EstadoDispositivo> = Lectura.Cargando,
    val portatil: Lectura<EstadoDispositivo> = Lectura.Cargando,
    val fisico: Lectura<EstadoFisico> = Lectura.Cargando,
    val historial: Lectura<HistorialSupervision> = Lectura.Cargando)

/** Serializa cambios de contexto y callbacks, incluso los ya encolados al cancelar. */
class CoordinadorSupervision(private val repositorio: RepositorioSupervision,
    private val publicar: (PanelSupervision) -> Unit) {
    private var generacion = 0L
    private var sesion: SesionSupervision? = null
    private var panel = PanelSupervision()
    private val suscripciones = mutableListOf<Suscripcion>()

    @Synchronized fun iniciar(nueva: SesionSupervision) {
        cerrar()
        require(nueva.uid.isNotBlank() && Regex("[A-Za-z0-9_-]{1,64}").matches(nueva.hogar))
        sesion = nueva
        val token = generacion
        fun recibir(cambio: (PanelSupervision) -> PanelSupervision) = actualizar(token, nueva, cambio)
        suscripciones += repositorio.observarDispositivo(nueva.hogar, Dispositivo.ESTACION) { dato -> recibir { it.copy(estacion = dato) } }
        suscripciones += repositorio.observarDispositivo(nueva.hogar, Dispositivo.PORTATIL) { dato -> recibir { it.copy(portatil = dato) } }
        suscripciones += repositorio.observarEstadoFisico(nueva.hogar) { dato -> recibir { it.copy(fisico = dato) } }
        suscripciones += repositorio.observarHistorial(nueva.hogar) { dato -> recibir { it.copy(historial = dato) } }
    }

    @Synchronized private fun actualizar(token: Long, contexto: SesionSupervision, cambio: (PanelSupervision) -> PanelSupervision) {
        if (token != generacion || sesion != contexto) return
        panel = cambio(panel)
        publicar(panel)
    }

    @Synchronized fun cerrar() {
        generacion++
        sesion = null
        suscripciones.forEach { it.cancelar() }
        suscripciones.clear()
        panel = PanelSupervision()
        publicar(panel)
    }
}
