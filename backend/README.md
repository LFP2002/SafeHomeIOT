# SafeHome: backend EMQX → Firestore (preparado, NO desplegado)

Migración del backend existente a **Cloudflare Workers**, TypeScript, OAuth 2.0 y
Firestore REST. Firebase Authentication y Firestore permanecen en **Spark**, sin Blaze,
facturación ni prepago. No modifica Android, firmware, CI ni el contrato público v1 de
[`design/ContratoFirestoreSupervision.md`](../design/ContratoFirestoreSupervision.md).
**No constituye todavía una integración real EMQX → Firebase → Android.**

## Arquitectura

```text
EMQX Cloud Serverless
  ├─ evento NATIVO connected ─── regla 1 ─┐
  ├─ evento NATIVO disconnected ─ regla 2 ├─ 1 conector HTTPS / POST
  └─ publicación futura de puerta ─ regla 3 ┘
               │ x-safehome-integration-secret
               ▼
Cloudflare Worker: fetch() en /emqx
  http → validación estricta → registro administrativo → transacción Firestore
                                                          ├─ estado actual v1
                                                          ├─ historial v1, si transición
                                                          └─ cursor/recibo privados
                         OAuth Google RS256 (Web Crypto) + IAM
               ▼
Firestore → lectores Android existentes (sin credenciales MQTT)
```

### Módulos

| Archivo | Responsabilidad |
| --- | --- |
| `src/index.ts` | Handler fetch, body acotado, secretos Workers, composición |
| `src/http.ts` | POST, secreto, límites, JSON, respuestas y logs saneados |
| `src/events.ts` | Contrato entrante, fechas, hashes e IDs deterministas |
| `src/registry.ts` | Autoridad identidad-dispositivo-hogar y revocación transaccional |
| `src/processor.ts` | Orden, sesiones, línea base y transición idempotente |
| `src/storage.ts` | Interfaces Storage/Transaction independientes de plataforma |
| `src/firestore-rest.ts` | beginTransaction, batchGet y commit con precondiciones |
| `src/firestore-codec.ts`, `src/timestamp.ts` | Tipos Firestore v1 e integer/timestampValue exactos |
| `src/oauth.ts` | JWT RS256 nativo, intercambio OAuth y caché efímera por isolate |
| `src/net.ts` | Transporte HTTP y lectura de streams con límites |
| `security/firestore.proposed.rules` | Propuesta cliente aislada para revisión/emulador |
| `test/*.test.ts` | Tests sin red ni credenciales con almacenamiento en memoria |
| `test/emulator/*.test.ts` | Autorización cliente y transacciones REST contra emulador |
| `test/workers/` | Bundle real en workerd, mocks salientes y profiler CDP local |

La clasificación individual de los **22 archivos originales** y la compatibilidad de
dependencias están en [MIGRACION.md](MIGRACION.md). El original completo está en un
[ZIP verificado](reference/README.md), no en un segundo backend activo. Los hashes,
IDs, schemas, autoridad administrativa y transiciones originales se conservan.

**Estado de viabilidad:** compilación y pruebas locales correctas no certifican los
10 ms de CPU de Workers Free. `wrangler.jsonc` deja `INGESTION_ENABLED=false`.
No se aprueba la activación mientras no exista evidencia suficiente; ver
[LIMITES.md](LIMITES.md), incluidas mediciones, riesgos y alternativa sin pagos.

## Contrato y compatibilidad Android

Se escriben exclusivamente estos documentos públicos:

| Ruta | Datos publicados |
| --- | --- |
| `hogares/{hogarId}/dispositivos/estacion` | `schemaVersion:1`, `mqtt`, `actualizadoEn:Timestamp`, heartbeat previo válido opcional |
| `hogares/{hogarId}/dispositivos/portatil` | Idéntico contrato |
| `hogares/{hogarId}/estadoFisico/actual` | `schemaVersion:1`, `puerta`, `actualizadoEn:Timestamp`, `puertaCambiadaEn:Timestamp` solo si conocido |
| `hogares/{hogarId}/historial/{sha256}` | `schemaVersion:1`, `tipo`, `dispositivo`, `creadoEn:Timestamp` de ocurrencia |

Los seis tipos son los admitidos por el parser Android:
`ESTACION_CONECTADA`, `ESTACION_DESCONECTADA`, `PORTATIL_CONECTADO`,
`PORTATIL_DESCONECTADO`, `PUERTA_ABIERTA`, `PUERTA_CERRADA`.
Los cuatro tipos MQTT se **derivan del evento nativo y del dispositivo del registro**;
no se confía en un tipo MQTT declarado por el firmware.

No se inventan valores de alarma. Se conserva únicamente una alarma existente válida;
sin ella, el campo queda ausente y Android muestra «sin datos». No se reutilizan
`estado/actual`, `personaPresente`, cuentas Móvil, comandos, Bluetooth o USB OTG.

