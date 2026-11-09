# Contrato propuesto v1 — supervisión Estación

## Estado de esta etapa

Contrato documentado antes de implementar los lectores Android. No está desplegado
ni se afirma que el firmware o un backend lo publique actualmente. Android usa Firebase
Auth y Firestore reales, sin fixtures de producción, MQTT directo, Bluetooth o USB.
Un documento ausente se representa como **sin datos**, no conectado/normal por defecto.

## Identidad y permisos

`usuarios/{uid}` mantiene `{rol: "estacion", activo: true, hogarId: string, nombre: string}`.
Solo ese rol exacto habilita la sesión de supervisión. Roles antiguos se rechazan sin
borrar Auth ni documentos. No se migra `simulador` a `estacion` automáticamente.
El registro explícito de una cuenta nueva crea exclusivamente su perfil y
`hogares/{uid}` con `{nombre, creadoEn: serverTimestamp}`; no crea telemetría ni eventos.
Una transacción comprueba inexistencia para no sobrescribir documentos existentes.
Si falla el alta de documentos, la cuenta Auth permanece: se informa al usuario y se
permite reintentar la preparación, únicamente en ese registro recién iniciado. No se
borran usuarios como compensación. Si se pierde ese contexto al reiniciar, se requiere
revisión explícita; no se inicializa una cuenta preexistente desde el login.

Las reglas remotas deberán autorizar lectura exclusivamente de su hogar a una Estación
activa y denegar escrituras Android de telemetría/historial. Revisar autorización de la
transacción de registro antes de desplegar: las reglas anteriores podrían exigir otros
documentos. Las guardias cliente no sustituyen reglas. No se incluyen ni despliegan reglas.

## Documentos nuevos (todos los tiempos son Firestore Timestamp)

### `hogares/{hogarId}/dispositivos/estacion` y `.../portatil`

| Campo | Tipo | Semántica |
|---|---|---|
| schemaVersion | integer = 1 | Versión del contrato |
| mqtt | string | `CONECTADO`, `DESCONECTADO` o `DESCONOCIDO` |
| actualizadoEn | Timestamp | Última observación confirmada por backend |
| ultimoHeartbeatEn | Timestamp opcional | Último heartbeat recibido; nunca futuro respecto de actualizadoEn |

El ID del documento define el dispositivo, no una cuenta Móvil. Timestamp ausente,
estado desconocido fuera de la enumeración o tipo inválido hacen inválido el documento.
Un heartbeat antiguo **no** se transforma en desconexión mediante un timeout Android.
El backend define y publica el estado MQTT usando evidencia del broker; el heartbeat
del Portátil por sí solo no demuestra su conexión actual ni la presencia de una persona.

### `hogares/{hogarId}/estadoFisico/actual`

| Campo | Tipo | Semántica |
|---|---|---|
| schemaVersion | integer = 1 | Versión |
| actualizadoEn | Timestamp | Fecha de la observación |
| puerta | string opcional | `ABIERTA`, `CERRADA` o `SIN_DATOS` |
| puertaCambiadaEn | Timestamp opcional | Última transición confirmada, no posterior a actualizadoEn |
| alarma | string opcional | `NORMAL`, `ADVERTENCIA`, `CRITICA` o `SIN_DATOS` |

Campos opcionales ausentes se muestran sin datos. No se calcula alarma combinando
puerta y heartbeat. La desconexión/error/caché de Estación marca la puerta conocida como
potencialmente desactualizada; no borra un hecho previo ni lo presenta como actual.
`estado/actual` antiguo no se reutiliza como telemetría MQTT: `personaPresente` no tiene
esa semántica. Tampoco se reutilizan `presencia`, `comandos`, `configuracion` o `moviles`.

### `hogares/{hogarId}/historial/{eventoId}`

`{schemaVersion: 1, tipo: string, dispositivo: "estacion"|"portatil", creadoEn: Timestamp}`.
`creadoEn` es ocurrencia del hecho, conservada en reintentos. Dispositivo obligatorio:

| tipo | dispositivo |
|---|---|
| ESTACION_CONECTADA / ESTACION_DESCONECTADA | estacion |
| PORTATIL_CONECTADO / PORTATIL_DESCONECTADO | portatil |
| PUERTA_ABIERTA / PUERTA_CERRADA | estacion |

Solo el backend autenticado publica eventos de cambios confirmados, mediante ID estable
por evento de origen (por ejemplo dispositivo + bootId + secuencia), creación idempotente
y deduplicación transaccional. Heartbeats repetidos actualizan diagnóstico, no crean
historial. Snapshot inicial no demuestra una transición. Una transición repetida real
posterior sí debe tener otro ID: Android deduplica por ID, no por tipo/fecha ni contenido.
No hay escrituras de historial derivadas de observadores Android ni reproducción de la
antigua cola local. Los datos privados de esa cola permanecen en el dispositivo hasta una
migración revisada; no se borran ni se suben al nuevo contrato.

## Lectura histórica compatible

Registros sin schemaVersion: se leen solo tipos válidos conocidos con Timestamp creadoEn
y sus campos propios. Incluye puerta, alarmas físicas, sistema, reconocimiento,
ESTADO_ACTUALIZADO y MOVIL_CONECTADO/MOVIL_DESCONECTADO. Los de teléfono se etiquetan
**Teléfono (registro anterior)**; PORTATIL_FUERA_RANGO/RECONECTADO conservan su descripción
histórica sin traducirse a MQTT. Nunca actualizan el panel. La variante offline anterior
requiere ocurridoEnMillis integer válido, sincronizadoEn Timestamp y coincidencia con
creadoEn. No se convierten documentos inválidos, versiones desconocidas ni heartbeat en
eventos. Se muestra cuántos registros fueron omitidos por incompatibilidad.

Se observan los 100 registros más recientes ordenados por creadoEn, con desempate estable
por ID en Android. La UI advierte el límite; no afirma mostrar todo el historial. La
consulta no incluye documentos sin creadoEn: el backend debe cumplir ese campo. No se
crean índices remotos desde esta etapa.

## Errores, sesiones y caché

Cada recurso distingue carga, ausente, datos, inválido y error. Los datos de caché o con
escrituras locales aún pendientes tienen etiqueta visible y no son confirmación de
conexión MQTT. Tampoco habilitan un perfil de sesión. Listeners incluyen cambios de
metadatos; logout, revocación/cambio del perfil, cambio de hogar y destrucción cancelan
todas las suscripciones. Un contador de generación descarta callbacks de sesiones o
reintentos anteriores, incluso si llegan después de remove(). Se permite reintentar.
Un error no produce evento ni borra documentos; la UI no expone mensajes SDK arbitrarios.

## Pendientes físicos/backend

- Backend autenticado EMQX → Firebase: ingestión TLS/ACL, relación de dispositivo/hogar,
  Last Will/estado de conexión del broker, marcas de tiempo, validación, deduplicación,
  eventos reales e identidad/orden de mensajes ante reconexiones.
- Firmware Estación: acordar y verificar publicación de puerta, alarma, cambio de puerta,
  identidad, bootId/secuencia y disponibilidad, sin Bluetooth Classic. No se modifica aquí.
- Firmware Portátil: mantener heartbeat al broker y verificar contrato/identidad. No es
  una cuenta Android. Sin evidencia del broker no se inferirá conexión MQTT desde heartbeat.
- Revisar reglas y hacer pruebas de autorización entre hogares, caché, recuperación de
  conexión y correlación de hechos físicos. Nada de ello se declara validado con tests JVM.
