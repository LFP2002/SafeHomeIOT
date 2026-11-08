package com.example.safehome

import android.content.Context
import java.util.UUID

class PreferenciasEventosHardware(context: Context) : AlmacenEventosHardware {
    private val preferencias = context.applicationContext.getSharedPreferences("historial_hardware_pendiente", Context.MODE_PRIVATE)
    override fun leer(): Map<String, *> = preferencias.all

    override fun escribir(clave: String, valor: String?): Boolean {
        val anterior = preferencias.all[clave]
        val editor = preferencias.edit()
        if (valor == null) editor.remove(clave) else editor.putString(clave, valor)
        // Fuerza una modificación incluso tras un commit fallido cuya caché ya contenga el valor.
        editor.putString("revision", UUID.randomUUID().toString())
        val confirmado = runCatching { editor.commit() }.getOrDefault(false)
        if (!confirmado && valor == null && anterior is String) {
            // SharedPreferences modifica su caché incluso si falla el disco. No perder el evento
            // de esa caché por un borrado no confirmado; el reintento remoto seguirá siendo idempotente.
            runCatching { preferencias.edit().putString(clave, anterior)
                .putString("revision", UUID.randomUUID().toString()).commit() }
        }
        return confirmado
    }
}