El webhook **no ingiere heartbeats**: cualquier source/tipo de heartbeat retorna 422
y no genera ni estado MQTT ni historial. `ultimoHeartbeatEn` es opcional para Android;
solo se preserva un Timestamp previo compatible. Añadir diagnóstico de heartbeat
exige un contrato explícito posterior; no consumir una cuarta regla sin revisarlo.

### Persistencia privada, no segunda arquitectura pública

- `integracionesEmqx/{brokerId}/identidades/{identityId}`: alta administrativa.
- `integracionesEmqx/{brokerId}/destinos/{destinationId}`: asociación canónica única
  dentro de ese despliegue para cada hogar/dispositivo.
- `hogares/{hogarId}/_ingesta/{estacion|portatil}`: cursores MQTT/puerta y scope.
- `_emqxRecibos/{eventId}`: resultado/fingerprint de la entrega.
- `_emqxBoots/{bootKey}`: boots ya observados, para no resucitar uno retirado.

No son consultados por Android y las reglas cliente los deniegan. No se configura
TTL: eliminar recibos/boots o cursores altera las garantías y necesita una política
de retención/migración revisada. Provisionar un único broker escritor por destino;
no habilitar dos despliegues paralelos sobre el mismo hogar/dispositivo.

### Garantías y decisiones de orden

1. Registro, destino y hogar se leen dentro de cada transacción; sin caché de identidad.
2. beginTransaction y todas las lecturas batchGet comparten un transaction ID;
   commit confirma recibo, cursor, estado e historial atómicamente con `exists:false`
   o `updateTime` como precondición. Si Firestore
   falla no se devuelve éxito; 503 habilita un reintento del conector.
3. Identidad MQTT: `SHA256(JSON.stringify([brokerId, username, clientid]))`.
   Destino: `SHA256(JSON.stringify([hogarId, dispositivo]))`.
4. ID de evento: hash de broker, username, ClientID, revisión, source y
   `connected_at` para MQTT; o `bootId, sequence` para puerta. No incluye hora HTTP.
   Una identidad de evento reutilizada con otro contenido retorna 409.
5. Primera observación: establece línea base **sin historial**, incluso si el evento
   nativo se llama connected/disconnected. Sin cursor previo no se inventa transición.
6. MQTT se ordena primero por `connected_at`, después por ocurrencia dentro de esa
   sesión. Una desconexión de sesión antigua nunca pisa una sesión nueva, incluso
   si su `disconnected_at` es posterior (takeover). Una sesión cerrada no revive por
   un connect atrasado. Un disconnect de sesión nueva nunca observada establece
   otra línea base, sin inventar la conexión intermedia.
7. Una reconexión mientras el estado conocido sigue conectado cambia el cursor de
   sesión, no inventa una desconexión ni un par de eventos. Solo cambios observados
   de estado generan historial. `actualizadoEn` MQTT no retrocede; ante takeover
   conserva el máximo observado. `creadoEn` histórico conserva la ocurrencia real.
8. Puerta: exige fecha coherente, `bootId` y secuencia. Dentro del mismo boot deben
   avanzar secuencia y fecha (se permite fecha igual si la secuencia avanza). Un boot
   nuevo establece línea base sin transición ni fecha de cambio inventada. Un boot
   retirado no reaparece. La puerta nunca pisa un documento público con fecha mayor.
9. Eventos atrasados se reconocen con 200 `stale` y recibo, pero no generan historial
   retrospectivo: no conocemos necesariamente sus estados intermedios. Esto es un
   historial de **transiciones observadas en orden**, no un journal completo del broker.
10. `hogarId` no es campo entrante autorizado (422 si aparece). El destino se obtiene
    solo del registro, con puntero inverso y hogar existente. No se crea un hogar
    desde telemetría. La asociación/revocación puede invalidar reintentos pendientes.

## Instalación y pruebas locales (sin credenciales reales)

Requiere Node **22.x**, npm y Java 21+ para el emulador Firestore. El lockfile fija
dependencias. No se necesitan login Firebase, cuenta de servicio ni credenciales reales.

```powershell
cd backend
npm ci
npm run build
npm run typecheck
npm test
npm run test:workers
# Java debe estar en PATH; por ejemplo el JBR local de Android Studio:
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
npm run test:emulators
npm run profile:workers
```

El proyecto de pruebas es **`demo-safehome-backend`**, Firestore escucha únicamente
en `127.0.0.1:8085` y los tests abortan si ese host de emulador no está configurado.
Los datos se limpian exclusivamente en el emulador. No usar un ID de proyecto real.
Los tests normales no conectan a Firestore. Los tests de contención usan el nuevo
adaptador REST real en el emulador; Admin se usa solo como herramienta de seed/lectura
en tests Node para comprobar tipos. No se empaqueta Admin/gRPC/Functions en el Worker.

