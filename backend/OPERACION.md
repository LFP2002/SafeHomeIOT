# Operación, alta administrativa y aceptación pendiente

## Variables y secretos

| Nombre | Dónde | Uso |
| --- | --- | --- |
| `EMQX_WEBHOOK_SECRET` | Secreto Workers | Cabecera de integración, 32–512 caracteres, recomendado 32 bytes aleatorios en hex |
| `EMQX_BROKER_ID` | Variable Workers | ID administrativo del despliegue, `[A-Za-z0-9_-]{1,64}`; no procede del body |
| `GOOGLE_PROJECT_ID` | Variable Workers | Proyecto Firebase Spark existente; solo base `(default)` |
| `GOOGLE_SERVICE_ACCOUNT_EMAIL` | Secreto Workers | Cuenta de servicio dedicada, sin rol Editor/Owner |
| `GOOGLE_PRIVATE_KEY` | Secreto Workers | PEM PKCS#8 de esa cuenta, nunca en Git/Android; máximo 4900 caracteres |
| `INGESTION_ENABLED` | Variable Workers | `false` por defecto; no habilitar hasta aprobar viabilidad Free |

No hay secretos por defecto. Se leen del binding `env` durante fetch. Para pruebas
automáticas se generan claves solo en RAM y se intercepta Google. `.dev.vars` está
ignorado: no usar credenciales reales para desarrollo local. No publicar el JSON
de la cuenta de servicio, PEM, access_token o assertion JWT en comandos, URLs,
logs, capturas, bundles, fixtures o respuestas HTTP.

## Google OAuth, REST e IAM mínimo viable

La cuenta de servicio firma localmente un JWT **RS256** con Web Crypto nativo:
`iss=email`, `scope=https://www.googleapis.com/auth/datastore`,
`aud=https://oauth2.googleapis.com/token`, `iat` actual y `exp=iat+3600`.
Se intercambia por POST `application/x-www-form-urlencoded`, grant
`urn:ietf:params:oauth:grant-type:jwt-bearer`. No se utiliza una contraseña Firebase,
ID token Android, API key pública o token permanente de usuario.

El CryptoKey es no extraíble; el token se cachea por isolate con margen de 60 s y
renovación single-flight. Al cambiar credenciales se reemplaza la caché. Nunca se
guarda token en Firestore/KV/Git. 401 de Firestore invalida la caché y falla esa entrega;
un retry posterior vuelve a autenticarse. Los endpoints son fijos, HTTPS y sin seguir
redirects. No se envía `x-goog-user-project` para asociar facturación.

El adaptador utiliza `beginTransaction`, `batchGet` con ID de transacción y `commit`
con `currentDocument.exists=false` o `updateTime` leído. Todo el conjunto de escrituras
es atómico; no hay PATCH sueltos. `ABORTED` repite la transacción completa hasta cuatro
intentos con backoff. Un commit ambiguo falla 503: la repetición lee el recibo antes
de producir otra transición. Deadline de 20 s y presupuesto de 40 llamadas REST.

Preparación administrativa futura, no ejecutada:

1. Confirmar Spark y que no hay cuenta de facturación vinculada. La base gratuita
   existente y Firebase Authentication se conservan; no crear otra base ni migrar Auth.
2. Comprobar Firestore API existente e IAM/creación de cuentas de servicio del proyecto.
   Estas operaciones no requieren activar Functions/Run/Secret Manager. Si una consola
   exige billing o una política impide crear una clave, **detenerse**, no activar Blaze
   ni desactivar controles organizativos sin autorización.
3. Crear una cuenta de servicio dedicada y un rol personalizado mínimo:
   - `datastore.databases.get`: begin/rollback de transacciones.
   - `datastore.entities.get`: batchGet.
   - `datastore.entities.create`: documentos nuevos y precondición exists=false.
   - `datastore.entities.update`: actualizaciones con updateTime.
4. Asignar ese rol solo al proyecto/base prevista. No conceder Owner, Editor, gestión
   de índices, borrado de documentos, Auth Admin, Secret Manager ni permisos de billing.
   `roles/datastore.user` es más amplio y no es el mínimo (incluye borrados/listados).
   La firma con clave propia no necesita `roles/iam.serviceAccountTokenCreator` ni
   llamar a IAM signBlob/Cloud KMS. La persona que crea la clave requiere permisos
   administrativos separados; el Worker no puede crear claves ni cambiar IAM.
