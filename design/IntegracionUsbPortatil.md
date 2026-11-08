# USB Serial del portátil — diagnóstico local

## Implementación

- Dependencia única: `com.github.mik3y:usb-serial-for-android:3.11.0`, declarada en el catálogo Gradle. JitPack está restringido al grupo `com.github.mik3y` en `dependencyResolutionManagement`.
- USB Host es opcional en el manifest. No se añaden permisos runtime genéricos ni filtros XML de autoapertura.
- `UsbSerialProber.getDefaultProber()` enumera drivers compatibles. CP2102/CP210x usa `Cp21xxSerialDriver`; su nombre amigable es «ESP32 / CP210x». Ni el nombre ni los identificadores USB verifican identidad SafeHome.
- La selección explícita consulta `UsbManager.hasPermission`. Si falta permiso, solicita autorización mediante `requestPermission` y un PendingIntent inmutable, de un solo uso, limitado al paquete y con acción única. El receptor no exportado vuelve a consultar el dispositivo conectado y el permiso real; no depende de extras modificables del broadcast.
- Apertura, parámetros, escritura y cierre ocurren en IO, serializados por mutex. Configuración: **115200, 8 bits, STOPBITS_1, PARITY_NONE, FlowControl.NONE**. No se fuerzan DTR/RTS.
- `SerialInputOutputManager` realiza lectura continua. El receptor JVM reconstruye líneas UTF-8 fragmentadas, acepta LF/CRLF y aplica el límite de `ProtocoloSafeHome`. Cada línea pasa por el parser SH1/CRC existente.
- Lista cerrada de comandos: HELLO APP_MOBILE, PING 1, STATUS ALL. El transporte no expone envío arbitrario ni comandos SYSTEM/ALARM.

## Verificación y solicitudes

1. Abrir el puerto invalida los datos anteriores y prepara/envía HELLO.
2. Solo DEVICE con formato `SH-M-<sufijo>` y ROLE PORTABLE permiten verificar. Una identidad incompatible cierra el enlace y muestra el mensaje de dispositivo incorrecto.
3. Tras verificar se envía STATUS ALL. WiFi, RSSI, MQTT y heartbeat permanecen desconocidos hasta sus respectivos STATE válidos.
4. PING se confirma exclusivamente con `ACK|<secuencia enviada>|OK`. Una escritura exitosa no implica respuesta.
5. STATUS concluye tras recibir los cuatro campos válidos; RSSI UNKNOWN es un resultado válido, mostrado como «No disponible». RSSI numérico admite -127…0 dBm.
6. Las solicitudes tienen espera de 5 segundos; timeout limpia la operación e informa falta de confirmación, sin éxito simulado ni reconexión automática. Un HELLO sin identidad mantiene el puerto no verificado; desconectar/conectar permite repetirlo.
7. ERR con secuencia o nombre del comando cancela la solicitud correspondiente. `ERR|PERMISSION|NOT_ALLOWED` carece de secuencia: se informa rechazo y se cancela la única solicitud pendiente, sin atribuirle éxito.
8. Los EVENT WiFi/MQTT solicitan STATUS cuando no existe otra solicitud pendiente. No se inventan estados a partir de eventos ni se registran en historial.

## Ciclo de conexión

Detach invalida la generación, identidad, diagnóstico, solicitudes y buffer; detiene el lector y cierra puerto/conexión. El receptor también detecta detach durante la autorización. Un error de lectura puede mostrarse brevemente antes del broadcast de detach; este establece DESCONECTADO.

Attach refresca la lista de la sesión Móvil activa, sin abrir puertos. Una nueva apertura espera los cierres anteriores. Cerrar sesión, cambiar de usuario/rol o destruir ControlSesion invalida callbacks y permisos pendientes. No hay reconexión infinita ni persistencia de permisos.

## Separación de responsabilidades

El diagnóstico no conoce Firebase, PresenciaMovil ni el historial. El heartbeat USB pertenece al ESP32 y no mide presencia digital de la app. El heartbeat Android y los servicios existentes permanecen intactos. MQTT/TLS sigue entre los ESP32 y EMQX, sin pasar por Android; desconectar USB puede quitar alimentación al portátil durante el prototipo OTG, pero Android no envía órdenes de apagado.

CRC detecta corrupción accidental; no autentica ni cifra. La etiqueta MQTT seguro representa el estado reportado por el firmware configurado con TLS; Android no inspecciona su sesión TLS ni sus credenciales.

## Pruebas físicas pendientes para el lunes

1. Conectar ESP32 Portátil mediante OTG y comprobar detección CP2102.
2. Autorizar acceso; comprobar también rechazo y desconexión durante el diálogo.
3. Verificar apertura 115200 8N1 y HELLO APP_MOBILE.
4. Recibir DEVICE SH-M-001 y ROLE PORTABLE; verificar que solo ambos habilitan diagnóstico.
5. Verificar STATUS ALL, WiFi CONNECTED, RSSI, MQTT CONNECTED y HEARTBEAT ACTIVE.
6. Probar PING y ACK correlacionado; ausencia de respuesta no debe mostrar éxito.
7. Desconectar cable durante lectura y confirmar DESCONECTADO con estados desconocidos.
8. Reconectar, repetir HELLO/STATUS y comprobar que no se reutilizan estados anteriores.
9. Cerrar sesión/cambiar a Estación con USB conectado; comprobar cierre y ausencia de callbacks anteriores.
10. Con EMQX activo, comprobar simultáneamente que el portátil sigue enviando heartbeat a la Estación.

Estas pruebas de USB Host/OTG, alimentación, firmware y MQTT simultáneo requieren teléfonos y ESP32 físicos; no se sustituyen por Wokwi ni por los tests JVM.
