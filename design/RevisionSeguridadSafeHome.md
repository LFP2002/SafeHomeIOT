# Revisión de seguridad SafeHome

## Alcance y evidencia

Revisión local del código Android, sus logs y tests JVM. No se dispone de firmware ESP32 en este repositorio. No se inspeccionó ni cambió EMQX ni reglas remotas Firebase. La configuración MQTT siguiente es la **informada para el prototipo**, pendiente de verificar físicamente/configuración. Los tests con fakes no prueban TLS, ACL, radio, persistencia Android real ni autonomía ESP32.

## MQTT

Broker privado EMQX Serverless, MQTT sobre TLS puerto **8883**, autenticación usuario/contraseña y credenciales distintas para Estación y Portátil. Política declarada: Portátil puede **Publish** únicamente en el topic de heartbeat correspondiente; Estación puede **Subscribe** a ese topic y no publicarlo. Portátil no debe suscribirse si su perfil es solo publicador. Regla general **DENY** para accesos no autorizados; evitar comodines amplios y cruces entre hogares. Verificar también rechazo anónimo, credenciales incorrectas y validación del certificado TLS en firmware.

Esto corrige el diseño previo basado en broker MQTT público al añadir privacidad del broker, autenticación, confidencialidad del transporte y autorización por usuario; su eficacia depende de la configuración real. No implica invulnerabilidad ni prueba de que las ACL estén desplegadas correctamente. No se reproducen hostname, topic sensible, passwords ni tokens.

Payload de heartbeat declarado: **PRESENTE**. No transporta nombres, correos, ubicación GPS, información médica ni contraseña Firebase. El topic y los tiempos aún pueden revelar metadatos operacionales. MQTT ocurre entre ESP32, no en Android.

## Bluetooth

Android selecciona dispositivos previamente emparejados; Android 12+ requiere `BLUETOOTH_CONNECT`. La conexión RFCOMM sola no verifica SafeHome: HELLO, DEVICE con formato `SH-E-…` y ROLE `STATION` deben concordar. `SH-E-001` es el identificador de demostración, no una clave secreta ni una identidad criptográfica. Cada reconexión invalida estado previo y exige identidad nueva antes de STATUS ALL.

Bluetooth Classic SPP **no se presenta como cifrado propio de SH1**. Emparejamiento y seguridad del enlace no convierten las cadenas DEVICE/ROLE en autenticación resistente a suplantación por un dispositivo malicioso emparejado.

## USB y SH1

USB requiere cable/conexión OTG y permiso Android. Se valida DEVICE `SH-M-…` (ejemplo `SH-M-001`) + ROLE `PORTABLE`. CP210x/CP2102 identifica el adaptador USB serie, **no autentica SafeHome**. La cuenta Móvil dispone solo de HELLO, PING y STATUS por USB; los actuadores Bluetooth están condicionados a sesión y rol Estación en ControlSesion.

SH1 tiene parser estricto y CRC-16/CCITT-FALSE para detectar corrupción accidental. **CRC no es cifrado ni autenticación criptográfica**; un adversario puede recalcularlo. No se incorporan firmas, claves ni protección antirreplay criptográfica en esta fase.

## Firebase y cola local

Authentication identifica usuarios; las reglas Firebase siguen siendo la autorización cloud. Las guardias Android no sustituyen esas reglas. No se comprobó autorización remota: revisar acceso por hogar y get/create del historial, como detalla `ContingenciaHistorialOffline.md`.

La cola utiliza SharedPreferences MODE_PRIVATE y commit en IO; contiene UUID, hogarId, tipo, ocurrencia e intentos, sin contraseñas, tokens, correos, UID personal ni códigos de vinculación. El hogarId y los eventos son metadatos operacionales, no información pública. MODE_PRIVATE no es cifrado adicional; acceso privilegiado y dispositivo comprometido quedan fuera de esa protección. El manifiesto desactiva backups (`allowBackup=false`). Las reglas para Android 8–11 y Android 12+ excluyen todos los dominios de datos privados, incluido `shared_prefs/historial_hardware_pendiente.xml`, tanto del respaldo cloud como de la transferencia entre dispositivos. La persistencia local y la sincronización offline se conservan; esto no sustituye una prueba de restauración en dispositivos físicos/OEM.

