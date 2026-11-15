# Prototipo Android: MQTT directo y Room

Fuente de verdad: `CONTRATO_MQTT_SafeHome_Simplificado.md` en la raíz.
El backend permanece fuera del circuito. No se modificaron firmware, EMQX,
Cloudflare, documentos remotos ni reglas de Firestore.

## Configuración local necesaria

1. El administrador debe tener creado el usuario separado `safehome_android`, con
   SUBSCRIBE exclusivamente a los cuatro topics del contrato y PUBLISH exclusivamente
   a `safehome/9f21/estacion/comando`. No utilizar las cuentas de los ESP32.
2. Crear **en la raíz Android**, junto a `local.properties`, el archivo
   `.mqtt.local.properties`. Ya está excluido mediante `.gitignore`.
3. En un editor local introducir la propiedad **`mqtt.password`** y asignarle la
   contraseña real de ese usuario. No se proporciona ningún valor de ejemplo.
   Es un archivo Java Properties (UTF-8 no ASCII se puede representar con escapes
   Unicode); escapar barras invertidas según Properties. No usar comillas alrededor
   del valor, salvo que formen parte de la contraseña. No pegar la clave en terminal,
   capturas, documentación, chat, tests ni repositorio.
4. Sincronizar Gradle y compilar localmente cuando se autorice. BuildConfig se genera
   desde ese archivo; no hay contraseña por defecto. Si falta, la app indica que
   debe configurarse y no intenta conectar. Una modificación exige recompilar el APK.
5. Mantener la configuración Firebase local existente para acceso/perfil. Activar
   fecha/hora automáticas del teléfono: ventana ESP32 de 15 s atrás y 5 s adelante.

Broker fijo `g80de15c.ala.us-east-1.emqxsl.com:8883`, MQTT 3.1.1 y TLS con almacén de
confianza/verificación de hostname por defecto de HiveMQ; no hay trust-all ni sockets
propios. Client ID aleatorio persistido en preferencias privadas, sin mostrarse en UI.

## Implementación

- HiveMQ MQTT Client **1.4.2**; Room runtime/compiler **2.8.5**; KSP **2.3.14**;
  Lifecycle Process **2.6.1** (misma línea Lifecycle existente). KSP evita KAPT,
  incompatible con el Kotlin integrado de AGP 9. No se añade kotlin-android.
- `ControlSesion` conserva Auth/perfil y compone `SupervisionMqtt` + `HistorialLocal`.
  Las clases Firestore IoT antiguas quedan inactivas; no se instancian listeners IoT.
- ProcessLifecycleOwner habilita conexión en primer plano (incluye su breve debounce
  de background), únicamente tras perfil Estación válido confirmado por servidor.
  Logout, revocación/perfil inválido o background invalidan callbacks y cierran conexión.
  Un solo cliente, operaciones serializadas en Main y retry de red cada 3 s; sin servicio.
- El estado tiene límite de 4096 bytes y UTF-8 estricto; enums, tipos JSON, identificadores
  y fechas enteras UTC se validan antes de utilizarse. JSON inválido se ignora.
- Disponibilidad por LWT, estado retenido/último conocido y nueva publicación se muestran
  separados. Estado offline no se presenta como conexión activa; SIN_DATOS no es AUSENTE.
  Fecha publicada siempre visible; una publicación tampoco garantiza frescura indefinida.
- Publicación de comandos solo en topic permitido, QoS1/retain=false, UUID nuevo por
  pulsación, un comando pendiente. El ACK no confirma. Solo ID exacto + boolean esperado
  confirman; tras 10 s o pérdida de conexión el resultado es desconocido, sin retry
  automático de aplicación. MQTT QoS1 sí puede retransmitir el mismo paquete en vuelo.
- Solo eventos válidos NO retained entran en Room; nunca se sintetizan desde estado,
  heartbeat, disponibilidad, UI o envío de comandos. INSERT IGNORE, ID global como PK,
  UID/hogar y fecha de ocurrencia; Flow descendente, sin borrado al salir.
- La PK global implica que el mismo ID recibido bajo otra cuenta se ignora: no se
  copia la fila ni se revela la cuenta anterior. Es la semántica de una fila por ID
  exigida para este prototipo, no un historial multiusuario replicado.
- Al reabrir sin red, Firebase puede no validar el perfil. Una sesión Auth persistida
  puede abrir **solo su historial local**, usando el último hogar previamente validado;
  ese acceso no habilita MQTT. Tras logout, iniciar sesión otra vez requiere Firebase.
  Ninguna cuenta puede consultar el historial de otro UID desde esta UI.

## Limitaciones y revisión manual pendiente

- Topics **mono-hogar 9f21**, aunque el perfil Firebase tenga otro hogar lógico. No hay
  aislamiento MQTT por usuario Firebase: las cuentas autorizadas ven el mismo hardware.
  No usar este diseño como sistema multi-hogar de producción.
- La contraseña incluida en BuildConfig/APK puede extraerse; ignorar el archivo no
  protege el APK instalado. Producción requiere credenciales efímeras/individuales y
  ACL por hogar, rotación y asociación verificable; no se implementan aquí.
- Estado/eventos ESP32 QoS0: pedir QoS1 al suscribirse no mejora esa entrega. App cerrada,
  desconectada o proceso muerto puede perder eventos. No hay recuperación de backlog;
  Room solo conserva lo recibido. Ni siquiera un publish QoS0 exitoso garantiza entrega.
- Room permanece tras logout/reinicio, pero desinstalar/borrar datos lo elimina. Backup
  ya está deshabilitado. No se migran ni borran los registros Firestore anteriores.
- El historial no tiene paginación para esta evaluación; errores locales se muestran.
- **No se ejecutaron Gradle, builds, tests, CI ni hardware/red reales en esta tarea.**
  Por tanto, compilación/resolución de dependencias y aceptación física quedan por
  verificar, no se afirman como aprobadas. No se configuró ni suministró contraseña real.

Revisión manual al autorizar: login Estación; estado vacío; TLS y ambos LWT; GPIO27
con una fila por ID; heartbeat/alarma y SIN_DATOS; ACTIVAR/DESACTIVAR con eco exacto;
timeout y caída durante envío; background/logout/reconexión; retained sin evento;
reinicio offline e historial filtrado por cuenta; comprobar que no salen escrituras
IoT a Firestore ni credenciales a logs/capturas. Conservar backend deshabilitado.