5. IAM de Firestore **no aísla por documento**. El código no escribe el registro,
   pero una clave robada con create/update podría modificar datos o asociaciones.
   Proteger la cuenta Cloudflare, acceso a secrets, rotación y estación administrativa.
   OAuth de servicio omite reglas cliente, igual que el antiguo Admin SDK; no abrir
   reglas Android para que funcione. Cambios IAM pueden tardar minutos en propagarse.

Referencia: [permisos Firestore por método](https://cloud.google.com/firestore/native/docs/security/iam#required_permissions_for_api_methods).
IAM real, OAuth real y acceso Spark del proyecto aún no se han probado.

## Alta administrativa (no automatizada ni ejecutada)

Verificar primero identidad **real** MQTT de cada dispositivo y propiedad del hogar.
El webhook no dispone de un endpoint de autoalta. Con una herramienta administrativa
autorizada/IAM, provisionar hogar mediante el flujo existente y luego crear:

`integracionesEmqx/{EMQX_BROKER_ID}/identidades/{identityId}`

```text
activo: true                                  boolean
username: <usuario MQTT comprobado>            string
clientid: <ClientID comprobado>                string
hogarId: <hogar existente y verificado>         string [A-Za-z0-9_-]{1,64}
dispositivo: estacion | portatil               string
revision: <versión nueva de asociación>         string [A-Za-z0-9_-]{1,64}
habilitadoDesde: <alta en epoch ms>             integer, no Timestamp
puertaHabilitada: false                        boolean inicialmente
topicPuerta: <topic exacto verificado>           opcional, solo necesario para puerta
```

El esquema es estricto (sin passwords, sin campos adicionales). `habilitadoDesde`
es la frontera de esa asociación: reconectar el dispositivo tras el alta para que
`connected_at` pertenezca a ella. No retrotraerla arbitrariamente para aceptar eventos
capturados de un hogar anterior. El hogar debe existir; el backend no lo fabrica.

Crear también el puntero inverso:

`integracionesEmqx/{EMQX_BROKER_ID}/destinos/{destinationId}`

```text
identidadId: <identityId de arriba>
revision: <misma revisión>
```

Algoritmos exactos (UTF-8, hex SHA-256):

```javascript
const { createHash } = require('node:crypto');
const hash = parts => createHash('sha256').update(JSON.stringify(parts)).digest('hex');
// Datos administrativos verificados, no valores enviados libremente por el ESP32:
const identityId = hash([brokerId, username, clientid]);
const destinationId = hash([hogarId, dispositivo]);
```

Crear/actualizar ambos documentos atómicamente con herramienta administrativa. Una
identidad activa por destino en ese broker; un único broker escritor por destino en
todo el sistema. No provisionar aliases paralelos. Ante cambio de hogar, credencial
o hardware: desactivar anterior, rotar revisión y frontera temporal, actualizar los
punteros, reconectar y revisar línea base. No borrar historial previo ni recibos para
«arreglar» orden. OAuth de servicio omite reglas cliente: controlar quién puede hacer estas
operaciones y mantener auditoría administrativa fuera del webhook.

## Procedimiento de despliegue futuro (NO ejecutar en esta etapa)

1. **Primero resolver la viabilidad de CPU Free de LIMITES.md.** Mantener
   `INGESTION_ENABLED=false`; no se ha autorizado ni realizado ningún deploy.
2. Confirmar Cloudflare Workers **Free**, Firebase **Spark**, sin billing/prepago ni
   autosubida a planes de pago. No son necesarios dominio comprado, Cloud Functions,
   Cloud Run, Artifact Registry, Google Secret Manager u otros servicios de pago.
3. Usar Node 22, `npm ci`, build/typecheck, tests Node/workerd/emulador y profiler.
   Conservar lockfile y evidencia saneada. Revisar la propuesta de reglas frente a las
   remotas por separado; no se han leído ni modificado reglas reales.
4. Preparar IAM y cuenta de servicio como se indica arriba. Tras autorización posterior,
   cargar los secretos de Workers de manera interactiva, nunca en argumentos:

   ```text
   npx wrangler secret put EMQX_WEBHOOK_SECRET
   npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_EMAIL
   npx wrangler secret put GOOGLE_PRIVATE_KEY
   ```

   Estos comandos modifican configuración remota: **solo instrucciones, no ejecutados**.
   Introducir PEM completo (saltos reales o `\n` escapados). RSA 2048 habitual cabe en
   5 KB; no subir JSON completo como variable. No introducir secrets en wrangler.jsonc.
5. Configurar `EMQX_BROKER_ID` y `GOOGLE_PROJECT_ID` en variables del Worker aprobado.
   Verificar la cuenta de Cloudflare elegida y workers.dev gratuito. La configuración
   versionada no incluye IDs ni credenciales reales, y sigue deshabilitada.
6. Solo si una autorización posterior permite validar en Free, publicar una versión
   controlada con `npx wrangler deploy`, inicialmente con ingesta deshabilitada. Verificar
   URL HTTPS `/emqx`, POST/401/503 sin secretos en logs. No ejecutar `firebase deploy`.
7. La habilitación para pruebas reales requiere otra aprobación consciente del riesgo
   CPU. Medir cold, renovación, contención, outcomes 1102/exceededCpu, cuota 1027,
   memoria y límites Spark. Ante exceso, detener y volver a deshabilitar; no activar Paid.
8. Provisionar registro administrativo y configurar conector/reglas EMQX.md únicamente
   cuando corresponda. TLS validado, ruta fail closed, sin origen alternativo de bypass.
   Probar duplicados y desconexiones antiguas antes de admitir tráfico normal.
9. Vigilar códigos, cuotas y acciones fallidas. Los logs de aplicación contienen solo
   `emqx_ingestion_failed`. Observabilidad está desactivada por defecto; al habilitar
   logs Free posteriormente, no capturar headers/body/JWT/token/PEM ni depuración EMQX.
10. Completar aceptación real hasta Android y documentar evidencia sin credenciales.
    Ninguna de estas instrucciones se ha ejecutado contra cuentas reales.

## Reversión

1. Pausar acciones/reglas del conector EMQX para evitar nuevas escrituras; conservar
   la configuración exportada y revisar buffer/TTL antes de descartarlo.
2. Deshabilitar ingesta en Workers, luego usar el rollback de una versión Workers
   **previamente aprobada** (`npx wrangler rollback <version-id>`, solo con autorización).
   Si es el primer despliegue, mantener pausado/deshabilitado: no hay versión anterior
   operativa inventada. El ZIP Cloud Functions es referencia, **no** rollback permitido
   a Blaze. No reactivar la plataforma antigua para sortear la restricción gratuita.
3. No revertir el schema Android ni recrear modelos antiguos. No borrar hogares,
   historial, cursores, recibos o asociaciones. Cualquier restauración de datos exige
   copia de seguridad y análisis separado para no duplicar hechos ya aceptados.
4. Para compromiso: pausar fuente/ingesta, rotar el secreto Workers y cabecera EMQX.
   Si es la clave Google, revocarla administrativamente, crear otra autorizada, cambiar
   el secreto y reiniciar la versión/isolate para vaciar caché. Tokens ya emitidos
   pueden seguir válidos hasta expirar; revisar revocación de IAM si corresponde.
   No hay doble secreto de gracia. Una reversión de código no restaura secretos
   revocados: revisar siempre bindings y estado de IAM antes de reanudar.
5. Si se necesita rollback de reglas cliente, usar únicamente el backup remoto
   previamente revisado y una autorización separada. No desplegar la propuesta
   automáticamente como compensación.

## Límites y riesgos pendientes

- No conocemos aún identidades, ACL, versión EMQX, topología ni formato firmware
  reales. No se ha creado secreto, función, conector, regla remota ni alta de dispositivo.
- El contrato de puerta exige bootId/secuencia/fecha; hasta incorporarlos y verificar
  reloj en otra etapa, la regla de puerta debe permanecer deshabilitada.
- No se publican alarmas nuevas ni diagnósticos de heartbeat; Android muestra ausencia
  de esos campos según su contrato. MQTT no representa presencia física de personas.
- Un ESP32 sin conectividad no puede publicar apertura/cierre. El broker puede
  detectar desconexión por keepalive más tarde; ese instante no es la hora de una
  salida humana. No existe garantía de recibir hechos físicos ocurridos offline.
- No se modifica firmware ni se añade cola offline. QoS, sesiones persistentes,
  almacenamiento firmware, MQTT expiry y buffer del conector condicionan la entrega.
  Una caída de backend más larga que el TTL, límites Serverless o eventos descartados
  por cuota pueden producir huecos irrecuperables. No prometer exactly-once de origen:
  solo persistencia idempotente de entregas aceptadas.
- La ventana HTTP de siete días y el rechazo de mensajes atrasados pueden descartar
  entregas extremadamente tardías. No se reconstruye historia retrospectiva; hay
  pérdida deliberada de detalle si falta un evento o llegan cambios fuera de orden.
- `connected_at` identifica sesión a resolución de milisegundos. Colisiones de una
  misma identidad con reconexiones indistinguibles en ese ms requieren evidencia y
  una extensión futura del origen; no se inventa un identificador aleatorio por retry.
- El registro debe impedir aliases entre despliegues. IAM Firestore no limita Admin
  por documento. Un conector o administrador comprometido con secreto puede falsificar
  evidencia de broker; limitar permisos y auditar acceso.
- El endpoint público consume cuota Free aun en 401. Body acotado y límites REST no
  son un WAF ni protegen de DoS o agotamiento de 100000 requests diarios. No configurar
  fail open. No se ha demostrado margen de CPU de 10 ms ni memoria total en la red.
- Recibos y boots crecen sin TTL. Evaluar coste y políticas de retención antes de
  producción; los datos privados de asociación incluyen identidades MQTT, no secretos.
- El proyecto real puede tener reglas/usuarios heredados no visibles en Git. Comparar
  reglas remotas y probar alta, perfil, revocación y aislamiento antes de desplegarlas.
- Revisar periódicamente `npm audit` y `npm audit --omit=dev`. El único paquete de
  aplicación es Zod; Firebase Admin/CLI son solo de tests y Wrangler/Miniflare son
  herramientas. Miniflare fija overrides de seguridad de sus transitivas `sharp`
  0.35.5 y `undici` 7.30.1, dentro de las mismas líneas compatibles. No aplicar
  migraciones incompatibles de `audit fix --force`. El SDK de tests usa un override
  explícito de `@grpc/grpc-js` a 1.14.7 para evitar avisos altos de su versión fijada;
  esa dependencia solo se usa en tests de reglas, no en el Worker.
  Los peers `@firebase/app`/`app-compat` se fijan a las versiones de Firebase 12.19
  compatibles con Node 22, evitando mezclar registros de componentes con Firebase 13
  (cuyo SDK cliente requiere Node 24). No se cambia el SDK Android.

## Criterios de aceptación real (pendientes)

- [ ] Versión EMQX anotada; SQL nativo validado con sus campos reales.
- [ ] ClientID/username y ACL de ambos ESP32 verificados, sin credenciales compartidas inseguras.
- [ ] Registro administrativo y destino canónico aprobados; hogar y perfil correctos.
- [ ] Viabilidad Workers Free aprobada con CPU real cold/caliente, OAuth/contención y cuotas.
- [ ] OAuth Google/IAM real funciona con Spark sin habilitar billing ni Blaze.
- [ ] Conectar/desconectar cada ESP32 actualiza Firestore con Timestamp y schemaVersion=1.
- [ ] Línea base sin falso historial; transición siguiente con un único documento histórico.
- [ ] Reintentos, desconexión atrasada y reconexiones probados desde el broker real.
- [ ] Fallo temporal HTTP/Firestore produce retry según configuración real EMQX, sin duplicados.
- [ ] Futuro firmware de puerta validado con bootId, secuencia, fechas y pruebas offline.
- [ ] Android observa los hechos reales, estados sin datos/caché y aislamiento entre hogares.
- [ ] Reglas remotas revisadas y autorizadas por separado; prueba real de alta/perfil/revocación.

**Ninguna prueba de mocks o emulador cierra por sí sola estos criterios.**

## Verificar Android sin modificar la aplicación

Solo después de autorizar y validar el servicio real dentro de los planes gratuitos:

1. Usar el APK actual y una cuenta Estación activa cuyo perfil apunte al hogar registrado.
   Confirmar conexión de datos para evitar confundir datos de caché con confirmación.
2. Conectar ambos ESP32 verificados. Revisar que los documentos `dispositivos/estacion`
   y `dispositivos/portatil` tienen `schemaVersion=1`, mqtt válido y Timestamp real.
   Android debe actualizar ambas tarjetas por sus listeners existentes.
3. Primera observación sin falso historial. Desconectar una sesión real: estado MQTT e
   historial coherentes; reenviar exactamente el webhook sin crear otro evento.
4. Reconectar y entregar el disconnect de la sesión antigua: no debe volver a offline.
   Una desconexión MQTT no prueba salida física de una persona.
5. Puerta solo cuando se acuerden bootId/secuencia/fechas en una fase de firmware
   separada. Verificar ausencia de alarma inventada y advertencias de datos obsoletos.
6. Probar otra cuenta/hogar, perfil inactivo y anónimo: no deben leer supervisión ajena
   ni publicar telemetría. No cambiar reglas para que una prueba pase; si las reglas
   desplegadas no protegen estas rutas, detener integración y solicitar revisión aparte.
7. Revisar cuota Spark compartida con listeners y registrar evidencia sin credenciales.
   Hasta realizar todo el circuito real no declarar EMQX → Firebase → Android terminado.