No se persiste heartbeat periódico ni RSSI periódico en historial/cola. La presencia digital Android sí utiliza su mecanismo independiente de Realtime Database; no confundirlo con los eventos físicos ni con el heartbeat MQTT. Historial físico: `hogares/{hogarId}/historial/{idEvento}`, ID estable y confirmación remota antes de borrar localmente.

## Modelo de amenazas

| Amenaza | Mitigación actual/declarada | Límite o evidencia pendiente |
|---|---|---|
| Broker MQTT público | Broker privado + autenticación + TLS + autorización por usuario | Verificar configuración real |
| Heartbeat falso | Credencial Portátil separada + Publish restringido | Robo de esa credencial permite suplantación |
| Credencial Estación simula Portátil | Estación sin permiso Publish al topic heartbeat | Probar DENY S01 |
| SH1 corrupto | CRC y parser estricto | No resiste falsificación deliberada |
| Dispositivo Bluetooth incorrecto | HELLO + DEVICE SH-E + ROLE STATION | Cadenas declaradas, no prueba criptográfica |
| Dispositivo USB incorrecto | HELLO + DEVICE SH-M + ROLE PORTABLE | CP210x no autentica |
| Firebase sin Internet | Hardware autónomo + cola offline | Autonomía informada; cola protege desde commit |
| Bluetooth desconectado | Alarma local autónoma + recuperación STATUS ALL | Estado actual recuperable, hechos perdidos no reconstruibles |
| WiFi/MQTT propio de Estación caído | PORTABLE UNKNOWN, no inferir salida | Android acepta UNKNOWN; firmware pendiente C10/C11 |
| Acceso entre hogares | Filtro local y reglas cloud por hogar | Fakes verifican aislamiento local; reglas no auditadas |

## Contingencias y límites reales

- Android sin Internet o Firebase no debe impedir alarma física. Para conservar MQTT, distinguir caída de Internet **del teléfono** de caída del router compartido.
- Bluetooth ausente o app cerrada no debe detener ESP32; la cola ya confirmada debe sobrevivir al proceso. Sin Bluetooth no se capturan nuevos hechos en Android.
- WiFi del Portátil perdido puede interrumpir heartbeat. Si la Estación pierde su propia infraestructura, debe emitir UNKNOWN y no producir CRITICAL solo por ese fallo. **No se puede certificar esta propiedad del firmware aquí**.
- Android no calcula salida por timeout MQTT. El reducer interpreta UNKNOWN como DESCONOCIDO, sin PORTATIL_FUERA_RANGO ni alarma nueva. Tampoco elimina una CRITICAL ya reportada: espera STATE ALARM del hardware.
- Pérdida de heartbeat no demuestra matemáticamente que alguien salió: batería, avería, interferencia, WiFi, MQTT o alimentación son explicaciones alternativas. Lógica física declarada, con sistema activo: OUT + DOOR CLOSED → WARNING; OUT + DOOR OPEN → CRITICAL. La puerta aumenta evidencia, no da certeza de localización.
- **GPIO 27 simula** sensor magnético/reed mediante botón; no hay evidencia de sensor electromagnético físico instalado.
- ESP32 Portátil es prototipo de futura pulsera/dispositivo portable. No hay GPS. SafeHome es académico, no dispositivo médico certificado.
- Saturación (1000), disco fallido, desinstalación/borrado y canal volátil anterior al commit limitan la conservación. Reloj Android puede desviarse. Una transacción enviada puede terminar después del logout; mantiene hogar e ID originales.

## Auditoría de logs

Se buscaron Log.d/i/w/e/v, println, printStackTrace y salidas estándar en fuentes. Se revisaron 12 sitios de logging y argumentos/callers. No se observó impresión directa de contraseñas MQTT, tokens Firebase, correo, UID, códigos, claves ni contenido de SharedPreferences.

Se detectaron **seis vías potenciales de divulgación** por mensaje o throwable completo: tres en ControlSesion, una en ServicioPresenciaMovil y dos en ServicioSupervisionEstacion. Un error SDK puede incorporar identificadores/rutas; no es evidencia de una filtración real de credenciales. La prueba `logsNoIncluyenExcepcionesCrudas` falló antes de corregirlas. Se eliminaron mensajes arbitrarios y stack traces, conservando etapa, resultado, tipo de excepción y/o código. Los demás logs útiles permanecen.

La prueba es una guardia de regresión para esos patrones conocidos, no un detector exhaustivo de secretos. No se revisó Logcat de dispositivos ni logs de firmware o broker.
