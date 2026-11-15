package com.example.safehome

import com.example.safehome.autenticacion.*
import com.example.safehome.iot.*
import org.junit.Assert.*
import org.junit.Test

class AutenticacionEstacionTest {
    private val perfil = mapOf("rol" to "estacion", "activo" to true, "hogarId" to "hogar_demo", "nombre" to "Usuario Demo")

    @Test fun admiteExclusivamenteEstacionActiva() {
        val valido = validarPerfilUsuario(perfil) as ResultadoPerfil.Valido
        assertEquals(RolUsuario.ESTACION, valido.perfil.rol)
        assertEquals("hogar_demo", valido.perfil.hogarId)
    }
    @Test fun rechazaTodosLosRolesAnterioresSinMutarDocumento() {
        listOf("movil", "cuidador", "simulador", "movil_pendiente", "movil_desvinculado", "movil_rechazado", "estacion_eliminada").forEach {
            val documento = (perfil + ("rol" to it)).toMutableMap()
            val original = documento.toMap()
            assertSame(ResultadoPerfil.RolNoAdmitido, validarPerfilUsuario(documento))
            assertEquals(original, documento)
        }
    }
    @Test fun rolEsExactoSinNormalizacionQueAmplieAcceso() {
        listOf("ESTACION", " estacion", "estacion ", "admin", "").forEach {
            assertSame(ResultadoPerfil.RolNoAdmitido, validarPerfilUsuario(perfil + ("rol" to it)))
        }
    }
    @Test fun rechazaPerfilesInactivosYMalFormados() {
        listOf(perfil - "rol", perfil - "nombre", perfil - "activo", perfil + ("activo" to false),
            perfil + ("activo" to "true"), perfil + ("hogarId" to "a/b"), perfil + ("hogarId" to ""),
            perfil + ("hogarId" to "a".repeat(65)), perfil + ("nombre" to " Demo "), perfil + ("nombre" to 3)).forEach {
            assertSame(ResultadoPerfil.Invalido, validarPerfilUsuario(it))
        }
    }
    @Test fun loginMantieneCompatibilidadConContrasenasAntiguas() {
        assertTrue(validarFormularioInicio("demo@example.com", "corta").esValido)
        assertFalse(validarFormularioInicio("demo@example.com", "").esValido)
    }
    @Test fun correoSeNormalizaSinAceptarControles() {
        assertEquals("demo@example.com", normalizarCorreo(" demo@example.com "))
        listOf("", "sin-arroba", "a b@example.com", "demo@example.com\n", "a\t@example.com").forEach {
            assertNotNull(validarCorreoRecuperacion(it))
        }
    }
    @Test fun registroExigeNombreCorreoClaveYConfirmacion() {
        assertTrue(validarRegistroEstacion("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234").esValido)
        assertFalse(validarRegistroEstacion("", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234").esValido)
        assertFalse(validarRegistroEstacion("Usuario Demo", "invalido", "ClaveDemo1234", "ClaveDemo1234").esValido)
        assertFalse(validarRegistroEstacion("Usuario Demo", "demo@example.com", "ClaveDemo1234", "otra").esValido)
    }
    @Test fun politicaDeContrasenaNuevaConservada() {
        listOf("corta", "a".repeat(12), "A".repeat(12), "Ab".repeat(6), "Aa1".repeat(11)).forEach {
            assertTrue(validarContrasenaNueva(it).isNotEmpty())
        }
        assertTrue(validarContrasenaNueva("Aa1234567890").isEmpty())
        assertEquals(32, limitarLongitudContrasena("x".repeat(40)).length)
    }
}

internal class CuentasFake : RepositorioCuentas {
    override var uidActual: String? = null
    var auth: ((String?) -> Unit)? = null
    val perfiles = mutableListOf<(Lectura<ResultadoPerfil>) -> Unit>()
    var perfilesCancelados = 0
    var authCancelados = 0
    var iniciarCallback: ((String?) -> Unit)? = null
    var crearCallback: ((String?, String?) -> Unit)? = null
    var prepararCallback: ((String?) -> Unit)? = null
    var recuperarCallback: ((String?) -> Unit)? = null
    var preparaciones = 0
    var inicios = 0
    var creaciones = 0
    override fun observarAuth(recibir: (String?) -> Unit): Suscripcion {
        auth = recibir
        recibir(uidActual)
        return Suscripcion { authCancelados++ }
    }
    override fun observarPerfil(uid: String, recibir: (Lectura<ResultadoPerfil>) -> Unit): Suscripcion {
        perfiles += recibir
        return Suscripcion { perfilesCancelados++ }
    }
    fun identificar(uid: String?) { uidActual = uid; auth?.invoke(uid) }
    override fun iniciar(correo: String, clave: String, terminar: (String?) -> Unit) { inicios++; iniciarCallback = terminar }
    override fun crear(correo: String, clave: String, terminar: (String?, String?) -> Unit) { creaciones++; crearCallback = terminar }
    override fun prepararRegistro(uid: String, nombre: String, terminar: (String?) -> Unit) { preparaciones++; prepararCallback = terminar }
    override fun recuperar(correo: String, terminar: (String?) -> Unit) { recuperarCallback = terminar }
    override fun cerrarSesion() = identificar(null)
}

class SesionEstacionTest {
    private val cuentas = CuentasFake()
    private val contextos = mutableListOf<SesionSupervision?>()
    private val sesion = SesionEstacion(cuentas) { contextos += it }
    private val valido = Lectura.Dato<ResultadoPerfil>(ResultadoPerfil.Valido(PerfilUsuario(RolUsuario.ESTACION, "hogar_demo", "Usuario Demo")))
    private fun autorizar() { cuentas.identificar("uid-demo"); cuentas.perfiles.last()(valido) }

    @Test fun authSinPerfilNoIniciaSupervision() {
        cuentas.identificar("uid-demo")
        assertTrue(sesion.estado.value.comprobando)
        assertTrue(contextos.isEmpty())
    }
    @Test fun perfilEstacionConfirmadoHabilitaSoloSuHogar() {
        autorizar()
        assertEquals(SesionSupervision("uid-demo", "hogar_demo"), contextos.last())
        assertNotNull(sesion.estado.value.perfil)
    }
    @Test fun perfilCacheNoAutorizaNiRetieneAccesoAnterior() {
        autorizar()
        cuentas.perfiles.last()(valido.copy(desdeCache = true))
        assertNull(sesion.estado.value.perfil)
        assertNull(contextos.last())
        assertTrue(sesion.estado.value.comprobando)
    }
    @Test fun otroRolSeRechazaSinInicializarNiEliminarDatos() {
        cuentas.identificar("uid-demo")
        cuentas.perfiles.last()(Lectura.Dato(ResultadoPerfil.RolNoAdmitido))
        assertNull(sesion.estado.value.perfil)
        assertTrue(sesion.estado.value.mensaje!!.contains("rechazado"))
        assertEquals("uid-demo", cuentas.uidActual)
        assertEquals(0, cuentas.preparaciones)
    }
    @Test fun revocacionDePerfilCierraSupervision() {
        autorizar()
        cuentas.perfiles.last()(Lectura.Dato(ResultadoPerfil.Invalido))
        assertNull(contextos.last())
        assertNull(sesion.estado.value.perfil)
    }
    @Test fun faltaDePerfilNoCreaDocumentosEnLogin() {
        cuentas.identificar("uid-demo")
        cuentas.perfiles.last()(Lectura.SinDatos)
        assertEquals(0, cuentas.preparaciones)
        assertNotNull(sesion.estado.value.mensaje)
    }
    @Test fun errorDePerfilCierraSupervisionYPermiteReintentar() {
        autorizar()
        val anterior = cuentas.perfiles.last()
        anterior(Lectura.Error("Sin autorización"))
        assertNull(contextos.last())
        sesion.reintentar()
        anterior(valido)
        assertNull(sesion.estado.value.perfil)
        cuentas.perfiles.last()(valido)
        assertNotNull(sesion.estado.value.perfil)
    }
    @Test fun logoutLimpiaPerfilYCancelaListener() {
        autorizar()
        val anterior = cuentas.perfiles.last()
        sesion.cerrarSesion()
        anterior(valido)
        assertNull(cuentas.uidActual)
        assertNull(sesion.estado.value.uid)
        assertNull(sesion.estado.value.perfil)
        assertEquals(1, cuentas.perfilesCancelados)
        assertNull(contextos.last())
    }
    @Test fun cambioDeUidDescartaCallbackDeSesionAnterior() {
        autorizar()
        val anterior = cuentas.perfiles.last()
        cuentas.identificar("uid-otro")
        anterior(valido)
        assertNull(sesion.estado.value.perfil)
        assertNull(contextos.last())
        cuentas.perfiles.last()(valido)
        assertEquals("uid-otro", contextos.last()!!.uid)
    }
    @Test fun cambioDeHogarCancelaContextoAnterior() {
        autorizar()
        cuentas.perfiles.last()(Lectura.Dato(ResultadoPerfil.Valido(PerfilUsuario(RolUsuario.ESTACION, "otro_hogar", "Usuario Demo"))))
        assertEquals("otro_hogar", contextos.last()!!.hogar)
    }
    @Test fun loginTardioTrasLogoutNoRestauraAcceso() {
        sesion.iniciar("demo@example.com", "clave")
        sesion.cerrarSesion()
        cuentas.identificar("uid-tardio")
        cuentas.iniciarCallback!!(null)
        assertNull(cuentas.uidActual)
        assertNull(sesion.estado.value.perfil)
        assertFalse(sesion.estado.value.ocupado)
    }
    @Test fun operacionesNoSeSolapanMientrasFirebaseResponde() {
        sesion.iniciar("demo@example.com", "clave")
        sesion.iniciar("otro@example.com", "clave")
        assertEquals(1, cuentas.inicios)
        sesion.cerrarSesion()
        sesion.iniciar("otro@example.com", "clave")
        assertEquals(1, cuentas.inicios)
    }
    @Test fun falloLoginLiberaOperacionSinAutorizar() {
        sesion.iniciar("demo@example.com", "clave")
        cuentas.iniciarCallback!!("Credenciales inválidas")
        assertFalse(sesion.estado.value.ocupado)
        assertNull(sesion.estado.value.perfil)
        assertEquals("Credenciales inválidas", sesion.estado.value.mensaje)
    }
    @Test fun formularioInvalidoNoInvocaAuth() {
        sesion.iniciar("invalido", "")
        assertEquals(0, cuentas.inicios)
    }
    @Test fun registroNoConsultaPerfilHastaPrepararHogar() {
        sesion.registrar("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234")
        cuentas.identificar("nuevo")
        assertTrue(cuentas.perfiles.isEmpty())
        cuentas.crearCallback!!("nuevo", null)
        assertEquals(1, cuentas.preparaciones)
        assertTrue(cuentas.perfiles.isEmpty())
        cuentas.prepararCallback!!(null)
        assertEquals(1, cuentas.perfiles.size)
        cuentas.perfiles.last()(valido)
        assertNotNull(sesion.estado.value.perfil)
    }
    @Test fun falloRegistroConservaAuthYReintentaSoloRegistroPropio() {
        sesion.registrar("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234")
        cuentas.identificar("nuevo")
        cuentas.crearCallback!!("nuevo", null)
        cuentas.prepararCallback!!("Permiso denegado")
        assertEquals("nuevo", cuentas.uidActual)
        assertTrue(sesion.estado.value.registroPendiente)
        assertNull(sesion.estado.value.perfil)
        sesion.reintentar()
        assertEquals(2, cuentas.preparaciones)
        cuentas.prepararCallback!!(null)
        assertFalse(sesion.estado.value.registroPendiente)
    }
    @Test fun crearCuentaTardiaTrasLogoutNoPreparaDocumentos() {
        sesion.registrar("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234")
        sesion.cerrarSesion()
        cuentas.identificar("nuevo")
        cuentas.crearCallback!!("nuevo", null)
        assertNull(cuentas.uidActual)
        assertEquals(0, cuentas.preparaciones)
        assertNull(sesion.estado.value.perfil)
    }
    @Test fun falloCreacionAuthNoPreparaHogar() {
        sesion.registrar("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234")
        cuentas.crearCallback!!(null, "No se pudo crear")
        assertFalse(sesion.estado.value.ocupado)
        assertEquals(0, cuentas.preparaciones)
    }
    @Test fun recuperacionEsGenericaYNoIniciaSesion() {
        sesion.recuperar("demo@example.com")
        cuentas.recuperarCallback!!(null)
        assertTrue(sesion.estado.value.mensaje!!.startsWith("Si existe"))
        assertNull(sesion.estado.value.perfil)
    }
    @Test fun recuperacionInvalidaNoConsultaFirebase() {
        assertNotNull(sesion.recuperar("invalido"))
        assertNull(cuentas.recuperarCallback)
    }
    @Test fun recuperacionFallidaEsVisible() {
        sesion.recuperar("demo@example.com")
        cuentas.recuperarCallback!!("Sin red")
        assertEquals("Sin red", sesion.estado.value.mensaje)
        assertFalse(sesion.estado.value.ocupado)
    }
    @Test fun destruirCancelaListenersYCallbacksPendientes() {
        autorizar()
        val previo = cuentas.perfiles.last()
        sesion.cerrar()
        previo(valido)
        assertEquals(1, cuentas.authCancelados)
        assertEquals(1, cuentas.perfilesCancelados)
        assertNull(contextos.last())
    }
    @Test fun callbackDeRegistroNoInicializaUnaCuentaDiferente() {
        sesion.registrar("Usuario Demo", "demo@example.com", "ClaveDemo1234", "ClaveDemo1234")
        cuentas.identificar("cuenta-distinta")
        cuentas.crearCallback!!("nuevo", null)
        assertEquals(0, cuentas.preparaciones)
        assertNull(sesion.estado.value.perfil)
        assertEquals("cuenta-distinta", cuentas.uidActual)
        assertTrue(sesion.estado.value.mensaje!!.contains("cambió"))
    }
    @Test fun loginNoCambiaUnaSesionFirebaseExistente() {
        autorizar()
        sesion.iniciar("otra@example.com", "clave")
        assertEquals(0, cuentas.inicios)
        assertEquals("uid-demo", sesion.estado.value.uid)
    }
}
