# Plan de pruebas físicas — lunes

Todas las pruebas están pendientes. No se ha probado hardware, firmware, broker ni reglas remotas en esta fase. Registrar versión de APK/firmware, equipos, alimentación, hora, condiciones de red y evidencia sin credenciales. Completar resultado real y estado solo después de ejecutar. Los resultados esperados expresan criterios de aceptación, no hechos comprobados.

Para contingencia, separar Internet del teléfono del WiFi/Internet compartido por los ESP32. Para tiempos, registrar último heartbeat recibido y momento de declaración, no solo momento de alejar a la persona. GPIO 27 es botón que simula reed. Utilizar identidades de demostración configuradas; DEVICE/ROLE no son autenticación criptográfica.

## Estación

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| E01 | Inicio ESP32 | Estación con firmware y alimentación | Encender | Arranque estable, estado inicial observable | PENDIENTE | NO EJECUTADA |
| E02 | GPIO 27 CLOSED → OPEN | Puerta simulada cerrada | Accionar botón a OPEN | DOOR OPEN coherente en hardware y STATUS | PENDIENTE | NO EJECUTADA |
| E03 | GPIO 27 OPEN → CLOSED | Puerta simulada abierta | Accionar botón a CLOSED | DOOR CLOSED coherente | PENDIENTE | NO EJECUTADA |
| E04 | GPIO 26 sistema OFF | Sistema ON | Accionar botón de sistema | SYSTEM OFF confirmado | PENDIENTE | NO EJECUTADA |
| E05 | GPIO 26 sistema ON | Sistema OFF | Accionar botón de sistema | SYSTEM ON confirmado | PENDIENTE | NO EJECUTADA |
| E06 | GPIO 25 reconoce alarma | Alarma activa | Accionar reconocimiento | Reconocimiento y estado de alarma coherentes con firmware | PENDIENTE | NO EJECUTADA |

## Bluetooth

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| B01 | Detectar SafeHome-Estacion | ESP32 encendido, Bluetooth teléfono activo | Buscar en ajustes Android | Nombre visible; app lista dispositivos ya emparejados | PENDIENTE | NO EJECUTADA |
| B02 | Emparejamiento | Dispositivo visible | Emparejar en Android | Emparejamiento finalizado | PENDIENTE | NO EJECUTADA |
| B03 | RFCOMM | Emparejado, cuenta Estación, permiso CONNECT si Android 12+ | Conectar desde app | Canal abierto, aún no válido sin identidad | PENDIENTE | NO EJECUTADA |
| B04 | HELLO | Canal abierto | Observar inicio de sesión SH1 | HELLO inicia intercambio de identidad | PENDIENTE | NO EJECUTADA |
| B05 | DEVICE SH-E-001 | Equipo demo configurado con ese ID | Recibir DEVICE | ID aceptado; DEVICE solo no completa verificación | PENDIENTE | NO EJECUTADA |
| B06 | ROLE STATION | DEVICE válido recibido | Recibir ROLE | Estación verificada y solicitud STATUS ALL | PENDIENTE | NO EJECUTADA |
| B07 | PING | Estación verificada | Probar comunicación | ACK OK correlacionado a secuencia enviada | PENDIENTE | NO EJECUTADA |
| B08 | STATUS ALL | Estación verificada | Actualizar estado | SYSTEM/DOOR/PORTABLE/ALARM reflejan hardware | PENDIENTE | NO EJECUTADA |
| B09 | SYSTEM OFF Android | ON y sin operación activa | Apagar desde app | ACK y STATE OFF confirman operación | PENDIENTE | NO EJECUTADA |
| B10 | SYSTEM ON Android | OFF y sin operación activa | Encender desde app | ACK y STATE ON confirman operación | PENDIENTE | NO EJECUTADA |
| B11 | ALARM ACK Android | Alarma activa, Estación verificada | Reconocer desde app | ACK y STATE esperado completan reconocimiento | PENDIENTE | NO EJECUTADA |
| B12 | Autonomía sin Bluetooth | ON, MQTT operativo | Cortar Bluetooth y provocar condición de alerta | ESP32 sigue evaluando puerta/heartbeat y LED/buzzer | PENDIENTE | NO EJECUTADA |
| B13 | Reconectar y recuperar | Bluetooth desconectado, estado físico cambiado | Reconectar | Nuevo HELLO/DEVICE/ROLE y STATUS ALL; sin reutilizar estado antiguo | PENDIENTE | NO EJECUTADA |

