# Contingencia del historial físico

> **ARCHIVO HISTÓRICO — implementación retirada.** La app de cuenta única Estación
> no recibe SH1 ni escribe/reproduce esta cola. Sus preferencias existentes no se
> borran ni migran automáticamente. La solución vigente está en
> [ContratoFirestoreSupervision.md](ContratoFirestoreSupervision.md) y
> [ArquitecturaSafeHomeFinal.md](ArquitecturaSafeHomeFinal.md).

## La alarma sigue siendo local

El ESP32 Estación decide puerta/portátil/alarma y maneja LED y buzzer autónomamente. Esta cola solo protege HISTORIAL. No recibe comandos de actuadores ni condiciona alarmas a Internet, Android o Firebase. Los protocolos Bluetooth/USB, heartbeat Android y servicios de presencia no cambian.

## Guardar local primero

La ruta remota sigue siendo `hogares/{hogarId}/historial/{idEvento}`. `SincronizadorHardwareHogar` consume las transiciones deduplicadas del procesador, captura el reloj Android al recibirlas, crea un UUID una sola vez y persiste antes de solicitar envío.

`PreferenciasEventosHardware` usa SharedPreferences `historial_hardware_pendiente` en MODE_PRIVATE. Cada evento es una entrada independiente `evento.<UUID>`, con formato local versionado `1|idEvento|hogarId|tipo|ocurridoEnMillis|intentos|ultimoIntentoMillis`. Sus campos se validan y no admiten separadores; no es un mensaje SH1. Esta codificación pura evita incorporar JSON o dependencias nuevas. Datos inválidos, incluido texto JSON corrupto, se conservan sin enviar; otras entradas válidas siguen recuperándose.

Las escrituras utilizan `commit()` bajo Mutex en Dispatchers.IO, nunca apply ni Main. Una revisión aleatoria fuerza persistencia también cuando un commit anterior falló y la caché de SharedPreferences ya contiene el valor. No se envía un evento si no se confirmó la persistencia de su intento. Una eliminación fallida conserva/restaura la entrada para reintentar con el mismo ID.

El modelo contiene solo UUID, identificador del hogar, EventoFisico existente, ocurrencia Android, contador y fecha del último intento. No se añade UID de usuario, correo, tokens, contraseñas, credenciales ni mensajes crudos.

## Qué se guarda

SISTEMA_ACTIVADO/DESACTIVADO, PUERTA_ABIERTA/CERRADA, PORTATIL_FUERA_RANGO/RECONECTADO, ALARMA_ADVERTENCIA/CRITICA/RECONOCIDA. Se conserva la política existente: STATE es la única fuente de transiciones; la primera lectura y las repeticiones no inventan eventos. Reconocimiento continúa requiriendo la confirmación del flujo existente.

No se guardan heartbeat físico o Android, RSSI, PING, STATUS ALL, HELLO, ACK, SH1 crudo ni eventos USB. La actualización de configuración no se encola como un comando: se mantiene separada. Reproducir historial atrasado nunca cambia SYSTEM actual ni la configuración a un valor histórico.

## Caída de Internet y reintentos

El motor recorre un lote finito del hogar activo, de a un evento. Mutex impide dos vaciados simultáneos; nuevas entradas pueden persistirse mientras espera Firestore y generan otra señal. El contador muestra únicamente el hogar activo.

Disparadores: nuevo evento persistido, inicio de sesión/obtención de hogar Estación válido, red que vuelve a estar validada, regreso de la app a foreground y Reintentar manual. Cada señal tiene como máximo tres intentos (esperas de 30 y 60 segundos); cada escritura tiene espera de 20 segundos. No hay polling continuo ni dependencia de que ConnectivityManager asegure éxito. El callback de red se retira al destruir el gestor.

Fallos de red/timeout conservan eventos. PERMISSION_DENIED/UNAUTHENTICATED, datos incompatibles o precondiciones no válidas conservan la cola y bloquean reintentos automáticos de esa sesión. Se puede reintentar explícitamente o con una nueva sesión válida. No se borra por rechazo remoto.

La UI de Estación IoT muestra contador, sincronización y errores discretos. La combinación visual de pendientes con la paginación Firebase queda pendiente: se prioriza no duplicar elementos; el contador indica que los eventos están conservados localmente.

## Idempotencia y confirmación

