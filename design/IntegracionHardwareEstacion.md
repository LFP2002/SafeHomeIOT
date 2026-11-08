# Centro de mando de Estación — SH1

## Flujo implementado

- Bluetooth Classic cliente SPP, solo dispositivos emparejados. El ESP32 conserva el control autónomo de LED/buzzer.
- Cada nueva conexión descarta identidad, estados físicos y operaciones anteriores: HELLO → DEVICE/ROLE → STATUS ALL.
- Solo STATE de una Estación verificada modifica SYSTEM/DOOR/PORTABLE/ALARM. CRC y formato se validan antes del procesador.
- PING utiliza `CMD|PING|1`. Su respuesta esperada es `ACK|<secuencia enviada>|OK`.
- SYSTEM ON/OFF y ALARM ACK permiten una operación a la vez. ACK correlacionado confirma recepción; un STATE esperado **posterior al ACK** completa la operación. Un STATE anterior sigue siendo información física real, pero no completa la operación.
- ERR se asocia mediante DATO = secuencia del comando. No se muestra al usuario su texto arbitrario.
- Timeout de operación: 4 segundos desde que se prepara el envío. Se cancela la espera, se informa «Estación no confirmó la acción.» y se solicita STATUS ALL; no se corta Bluetooth ni se reenvía el comando.
- Una notificación EVENT solicita STATUS ALL; no modifica directamente estado ni historial.
- No hay timeout Android del portátil. PORTABLE es independiente de la presencia digital Android existente.

## Historial y sincronización

El único origen del historial físico son los cambios de STATE validados, procesados en orden antes de publicar el StateFlow. La primera lectura de cada propiedad tras conectar establece una base y no inventa una transición. EVENT y STATE repetidos no duplican registros.

- Ruta existente: `hogares/{hogarId}/historial/{eventoId}`.
- Tipos SYSTEM conservan los campos `{tipo, sistemaActivo, creadoEn}`; los eventos offline nuevos añaden `ocurridoEnMillis` y `sincronizadoEn` (ver `ContingenciaHistorialOffline.md`).
- Nuevos tipos: PUERTA_ABIERTA, PUERTA_CERRADA, PORTATIL_FUERA_RANGO, PORTATIL_RECONECTADO, ALARMA_ADVERTENCIA, ALARMA_CRITICA, ALARMA_RECONOCIDA; campos `{tipo, creadoEn}`.
- Para eventos nuevos, `creadoEn` conserva la ocurrencia Android y `sincronizadoEn` usa serverTimestamp. Los documentos antiguos mantienen su interpretación. No se registran latidos, PING, RSSI, STATUS ni snapshots repetidos.
- ALARMA_RECONOCIDA solo se atribuye al comando Android confirmado con ACK + STATE ALARM NONE. NONE sin ese contexto puede significar retorno del portátil o SYSTEM OFF: no se etiqueta falsamente como reconocimiento. Queda pendiente atribuir el reset físico GPIO 25 si se necesita distinguir su causa de esas otras transiciones; EVENT ACKNOWLEDGED solo provoca una consulta.
- `hogares/{hogarId}/configuracion/actual` refleja SYSTEM recibido, usando transacción, validación y campos existentes (`sistemaActivo`, `actualizadoEn`). También se sincroniza el primer SYSTEM tras conectar. SYSTEM repetido no escribe salvo recuperación tras un fallo de sincronización.
- Los eventos se persisten primero en la cola local y se envían con UUID estable. La configuración actual se proyecta separadamente; reintentar un evento histórico no reproduce una configuración antigua.
- La proyección es unidireccional: SH1 → Firestore. Los observadores Firestore nunca llaman al transporte. Con hardware verificado, `cambiarSistema` y `enviarReconocimientoAlerta` derivan al control IoT; no se vuelve a invocar el flujo legado para «sincronizar» una orden.
- Sin hardware verificado se conserva el flujo Firebase anterior, rotulado como configuración de compatibilidad, no como estado físico confirmado.

## Límites explícitos

- No se modifica `estado/actual`: sus booleanos no representan PORTABLE UNKNOWN ni la alarma reconocida independientemente de puerta/presencia. Esa sincronización física entre cuentas requiere acordar una evolución compatible en otra fase.
- No se escribe `comandos/actual` al reconocer una alarma IoT: emitir allí otra solicitud no representaría una confirmación y podría repetir una acción. Se registra únicamente el evento confirmado.
- No se añaden notificaciones físicas locales; WARNING/CRITICAL se reflejan inmediatamente en la UI. Las notificaciones y servicios digitales actuales se conservan.
- El canal Bluetooth existente de 128 entradas entrega transiciones a una cola persistente de hasta 1000 eventos. Su consumidor ya no espera a Firebase. Límites y recuperación se detallan en `ContingenciaHistorialOffline.md`.
- Los eventos ya persistidos sobreviven al cierre de sesión/conexión; los callbacks de transporte siguen sujetos a su generación. Una transacción ya enviada no puede deshacerse desde Android y nunca se reasigna a otra cuenta.

## Reglas Firebase: comprobación pendiente, sin cambios ni publicación

Este proyecto no contiene las reglas Firestore/Realtime Database desplegadas. No se consultaron datos ni reglas remotos, por lo que **no se afirma que estas nuevas escrituras estén autorizadas**.

Si aparece PERMISSION_DENIED al probar:

1. Revisar `get/create` en el match `hogares/{hogarId}/historial/{eventoId}` y la evolución de campos/timestamps descrita en `ContingenciaHistorialOffline.md`, conservando autorización por hogar y compatibilidad SIMULADOR.
2. Revisar `update` en `hogares/{hogarId}/configuracion/actual`: se conservan los campos existentes y se escriben solamente tras STATE real. Si las reglas exigen siempre un evento SYSTEM junto con cualquier cambio de configuración, la sincronización inicial (sin transición conocida) requeriría adaptar esa condición; no se fabrica un evento para sortearla.
3. La app muestra el fallo de sincronización sin revertir el estado físico ni repetir el comando. No se publican reglas automáticamente.

## Prueba física pendiente

Emparejar SafeHome-Estacion; verificar HELLO, DEVICE SH-E-001, ROLE STATION, STATUS ALL y PING. Pulsar GPIO 27 para comprobar OPEN/CLOSED. Enviar OFF/ON y observar ACK → STATE y actuadores. Provocar PORTABLE OUT (timeout propio del ESP32), WARNING y CRITICAL al abrir puerta. Reconocer y comprobar NONE y buzzer apagado. Cortar Bluetooth y comprobar autonomía local, estado desconocido en Android y restauración por HELLO/STATUS al reconectar. Probar también ERR, ACK ajeno, falta de ACK/STATE, cierre de sesión con comando pendiente y los permisos reales de historial/configuración.