## Portátil USB

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| U01 | Alimentación OTG | Teléfono compatible y cable datos | Conectar ESP32 Portátil | Arranque alimentado por teléfono; registrar estabilidad | PENDIENTE | NO EJECUTADA |
| U02 | Detectar CP2102 | Adaptador CP2102 conectado | Listar dispositivos | Adaptador detectado sin considerarlo identidad SafeHome | PENDIENTE | NO EJECUTADA |
| U03 | Permiso USB | Cuenta Móvil, dispositivo detectado | Solicitar conexión y conceder permiso | Diálogo Android y apertura solo autorizada | PENDIENTE | NO EJECUTADA |
| U04 | 115200 8N1 | Puerto autorizado | Abrir sesión | Comunicación serie estable a 115200, 8 bits, sin paridad, 1 stop | PENDIENTE | NO EJECUTADA |
| U05 | HELLO APP_MOBILE | Puerto abierto | Observar handshake | Comando HELLO APP_MOBILE enviado | PENDIENTE | NO EJECUTADA |
| U06 | DEVICE SH-M-001 | Equipo demo con ese ID | Recibir identidad | DEVICE aceptado, esperando ROLE si falta | PENDIENTE | NO EJECUTADA |
| U07 | ROLE PORTABLE | DEVICE válido recibido | Recibir ROLE | Portátil verificado | PENDIENTE | NO EJECUTADA |
| U08 | STATUS ALL | Portátil verificado | Actualizar diagnóstico | WIFI/RSSI/MQTT/HEARTBEAT observables, sin eventos físicos de Estación | PENDIENTE | NO EJECUTADA |
| U09 | PING | USB verificado | Probar comunicación | Respuesta correlacionada válida | PENDIENTE | NO EJECUTADA |
| U10 | Desconexión física | USB conectado | Retirar cable | App invalida diagnóstico/identidad; no ordena cambios a Estación | PENDIENTE | NO EJECUTADA |
| U11 | Nuevo handshake | Cable retirado | Reconectar y autorizar si procede | Nuevo HELLO/DEVICE/ROLE antes de diagnóstico verificado | PENDIENTE | NO EJECUTADA |

U10 puede retirar también alimentación al Portátil: eso puede causar después pérdida MQTT real. Para aislar solo USB, usar alimentación independiente apropiada y segura; no confundir esa pérdida física con una orden Android.

## MQTT y alarma

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| M01 | Conectar ambos a EMQX | WiFi/Internet, credenciales separadas | Iniciar ambos ESP32 | Conexión privada autenticada TLS 8883 | PENDIENTE | NO EJECUTADA |
| M02 | Publicar heartbeat | Portátil conectado | Observar varios intervalos | PRESENTE aproximadamente cada 10 s | PENDIENTE | NO EJECUTADA |
| M03 | Recibir heartbeat | Estación suscrita y red sana | Publicar desde Portátil | Recepción en Estación sin Android | PENDIENTE | NO EJECUTADA |
| M04 | PORTABLE PRESENT | Heartbeat recibido | Consultar estado | PRESENT confirmado | PENDIENTE | NO EJECUTADA |
| M05 | Pérdida Portátil | Sistema ON, Estación con red sana | Alejar fuera de WiFi o desconectar Portátil | Cesa heartbeat; registrar causa y último recibido | PENDIENTE | NO EJECUTADA |
| M06 | Timeout 25 s | Heartbeat cesó, Estación con red sana | Medir desde último recibido | OUT alrededor de 25 s desde último heartbeat, con tolerancia documentada | PENDIENTE | NO EJECUTADA |
| M07 | Puerta cerrada WARNING | ON, OUT, infraestructura sana | Mantener DOOR CLOSED | WARNING según lógica física declarada | PENDIENTE | NO EJECUTADA |
| M08 | Puerta abierta CRITICAL | ON, OUT, infraestructura sana | Establecer DOOR OPEN | CRITICAL según lógica física declarada | PENDIENTE | NO EJECUTADA |
| M09 | Buzzer continuo | CRITICAL activo | Observar buzzer y LED | Buzzer continuo y señalización prevista sin Android | PENDIENTE | NO EJECUTADA |
| M10 | Retorno Portátil | Portátil desconectado | Recuperar alimentación/WiFi | Reconexión MQTT y heartbeat | PENDIENTE | NO EJECUTADA |
| M11 | Recuperar PRESENT | Heartbeat volvió | Consultar Estación | PORTABLE PRESENT coherente | PENDIENTE | NO EJECUTADA |
| M12 | Rearme | Retorno/reconocimiento completado | Provocar una nueva pérdida con sistema ON | Nueva condición detectada; alarma no queda bloqueada por ciclo anterior | PENDIENTE | NO EJECUTADA |

