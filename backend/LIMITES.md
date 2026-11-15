# Viabilidad Workers Free + Firebase Spark

Fuentes oficiales consultadas el 2026-11-09 (revisarlas antes de activar):

- [Workers Limits](https://developers.cloudflare.com/workers/platform/limits/)
  (página actualizada el 2026-10-29).
- [Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/).
- [Profiling CPU](https://developers.cloudflare.com/workers/observability/dev-tools/cpu-usage/).
- [Firestore cuotas](https://firebase.google.com/docs/firestore/quotas).
- [Firestore REST y OAuth/IAM](https://firebase.google.com/docs/firestore/use-rest-api).

## Cuotas vigentes documentadas

| Recurso | Free/Spark | Diseño |
| --- | --- | --- |
| Workers solicitudes | 100000/día, reinicio UTC | Incluye rechazos; proteger ruta y monitorizar. No auto-upgrade |
| Workers CPU | **10 ms por solicitud** | Riesgo principal, especialmente cold/JIT/importación RSA y contención |
| Workers subsolicitudes | 50 por invocación | Máximo 40 REST + una renovación OAuth dentro del deadline |
| Workers conexiones iniciales simultáneas | 6 | REST secuencial; token single-flight por isolate |
| Workers memoria | 128 MB por isolate | Body 8 KiB, respuesta OAuth 16 KiB, respuesta Firestore 64 KiB |
| Workers bundle | 64 MiB sin comprimir según documentación actual | Medido con build y metafile; no confundir con límites históricos de 3 MiB |
| Workers startup | 1 segundo | No generar claves ni llamar a Google en scope global |
| Workers variables/secrets | 64, 5 KB cada una | Clave PEM admitida hasta 4900 caracteres; RSA 2048 recomendada |
| Firestore documentos leídos | 50000/día | MQTT nuevo: 6; puerta: 7; duplicado: 4, más retries y Android |
| Firestore escrituras | 20000/día | MQTT baseline: 3; transición: 4; puerta nuevo boot: 4; duplicado: 0 |
| Firestore almacenamiento | 1 GiB | Historial/recibos/boots crecen; no TTL ni borrados automáticos |
| Firestore transferencia saliente | 10 GiB/mes | Incluye REST y clientes; vigilar uso compartido |

La cuota Free de Workers tiene **CPU**, no wall time. Esperar a Google no consume CPU,
pero parsear JSON, Zod, hashing, firma y serialización sí pueden consumirla. No utilizar
el tiempo total de un test Node, ni latencia HTTP, como prueba de cumplimiento de 10 ms.
La memoria de native crypto y buffers no queda íntegramente reflejada en heap JS.

No se usa Cloud Functions, Secret Manager Google, KV, Queues, D1, Durable Objects,
cron ni servicios de pago. No activar billing, Blaze o Workers Paid para resolver errores.
Una cuota excedida implica indisponibilidad/reintentos limitados, no permiso para pagar.

## Presupuesto transaccional

`beginTransaction` + 6/7 `batchGet` de un documento con el **mismo transaction ID** +
`commit` = 8/9 subsolicitudes. Duplicado: begin + 4 lecturas + rollback = 6.

Hasta cuatro intentos por `ABORTED`, cada uno con rollback: máximo 40 llamadas REST;
el token OAuth requiere como máximo una llamada nueva dentro del deadline de 20 s.
No se sigue ningún redirect (Workers exige `manual`; un 3xx se rechaza). No se hace
retry interno ante cuota/IAM/401/5xx o resultado ambiguo de commit. Se devuelve 503
y el siguiente webhook vuelve a leer el recibo. Agotar el deadline puede impedir el
rollback de limpieza; Firestore expira la transacción, sin confirmar escrituras locales.

Un evento aceptado puede consumir cuatro escrituras: **5000 cambios/día ya agotarían
las escrituras Spark**, sin contar registro, metadatos u otras apps. Los 100000 requests
de Workers NO equivalen a 100000 eventos/día disponibles en Firestore. Reintentos de
contención repiten lecturas; Android y sus listeners comparten la cuota de 50000.

En Spark no se habilitan TTL, PITR, backups/restore/clones administrados o más bases
de datos de pago. No borrar deduplicadores para ahorrar espacio sin política revisada:
alteraría garantías. Si se aproxima a 1 GiB, pausar ingesta y revisar retención/migración.

## Prueba local del runtime y medición

```text
npm run build
npm run typecheck
npm test
npm run test:workers
npm run test:emulators
npm run profile:workers
```

- `test:workers` ejecuta el bundle real en **workerd** (Miniflare), con todos los fetch
  salientes interceptados. Firma Web Crypto con RSA generada solo en RAM y verifica
  la firma fuera del isolate. No son únicamente tests Node del dominio.
- `profile:workers` conecta al **inspector del isolate workerd**, no al de Node; toma
  muestras CPU (100 µs) para cold OAuth, MQTT caliente, duplicados, puerta y cuatro
  abortos. Guarda solo métricas en `dist/profile-local.json`, nunca claves o tokens.
- El muestreo excluye `(idle)`, incluye GC/program y reporta wall time por separado.
  Los **spans entre muestras pueden incluir espera nativa/backoff**, especialmente
  si el runtime deja de muestrear al esperar. No son una medida aislada de CPU, ni se
  deben interpretar como CPU consumida los intervalos largos de contención. Se reportan
  también gaps y frames dominantes para que esa limitación no quede oculta.
  Puede incluir overhead del inspector y omitir trabajo nativo; el
  promedio de un lote NO demuestra un máximo por invocación. Heap JS no es memoria total.
- Miniflare/workerd local **no certifica ni reproduce la aplicación de cuotas Free**.
  Los servidores Google son falsos en esta prueba; Firestore real se aproxima con el
  emulador en otra suite, y OAuth real sigue pendiente.

### Resultado observado, no certificación de CPU

Ejecución local 2026-11-09, host Node 22.23.4, bundle **783995 bytes (765.6 KiB)**;
el metafile no incluye Firebase Admin, Functions, gRPC ni archivos de test.

| Caso | Spans no-idle por invocación, aproximados | Subrequests máximas observadas |
| --- | --- | --- |
| Frío MQTT, importación de clave y OAuth | 31.13 ms | 9 |
| MQTT caliente, lote de 20 | 7.57 ms de promedio | 8 |
| Duplicado, lote de 20 | 5.76 ms de promedio | 6 |
| Puerta, lote de 20 | 7.77 ms de promedio | 9 |
| Cuatro transacciones abortadas | 193.90 ms | 40 |

**Estos números no son CPU contabilizada por Cloudflare.** Por ejemplo, hubo un gap
de 93.19 ms entre muestras durante los abortos (incluye espera/backoff); atribuirlo
íntegramente a CPU sería incorrecto. Hubo también gaps de 17.29 ms en frío. Los
promedios calientes no acotan p95/p99 ni el máximo. Heap JS usado: aproximadamente
6.53 MiB, sin medir toda la memoria nativa. No se midió startup en el edge real.

Conclusión: la funcionalidad del runtime y el presupuesto de llamadas se han probado
localmente, pero **no existe evidencia suficiente para aprobar 10 ms por solicitud**.
Se detiene la activación Free. Se conserva el diseño íntegro y deshabilitado, no se
anuncian esos resultados como prueba de que Free funciona ni como demostración de
que necesariamente vaya a fallar en todos los despliegues.

## Puerta de activación y alternativa sin pagos

`INGESTION_ENABLED=false` en Wrangler es deliberado. Mantenerlo así mientras no se
haya aprobado la viabilidad. No aceptar eventos ni devolver éxito antes de commit.

Si las mediciones cold/renovación/contención muestran exceso o no hay margen seguro,
**detener la activación en Free**. No quitar firmas, comparación segura, autoridad,
deduplicación, lecturas transaccionales ni precondiciones para «hacerlo caber».

Alternativa a evaluar por separado, sin Blaze ni prepago: ejecutar el mismo dominio
y adaptador REST en un equipo propio ya disponible (Node 22) y publicar únicamente
el webhook HTTPS con un túnel gratuito autorizado, conservando secreto/OAuth/IAM y
transacciones. No se implementa un segundo backend aquí. Requiere disponibilidad,
actualizaciones, protección de claves y límites del túnel; no se promete hosting
gratuito 24/7 ni se compra hardware o un servicio. Si no existe un equipo adecuado,
no hay alternativa operativa confirmada dentro de estas restricciones.

Antes de cualquier futura aceptación Free deben comprobarse CPU real fría/caliente,
renovación de token, contención, memoria y outcomes `exceededCpu`/1102 y cuota 1027,
mediante observabilidad del propio Worker Free y solicitudes con datos saneados.
Esto requiere una autorización posterior de despliegue: **no se ha realizado aquí**.
