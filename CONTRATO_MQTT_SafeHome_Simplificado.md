# SafeHome — Contrato MQTT simplificado (15 de noviembre de 2026)

**Fuente de verdad:** los dos archivos `.ino` entregados con este documento. No se utiliza el backend EMQX → Cloudflare → Firestore para datos IoT. Mantener las reglas HTTP de EMQX en Disabled y Cloudflare con `INGESTION_ENABLED=false`. Firebase Authentication/Firestore se conservan solo para cuenta y perfil.

## Broker y seguridad de prototipo

- Broker: `g80de15c.ala.us-east-1.emqxsl.com`; puerto **8883**; MQTT **3.1.1 sobre TLS con certificado verificado**.
- ESP32 Estación: username `safehome_estacion`; contraseña privada en su sketch local.
- ESP32 Portátil: username `safehome_portatil`; contraseña privada en su sketch local.
- Android: crear usuario MQTT separado `safehome_android`; solo permiso de **SUBSCRIBE** sobre estado, evento y disponibilidad, y permiso de **PUBLISH** sobre el comando; no credenciales de ESP32. Las claves distribuidas dentro del APK pueden extraerse: documentarlo como limitación del prototipo.
- Prefijo de topics: `safehome/9f21/`. **No** habilitar Cloudflare ni reglas HTTP.

## Topics

| Topic | Emisor → receptor | Payload | Retained | QoS de publicación |
|---|---|---|---|---|
| `safehome/9f21/dispositivo/heartbeat` | Portátil → Estación | Texto literal `PRESENTE` (8 bytes) cada ~10 s | NO | 0 |
| `safehome/9f21/portatil/disponibilidad` | Portátil → Android | `ONLINE` / LWT `OFFLINE` | SÍ | 0 |
| `safehome/9f21/estacion/disponibilidad` | Estación → Android | `ONLINE` / LWT `OFFLINE` | SÍ | 0 |
| `safehome/9f21/estacion/estado` | Estación → Android | JSON del estado completo | SÍ | 0 |
| `safehome/9f21/estacion/evento` | Estación → Android | JSON de un evento | NO | 0 |
| `safehome/9f21/estacion/comando` | Android → Estación | Texto `ACCION|ENVIADO_EN|ID` | **NO** | 1 recomendado |

La Estación se suscribe al topic de comandos con QoS 1, al heartbeat con QoS 0. `PubSubClient` publica estados y eventos con QoS 0. LWT no prueba la presencia física de una persona.

## Estado actual (retained)

```json
{
  "schemaVersion": 1,
  "sistemaActivo": true,
  "puerta": "CERRADA",
  "portatil": "PRESENTE",
  "alarma": "NORMAL",
  "actualizadoEn": 1794765280961,
  "bootId": "A17C8AE917356A021822A8EE48B40451",
  "ultimoComandoId": ""
}
```

- `puerta`: `ABIERTA` / `CERRADA`.
- `portatil`: `PRESENTE` / `AUSENTE` / `SIN_DATOS`. `SIN_DATOS` no equivale a ausencia.
- `alarma`: `NORMAL` / `ADVERTENCIA` / `CRITICA`.
- `actualizadoEn`: Unix UTC **milisegundos**. Android debe distinguir valor retenido/desactualizado de conexión confirmada mediante topic `/disponibilidad`.
- `ultimoComandoId`: ID del último comando válido reconocido, **incluso si el estado ya coincidía**. Android debe esperar ese eco para informar aplicación confirmada; un `publish` exitoso **no** es confirmación del ESP32.

## Eventos locales para Room (no retained)

```json
{
  "id": "A17C8AE917356A021822A8EE48B40451-8",
  "tipo": "PUERTA_ABIERTA",
  "origen": "GPIO27",
  "ocurridoEn": 1794765280961
}
```

Tipos: `PUERTA_ABIERTA`, `PUERTA_CERRADA`, `PORTATIL_AUSENTE`, `PORTATIL_RECUPERADO`, `ALARMA_ADVERTENCIA`, `ALARMA_CRITICA`, `ALARMA_NORMAL`, `ALARMA_RECONOCIDA`, `SISTEMA_ACTIVADO`, `SISTEMA_DESACTIVADO`. Origen: `GPIO27`, `GPIO26`, `GPIO25`, `ANDROID`, `ESP32`.

Guardar en Room una fila por `id` (unique primary key; hacer `INSERT IGNORE` para duplicados) y asociar a la cuenta autenticada; nunca convertir un estado retenido en un evento histórico ni registrar un evento por cada heartbeat. Usar `ocurridoEn` para la fecha de ocurrencia; si Android no estaba conectado puede perder eventos. La cola de RAM de la Estación es limitada a 16 eventos; no persiste tras reinicio y publicar QoS0 **no** garantiza llegada al teléfono.

## Comandos desde Android

Formato **texto UTF-8**, sin JSON ni espacios, con tres partes separadas por `|`:

```text
DESACTIVAR|1794765280961|f61a4420188842b591f313afbe391277
ACTIVAR|1794765290000|e8871b3d27db4b87a794011d221e0a18
```

- `ENVIADO_EN`: fecha UTC en milisegundos, exactamente 13 dígitos. La Estación acepta como máximo 15 s de antigüedad y 5 s de adelanto. Android debe contar con hora correcta.
- `ID`: identificador hexadecimal o UUID con guiones (8–40 caracteres), distinto por operación; jamás reutilizar para una nueva orden.
- Publicar en `safehome/9f21/estacion/comando`, QoS 1, **retain=false**. No publicar el comando en Firestore.
- La Estación ejecuta GPIO26 **lógicamente**: desactivar la vigilancia y alarmas, sin apagar ESP32 ni WiFi. Los botones físicos siguen funcionando.
- Confirmar en Android cuando el estado recibido contenga `ultimoComandoId` igual al enviado y el `sistemaActivo` solicitado. Dar timeout/error si no llega; no fingir éxito por el ACK del broker.

## Comportamiento para demostración

1. Puerta GPIO27 alterna ABIERTA/CERRADA y dispara eventos + estados.
2. Portátil publica `PRESENTE` cada 10 s. A los 25 s sin heartbeat **mientras WiFi/MQTT de la Estación siguen conectados y antes hubo heartbeat**, la Estación pasa a AUSENTE, alarma ADVERTENCIA con puerta cerrada o CRITICA con puerta abierta.
3. GPIO25 reconoce la alarma. GPIO26 activa/detiene la vigilancia.
4. Android ve conexiones mediante LWT, estado completo retained y eventos no retained; Room conserva eventos recibidos.
5. Cuando Android está cerrado o sin datos, el historial puede quedar incompleto. Cuando la Estación pierde internet, la alarma local continúa independiente, pero no puede transmitir actualizaciones.

## Notas prácticas

- En Arduino IDE: board **ESP32 Dev Module**, Monitor Serie **115200**, WiFi real 2.4 GHz; completar claves privadas locales.
- Instalar **PubSubClient** de Nick O'Leary; core oficial ESP32.
- El sketch Portátil fue **reconstruido** a partir del contrato existente de heartbeat y del patrón de Client ID conocido, pues no se dispuso del `.ino` original completo; por ello debe compilarse y validarse físicamente.
- Los `.ino` no han sido compilados contra la toolchain real de ESP32 ni probados sobre el hardware en esta sesión. Sí superaron comprobación sintáctica C++ con stubs de las APIs.
- Firebase Authentication y Firestore mantienen las reglas actuales para perfiles. No borrar datos ni historial anterior por el cambio de arquitectura.