### Resultado local de esta implementación

Validado con workerd/Miniflare 4.20260730.0 y Firestore Emulator 1.22.0:

- Build TypeScript y typecheck de código/tests: correctos.
- 30 escenarios originales de dominio/HTTP conservados y aprobados.
- 12 pruebas nuevas OAuth/codec/transacciones REST aprobadas.
- 6 pruebas del bundle real en workerd aprobadas; no son solo ejecución Node.
- 12 pruebas con emulador (9 de autorización sin cambios y 3 REST/transacciones adaptadas): aprobadas,
  sin omitidas, incluyendo seis entregas concurrentes del mismo evento. Se añade una
  prueba workerd → REST → emulador → lectura SDK que verifica tipos y deduplicación
  (**13 pruebas en esta suite**).
- Perfil CDP del isolate local con frío/OAuth, caliente, duplicados, puerta y abortos.
  No demuestra cumplimiento de CPU/memoria Free en la red real; ver LIMITES.md.
- Los tests solo generan claves RSA efímeras en RAM y bloquean tráfico real a Google.
- Bundle: 783995 bytes, sin Firebase Admin/Functions/gRPC/tests; auditoría de producción
  sin vulnerabilidades reportadas. Herramientas: 13 avisos moderados, ninguno alto/crítico.

Esto no prueba las reglas remotas, TLS/ACL de un clúster EMQX real, el firmware
ni la recepción en Android desde hardware. Ver los criterios pendientes de OPERACION.md.

`npm run dev` arranca Wrangler local y, por defecto, devuelve 503 para ingesta válida
porque está deshabilitada. No poner credenciales reales en desarrollo ni cambiar el
destino a un proyecto real para «probar». El harness workerd intercepta Google con
servidores falsos; la suite del emulador usa exclusivamente `demo-safehome-backend`.
No habilitar `--remote` ni ejecutar ningún deploy en esta etapa.

## Seguridad Firestore propuesta

No se encontraron reglas desplegadas versionadas en el historial original; la propuesta
local del backend se conserva sin cambios.
**no se han leído las reglas remotas**. `security/firestore.proposed.rules` se usa solo
en el emulador. Antes de desplegar, exportar/comparar las reglas remotas mediante el
procedimiento administrativo autorizado, revisar clientes existentes y aprobar el diff.
No reemplazarlas ciegamente.

- Solo perfiles `rol=estacion`, `activo=true`, del hogar consultado leen supervisión
  e historial. Consulta Android `orderBy(creadoEn,desc).limit(100)` probada.
- No se permiten escrituras cliente de telemetría, eventos físicos o metadatos privados.
- Un usuario autenticado puede leer su perfil propio, incluso ausente/inactivo,
  para el registro y la guardia de sesión. No puede listar perfiles ni leer otros.
- Se admite el alta **atómica** de perfil y hogar con ID igual al UID, campos exactos,
  nombre compatible y `creadoEn=serverTimestamp()`. No permite autoasignarse a un hogar
  existente ni elevar privilegios. Perfil/hogar aislados se rechazan. El reintento
  exacto Android solo lee documentos existentes, sin sobrescribirlos.
- El get del documento raíz `hogares/{uid}` propio está permitido para comprobar
  inexistencia/reintento de registro; no concede acceso a subcolecciones físicas.
- Ningún cliente modifica/borrar rol, actividad, hogar o perfil. Revocaciones son de
  administración. Cualquier usuario Auth sin perfil/hogar puede solicitar el alta de
  su hogar UID: las reglas no conocen el estado efímero «registro recién iniciado» de
  Android. Esto no permite apropiarse de un hogar ajeno.
- Inicio de sesión y recuperación pertenecen a Firebase Auth, no a reglas Firestore.
- **OAuth de cuenta de servicio omite reglas cliente**: se autoriza mediante IAM. Restringir
  cuentas de servicio, quién administra el registro y quién lee el secreto. No conceder
  privilegios de propietario/editor a la cuenta de ejecución. Firestore IAM no equivale
  a aislamiento por documento; el registro es parte crítica de la frontera de confianza.

## Integración real y operación

Ver [EMQX.md](EMQX.md) para contrato, SQL y configuración del webhook.
Ver [OPERACION.md](OPERACION.md) para variables, secretos, onboarding, despliegue,
reversión, pruebas reales pendientes y límites de entrega.

CI backend, si se aprueba después: workflow separado con Node 22, Java 21+, `npm ci`,
build/typecheck/tests Node y workerd, emulador `demo-*`, sin secretos y sin deploy. **No se añade ni
se reemplaza el workflow Android existente.**
