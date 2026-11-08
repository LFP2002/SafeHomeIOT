package com.example.safehome

import java.util.UUID

data class EventoHardwarePendiente(
    val idEvento: String,
    val hogarId: String,
    val tipo: EventoFisico,
    val ocurridoEnMillis: Long,
    val intentos: Int = 0,
    val ultimoIntentoMillis: Long? = null
) {
    init {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(idEvento))
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(hogarId))
        require(ocurridoEnMillis in 1..253402300799999L && intentos >= 0)
        require(ultimoIntentoMillis == null || ultimoIntentoMillis in 1..253402300799999L)
    }

    companion object {
        fun crear(hogarId: String, tipo: EventoFisico, ocurridoEnMillis: Long) =
            EventoHardwarePendiente(UUID.randomUUID().toString(), hogarId, tipo, ocurridoEnMillis)
    }
}

/** Formato local versionado, una entrada por evento. Los campos validados no admiten '|'.
 * Evita que un registro corrupto impida recuperar el resto; no requiere JSON ni librerías. */
object CodecEventoHardware {
    fun serializar(evento: EventoHardwarePendiente): String = with(evento) {
        "1|$idEvento|$hogarId|${tipo.name}|$ocurridoEnMillis|$intentos|${ultimoIntentoMillis ?: ""}"
    }

    fun leer(valor: Any?): EventoHardwarePendiente? = try {
        val campos = (valor as? String)?.takeIf { it.length <= 512 }?.split('|')
        if (campos == null || campos.size != 7 || campos[0] != "1") null else EventoHardwarePendiente(
            campos[1], campos[2], EventoFisico.valueOf(campos[3]), campos[4].toLong(), campos[5].toInt(),
            campos[6].takeIf { it.isNotEmpty() }?.toLong())
    } catch (_: IllegalArgumentException) { null }
}
