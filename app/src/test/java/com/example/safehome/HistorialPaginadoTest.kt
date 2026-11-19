package com.example.safehome

import com.example.safehome.iot.*
import com.example.safehome.presentacion.*
import org.junit.Assert.*
import org.junit.Test

class HistorialPaginadoTest {
    @Test fun cantidadesYUltimaPagina() {
        for ((cantidad, paginas) in listOf(0 to 0, 1 to 1, 5 to 1, 6 to 2, 10 to 2, 12 to 3)) {
            val fuente = (1..cantidad).toList()
            val primera = paginarHistorial(fuente, 0)
            assertEquals(cantidad, primera.total)
            assertEquals(paginas, primera.paginas)
            assertFalse(primera.anterior)
            assertEquals(cantidad > 5, primera.siguiente)
            val ultima = paginarHistorial(fuente, Int.MAX_VALUE)
            assertFalse(ultima.siguiente)
            assertEquals(if (cantidad == 0) 0 else ((cantidad - 1) % 5) + 1, ultima.elementos.size)
            assertEquals(paginas > 1, ultima.anterior)
        }
    }
    @Test fun doceEventosSonCincoCincoYDosSinDuplicarNiReordenar() {
        val fuente = (12 downTo 1).toList()
        val paginas = (0..2).map { paginarHistorial(fuente, it) }
        assertEquals(listOf(5, 5, 2), paginas.map { it.elementos.size })
        assertEquals(fuente, paginas.flatMap { it.elementos })
        assertEquals(12, paginas.flatMap { it.elementos }.distinct().size)
        assertEquals((12 downTo 1).toList(), fuente)
    }
    @Test fun limitesNegativosYListasReducidasSonSeguros() {
        assertEquals(0, paginarHistorial((1..12).toList(), -1).indice)
        assertEquals(2, paginarHistorial((1..12).toList(), 200).indice)
        assertEquals(0, paginarHistorial(listOf(1), 2).indice)
        assertEquals(0, paginarHistorial(emptyList<Int>(), 2).indice)
        assertEquals(0, paginarHistorial(emptyList<Int>(), 2).paginas)
    }
    @Test fun nuevosEventosConservanPaginaValidaYReflejanLaFuente() {
        val fuente = (12 downTo 1).toList()
        val ampliada = listOf(14, 13) + fuente
        val pagina = paginarHistorial(ampliada, 1)
        assertEquals(1, pagina.indice)
        assertEquals(ampliada.subList(5, 10), pagina.elementos)
        assertEquals(14, pagina.total)
        assertEquals(ampliada, (0..2).flatMap { paginarHistorial(ampliada, it).elementos })
    }
    @Test fun empatesCronologicosConservanOrdenDeRoom() {
        val eventos = listOf("c", "b", "a").map { EventoSupervision(it, "PUERTA_ABIERTA", "", Instante(100), "") }
        assertEquals(listOf("c", "b", "a"), paginarHistorial(eventos, 0).elementos.map { it.id })
    }
}