## Contingencia

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| C01 | Internet Android caído | Bluetooth activo; ESP32 con Internet propio operativo | Quitar Internet solo a Android y generar transición | Hardware continúa; Android recibe STATE | PENDIENTE | NO EJECUTADA |
| C02 | Pendiente local | C01, transición significativa posterior al estado inicial | Observar contador | Evento guardado localmente y pendiente | PENDIENTE | NO EJECUTADA |
| C03 | Reiniciar app | Pendiente ya confirmado localmente | Cerrar proceso y abrir sin borrar datos | App reinicia sin depender de Firebase para alarma ESP32 | PENDIENTE | NO EJECUTADA |
| C04 | Persistencia tras reinicio | C03, mismo hogar válido | Consultar contador sin Internet | Pendiente conservado | PENDIENTE | NO EJECUTADA |
| C05 | Recuperar Internet | Pendiente y sesión válida | Restaurar Internet Android | Intento de sincronización, sin asumir éxito por red disponible | PENDIENTE | NO EJECUTADA |
| C06 | Sincronización única | C05, reglas permiten get/create | Inspeccionar historial y reintentar | Un documento por ID, ocurrencia conservada, cola vacía tras éxito | PENDIENTE | NO EJECUTADA |
| C07 | Bluetooth cae con WARNING | WARNING activo | Cortar Bluetooth | Alarma sigue bajo control ESP32 | PENDIENTE | NO EJECUTADA |
| C08 | Buzzer continúa | C07 | Observar; después provocar CRITICAL si corresponde | Patrón WARNING continúa; CRITICAL continuo según M09 | PENDIENTE | NO EJECUTADA |
| C09 | Firebase no disponible | MQTT sano y sistema ON | Impedir acceso Firebase desde Android y provocar OUT/puerta | WARNING/CRITICAL/LED/buzzer independientes; historial pendiente si Bluetooth activo | PENDIENTE | NO EJECUTADA |
| C10 | WiFi propio de Estación caído | ON, PRESENT, sin alarma previa; Portátil operativo | Cortar solo WiFi de Estación y esperar más de 25 s | UNKNOWN, no OUT ni CRITICAL atribuible únicamente a esa caída | PENDIENTE | NO EJECUTADA |
| C11 | MQTT propio de Estación caído | Igual a C10, WiFi mantenido | Interrumpir conexión MQTT solo de Estación | UNKNOWN y diagnóstico de infraestructura; recuperar estado al volver | PENDIENTE | NO EJECUTADA |
| C12 | App cerrada y autonomía | ESP32 alimentados, MQTT sano | Cerrar ambas apps y provocar condición física | ESP32 conserva evaluación y alarma sin Android | PENDIENTE | NO EJECUTADA |
| C13 | Logout y cambio hogar | Pendientes A ya persistidos | Cerrar sesión, entrar B, luego volver A | B no recibe eventos A; A permite reintento sin perder cola | PENDIENTE | NO EJECUTADA |

C10/C11 son criterios especialmente pendientes: Android interpreta UNKNOWN, pero aquí no se dispone de firmware para comprobar que se emita. No exigir que UNKNOWN borre una CRITICAL preexistente sin una transición ALARM confirmada.

## Seguridad

| ID | Prueba | Precondición | Acción | Resultado esperado | Resultado real | Estado |
|---|---|---|---|---|---|---|
| S01 | Estación no publica heartbeat | Cliente autorizado de prueba, credencial Estación | Intentar Publish en topic heartbeat | Publicación denegada por ACL | PENDIENTE | NO EJECUTADA |
| S02 | Portátil publica heartbeat | Credencial Portátil, topic permitido | Publicar PRESENTE | Publicación permitida y recibida por Estación | PENDIENTE | NO EJECUTADA |
| S03 | Portátil sin Subscribe | ACL de Portátil configurada solo Publish | Intentar Subscribe | Suscripción denegada; documentar configuración real si difiere | PENDIENTE | NO EJECUTADA |
| S04 | CRC incorrecto | Canal de prueba SH1 abierto | Inyectar trama alterada sin recalcular CRC | Parser rechaza, sin cambio físico Android ni historial | PENDIENTE | NO EJECUTADA |
| S05 | ROLE incorrecto | Nueva sesión no verificada | Enviar PORTABLE por Bluetooth y STATION por USB | Ambos rechazados; no habilitar controles/diagnóstico verificado | PENDIENTE | NO EJECUTADA |
| S06 | DENY general | Cliente de pruebas y permisos limitados | Intentar topic ajeno y acceso anónimo | Acceso denegado, sin ampliar ACL | PENDIENTE | NO EJECUTADA |
| S07 | Certificado TLS | Entorno de pruebas controlado | Probar certificado no confiable/nombre incorrecto | Cliente rechaza conexión; no desactivar validación | PENDIENTE | NO EJECUTADA |

No modificar reglas o infraestructura de producción para ejecutar pruebas. Guardar evidencia redactada, sin passwords, tokens ni identificadores personales. Total: 62 casos, todos inicialmente PENDIENTE / NO EJECUTADA.