La implementación real de DestinoHistorialHardware usa una transacción en el documento cuyo ID es el UUID persistido. Si no existe, hace set. Si ya existe y coincide con tipo/ocurrencia/datos inmutables, confirma sin reescribirlo. Si existe con contenido incompatible, no lo sobrescribe. Una respuesta perdida seguida de reintento produce un solo documento y conserva su primer timestamp de sincronización.

Solo después de éxito confirmado y contexto todavía vigente se elimina localmente. No se migran ni reescriben documentos históricos existentes. Las transacciones Firebase ya enviadas no pueden cancelarse en el servidor: ante logout o timeout podrían terminar después; el evento permanece local, el documento conserva su ruta original y el siguiente intento es idempotente.

## Tiempos retrocompatibles

- `ocurridoEnMillis`: reloj Android al consumir la transición, antes de persistir o esperar red; jamás reloj del ESP32.
- `creadoEn`: Timestamp de esa misma ocurrencia para conservar la consulta actual `orderBy("creadoEn", DESCENDING)` y su paginación.
- `sincronizadoEn`: serverTimestamp de la primera creación remota.

Los documentos nuevos añaden ocurridoEnMillis y sincronizadoEn a los campos existentes. HistorialHogar valida ambos y muestra ocurrencia. Los antiguos siguen usando su creadoEn original. El reloj Android puede estar desajustado; no se finge disponer de hora de servidor durante la desconexión.

## Sesión, hogar y límites

Logout cancela el vaciado y limpia solo su contexto visual; NO borra SharedPreferences. Hogar B nunca sube pendientes de A. Volver a una sesión Estación válida del hogar A permite recuperarlos, incluso tras recrear el proceso. La persistencia iniciada se completa con NonCancellable para no cortarla por cierre de sesión.

Límite total: 1000 entradas, incluyendo registros corruptos. No se expulsa ningún evento para hacer espacio. Al saturar se rechaza el nuevo guardado con aviso explícito y un log fijo sin identificadores. Fallos de almacenamiento/corrupción también se muestran; no se inventan datos. No hay recuperación automática de entradas inválidas.

Límites del prototipo: borrar datos/desinstalar elimina la cola; fallo físico de disco o saturación impiden garantizar nuevos guardados. La protección comienza al persistir: no puede recuperar eventos ocurridos mientras Bluetooth/Android estaban ausentes, ni una transición todavía en el canal volátil de transporte cuando muere el proceso. Ese canal existente de 128 elementos se conserva; ahora su consumidor solo espera IO local, nunca Firebase. Los estados/actuadores siguen independientes. No se incorporó WorkManager: con el proceso cerrado, el envío espera al siguiente inicio.

## Reglas remotas: comprobación pendiente

No hay reglas Firestore desplegadas en el proyecto y no se consultaron ni modificaron datos remotos. No se afirma que las escrituras nuevas estén autorizadas.

Si falla la ruta `hogares/{hogarId}/historial/{idEvento}` con PERMISSION_DENIED, revisar autorización de **get/create** para la Estación del hogar (roles ESTACION/SIMULADOR compatibles) y el conjunto de campos por tipo: `tipo`, `creadoEn`, `ocurridoEnMillis`, `sincronizadoEn`, más `sistemaActivo` para SYSTEM. Las reglas deben permitir ocurridoEnMillis/creadoEn pasados y exigir sincronizadoEn de servidor al crear. Si actualmente exigen creadoEn == request.time, esa condición debe evolucionar. Los reintentos de documentos existentes solo leen; no necesitan actualizar su timestamp. No se propone abrir acceso entre hogares.

La configuración existente `hogares/{hogarId}/configuracion/actual` se proyecta separadamente para que el historial offline no dependa de ella. Si sus reglas exigen un evento simultáneo en cada cambio, esa condición requerirá revisión; la app no fabrica eventos ni publica reglas para sortearla.

## Validación

JVM con fakes: orden local→remoto, persistencia/recreación, errores, aislamiento, cancelación, concurrencia, saturación, corrupción, timestamps y respuesta perdida/idempotencia. No requiere ESP32 ni contacto con Firebase.

Lunes: provocar transiciones con ESP32, quitar Internet manteniendo Bluetooth, comprobar contador y reinicio de app/logout, recuperar red y verificar un único documento por evento con ambas fechas. Probar cambio de hogar y permisos denegados. Comprobar que desconectar Android/Firebase no altera la alarma autónoma del ESP32. También verificar comportamiento real de SharedPreferences ante cierre de proceso y reglas Firestore, que los fakes no validan.
