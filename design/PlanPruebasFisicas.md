# Plan de aceptación — supervisión Firestore de cuenta Estación

**Todos los casos físicos/remotos están pendientes.** No se cambia infraestructura
para ejecutarlos sin revisión previa. Usar cuentas y hogares de prueba autorizados,
sin copiar credenciales/identificadores personales a evidencias públicas.

| Caso | Acción | Criterio esperado | Estado |
|---|---|---|---|
| A01 | Login Estación activa | Perfil confirmado servidor; solo su hogar | PENDIENTE |
| A02 | Login Móvil/Cuidador/Simulador antiguo | Rechazo controlado, sin mutación/borrado | PENDIENTE |
| A03 | Revocar perfil o cambiar hogar | Cancelar vista anterior y sus listeners | PENDIENTE |
| A04 | Registro explícito nuevo | Solo perfil/hogar, sin estado físico inicial | PENDIENTE |
| A05 | Denegar preparación del hogar | Informar, conservar Auth, permitir reintento propio | PENDIENTE |
| A06 | Logout mientras llegan respuestas | Sin callbacks visibles de sesión anterior | PENDIENTE |
| D01 | No hay documentos de telemetría | Sin datos, no MQTT conectado/alarma normal | PENDIENTE |
| D02 | Backend publica conexión real de cada ESP32 | Mostrar estado y fecha exactos | PENDIENTE |
| D03 | Desconectar Estación del broker | Backend publica desconexión; advertir puerta desactualizada | PENDIENTE |
| D04 | Portátil pierde MQTT/heartbeat | Mostrar evidencia publicada; no inferir salida de persona | PENDIENTE |
| D05 | Accionar puerta física | Cambio y fecha confirmados por backend | PENDIENTE |
| D06 | Firmware genera WARNING/CRITICAL/NORMAL | Alarma representada sin cálculo Android | PENDIENTE |
| D07 | Caída de Internet Android | Caché etiquetada, sin crear eventos ni controlar alarma | PENDIENTE |
| D08 | Recuperar conexión Android | Datos servidor sustituyen caché, listeners sin duplicación | PENDIENTE |
| H01 | Repetir heartbeats estables | No hay documentos históricos por repetición | PENDIENTE |
| H02 | Reintentar entrega del mismo evento backend | Un solo ID/documento histórico | PENDIENTE |
| H03 | Dos transiciones reales del mismo tipo | IDs distintos, ambas visibles | PENDIENTE |
| H04 | Mezclar historial nuevo/antiguo/inválido | Compatibles etiquetados y conteo de omitidos | PENDIENTE |
| S01 | Intentar lectura cruzada entre hogares | Reglas deniegan acceso | PENDIENTE |
| S02 | Intentar escritura Android de telemetría/historial | Reglas deniegan; solo backend autorizado escribe | PENDIENTE |
| S03 | Probar reglas nuevas de registro | No permiten sobrescribir usuarios/hogares existentes | PENDIENTE |
| S04 | Respaldo/transferencia de app | No exportar caché ni antigua cola sensible | PENDIENTE |

Precondición para D02–H03: backend autenticado EMQX → Firebase desplegado tras revisión,
contrato firmware confirmado, reglas revisadas y dispositivos correctamente asociados.
No se sustituyen estas pruebas por fixtures Android, Wokwi ni tests JVM. El firmware
Estación sin Bluetooth y el heartbeat MQTT del Portátil no se modifican en esta etapa.
