# Contrato de entrada y configuración EMQX

**Pendiente de prueba en el despliegue real.** No se conocen todavía la versión exacta
del clúster Serverless, los ClientIDs, usuarios ni topic/payload actuales de los ESP32.
Los placeholders siguientes NO son valores de producción ni identidades inventadas.

## Endpoint

`POST https://<worker-aprobado>.<subdominio>.workers.dev/emqx`

- TLS con validación del certificado habilitada.
- `Content-Type: application/json` (opcional `charset=utf-8`).
- `x-safehome-integration-secret: <secreto-de-Cloudflare-Workers>`.
- Un objeto JSON por solicitud, sin arrays ni compresión, máximo **8192 bytes**.
- Se rechazan campos extra. Ningún body puede contener `hogarId`, contraseñas,
  un destino Firestore o un tipo MQTT declarado por el ESP32.
- Identidades admitidas: ASCII `[A-Za-z0-9_.:@-]`, de 1 a 128 caracteres. Confirmar que
  las identidades físicas cumplen esa política antes del alta; no cambiarlas aquí.
- Todos los tiempos entrantes son enteros Unix **milisegundos** (no segundos ni strings).
  Ocurrencia: últimos siete días y como máximo 60 segundos de adelanto. Sesión MQTT:
  `connected_at` puede ser anterior a siete días si la conexión fue larga, pero no
  anterior al alta de la asociación. Las fechas se conservan como Timestamp Firestore.
- El secreto autoriza al conector a actuar como fuente broker. El backend no puede
  distinguir una regla alterada maliciosamente por un administrador con acceso a ese
  secreto. Proteger la consola EMQX y no incluir el secreto en mensajes MQTT.

### Conexión NATIVA

| Campo | Valor / origen |
| --- | --- |
| source | `broker.connected`, constante SQL |
| username / clientid | Metadatos del broker, no payload |
| connected_at | Campo nativo de EMQX |
| occurredAt | Exactamente `connected_at` |

### Desconexión NATIVA

Igual identidad; `source=broker.disconnected`, `connected_at` de la sesión terminada,
`disconnected_at` nativo y `occurredAt=disconnected_at`. Se exige
`disconnected_at >= connected_at`. No usar `now()` del webhook ni publicar «offline»
desde el ESP32. No utilizar un heartbeat ausente como prueba de desconexión.

### Puerta FUTURA (bloqueada hasta acordar firmware)

| Campo | Validación |
| --- | --- |
| source | `station.door`, constante SQL |
| username / clientid | Metadatos broker de la Estación registrada |
| topic | Topic exacto permitido en registro y ACL |
| type | `PUERTA_ABIERTA` o `PUERTA_CERRADA` |
| occurredAt | Fecha del hecho físico, reloj firmware sincronizado |
| receivedAt | `timestamp` de recepción del broker, no generado en cada reintento HTTP |
| retained | Debe ser `false`; no usar retained como transición |
| bootId | Identificador nuevo aleatorio por arranque, 8–64 caracteres `[A-Za-z0-9_-]` |
| sequence | Entero 0–4294967295, creciente por boot; no reciclar sin cambiar bootId |

`receivedAt` no puede anteceder la ocurrencia por más de 60 segundos ni ser futuro
fuera de esa tolerancia; la entrega debe caber en la ventana de siete días. Se permite
reenviar el mismo boot/secuencia con distinta hora de recepción broker, pero no con
otro tipo/hora de ocurrencia. `bootId` no es un reloj: el cambio de boot se ordena por
fecha y no crea historial inicial. NTP y la integridad de la cola futura son dependencias
del firmware. Sin bootId/secuencia, **422**; no existe fallback de identidad inventada.

## Reglas SQL: tres reglas y un conector

Fuentes oficiales consultadas:

- [Cloud: Data Sources and Fields](https://docs.emqx.com/en/cloud/latest/rule_engine/rule_engine_events.html)
  documenta `$events/client_connected` y `$events/client_disconnected`, con
  `connected_at` y `disconnected_at` en ms.
- [Cloud: HTTP Server](https://docs.emqx.com/en/cloud/latest/data_integration/http_server.html).
- [EMQX: Data Sources and Fields](https://docs.emqx.com/en/emqx/latest/data-integration/rule-sql-events-and-fields.html)
  explica nombres jerárquicos desde 5.10 y compatibilidad con los nombres anteriores.

**No se presupone la versión del despliegue.** Antes de activar reglas, anotar versión
y probar cada SQL en «Try It Out» con eventos realmente disponibles. Las consultas
siguientes usan los nombres con guion bajo documentados por EMQX Cloud. Si la versión
verificada requiere `$events/client/connected` / `$events/client/disconnected`, cambiar
solo el FROM tras comprobarlo. No activar ambas variantes: duplicaría acciones.

### Regla 1: conexiones de ambos ESP32

```sql
SELECT
  'broker.connected' AS source,
  clientid,
  username,
  connected_at,
  connected_at AS occurredAt
FROM "$events/client_connected"
```

### Regla 2: desconexiones de ambos ESP32

```sql
SELECT
  'broker.disconnected' AS source,
  clientid,
  username,
  connected_at,
  disconnected_at,
  disconnected_at AS occurredAt
FROM "$events/client_disconnected"
```

Opcionalmente restringir ambas con un WHERE a las parejas username/ClientID
**verificadas**. No es sustituto del registro backend; evita costes y 403 por otros
clientes del clúster. No filtrar por campos procedentes de payload.

### Regla 3: puerta (NO habilitar hasta validar contrato firmware)

```sql
SELECT
  'station.door' AS source,
  clientid,
  username,
  topic,
  payload.type AS type,
  payload.occurredAt AS occurredAt,
  payload.bootId AS bootId,
  payload.sequence AS sequence,
  timestamp AS receivedAt,
  flags.retain AS retained
FROM "<TOPIC_PUERTA_VERIFICADO>"
```

Usar un topic exacto autorizado; validar que `flags.retain` de ese despliegue produce
un booleano, y que la extracción de payload produce números, no strings. El SQL no
convierte un texto arbitrario a evento de conexión. La primera observación de puerta
es baseline, aunque el payload se denomine PUERTA_ABIERTA/CERRADA.

Se consumen **3 de las 4 reglas máximas y 1 de los 2 conectores máximos**. No crear
una regla/conector por ESP32 ni añadir un segundo backend de datos. La cuarta regla
queda libre; no se habilita ingestión de heartbeat ni inferencia de presencia.

## Configuración paso a paso (solo instrucciones, nada desplegado)

1. Confirmar Firebase **Spark sin billing**, Workers **Free**, región Firestore y
   versión/clúster EMQX. Mantener el límite de gasto de EMQX en cero; no contratar un plan.
   Revisar primero LIMITES.md: la activación Free está pendiente de viabilidad CPU.
   Registrar ClientID y username de cada ESP32 observados en conexiones reales, sin
   copiar passwords al repositorio. Confirmar si los usuarios están compartidos: de
   ser así, separar credenciales/ACL administrativamente antes de confiar en identidad.
2. Configurar autenticación MQTT/TLS y ACL por dispositivo: la Estación publica solo
   su topic permitido; Portátil no puede publicar puerta, eventos de broker ni topics
   ajenos. Fijar asociación username–ClientID donde el proveedor lo permita; el backend
   valida la pareja, pero no sustituye una política MQTT que impida suplantarla.
3. Completar el alta administrativa descrita en OPERACION.md. Mantener
   `puertaHabilitada=false` hasta comprobar el firmware futuro.
4. Solo tras aprobar CPU/cuotas y autorizar un despliegue futuro, configurar secretos
   Workers y el servicio descrito en OPERACION.md. Copiar su URL HTTPS `/emqx` a EMQX,
   sin query strings con secretos. No activar Functions, Blaze ni Google Secret Manager.
5. En Data Integration, crear **un** HTTP Server connector con esa URL, TLS verificado
   y cabecera secreta. Si la consola divide base URL y path, comprobar URL resultante
   para no duplicar el path. No habilitar OAuth2 simultáneamente ni enviar tokens Auth.
6. Crear reglas 1 y 2. Cada acción: POST, application/json, un objeto por solicitud,
   sin batching. Como body usar la serialización JSON de **todos los campos SELECT**
   (`${.}` en el editor de plantilla HTTP); comprobar en la vista previa que es un
   objeto y no un string escapado, array o texto interpolado inválido. Si el editor
   no admite ese placeholder, usar plantilla de campos SELECT con serialización JSON
   y comprobar el objeto exacto antes de activar. No concatenar payload arbitrario.
7. Ajustar timeout, buffer/TTL y retries disponibles en esa versión. El backend devuelve
   503 para indisponibilidad, con `Retry-After:10`; **verificar experimentalmente** qué
   reintenta EMQX, sus límites de buffer y comportamiento ante 4xx. Evitar bucles de
   reintento para errores permanentes. Vigilar métricas/alertas de acciones fallidas y
   SQL fallido, sin registrar cabeceras ni cuerpos con secretos. Un «Test connector»
   puede enviar GET/objeto vacío y recibir 405/422: eso no prueba una entrega válida.
8. Conectar un ESP32 real: primera observación sin historial. Desconectar esa sesión:
   un cambio confirmado crea historial. Reenviar exactamente el webhook: no duplica.
   Reconectar y entregar un disconnect viejo: no pisa el estado de la sesión nueva.
9. En una etapa posterior, validar reloj, bootId, secuencia y payload de la Estación;
   entonces habilitar regla 3 y `puertaHabilitada=true`. No modificar firmware aquí.
10. Completar la prueba real Firebase → Android con dos hogares y perfiles activos,
    inactivos y ajenos, manteniendo evidencia saneada. Hasta entonces la integración
    sigue **pendiente de aceptación física**.

## Respuestas y reintentos

| HTTP | Código | Interpretación |
| --- | --- | --- |
| 200 | `baseline`, `applied`, `unchanged` | Persistido, con/sin transición según evidencia |
| 200 | `duplicate`, `stale` | Ya recibido o atrasado; no repetir indefinidamente |
| 400 | `invalid_json` | JSON malformado |
| 401 | `unauthorized` | Secreto ausente/incorrecto |
| 403 | `unknown_identity`, `inactive_binding`, etc. | Alta, permisos o asociación inválidos |
| 405 | `method_not_allowed`, `Allow:POST` | No es POST |
| 409 | `event_identity_conflict` | Misma identidad de evento con contenido distinto; investigar |
| 413 | `payload_too_large` | Supera 8 KiB |
| 415 | `json_required`, `encoding_not_supported` | Formato o compresión no admitidos |
| 422 | `invalid_event`, fechas incoherentes/fuera de ventana | Contrato inválido; corregir fuente |
| 503 | `temporarily_unavailable` | Fallo transitorio/no configurado: reintentar con backoff |

El Worker autentica antes de consumir el body y limita su stream a 8 KiB aun sin
Content-Length. El edge puede producir sus propios errores de cuota (1027) o CPU
(1102), que no son un 200 de aceptación. Elegir modo **fail closed**, sin origen de
bypass, y comprobar cómo reintenta EMQX esos errores; no suponer entrega garantizada.
`INGESTION_ENABLED=false` devuelve 503 sin llamar a OAuth/Firestore. La verificación
real de Workers Free y de todo el circuito hasta Android sigue pendiente.
