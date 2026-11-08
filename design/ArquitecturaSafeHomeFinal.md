# Arquitectura final SafeHome — evaluación

## Componentes y conexiones

```text
                          INTERNET
                             |
                       EMQX MQTT/TLS
                        /         \
                   WiFi             WiFi
                    /                 \
           ESP32 PORTÁTIL        ESP32 ESTACIÓN
                 |                |          |
              USB OTG         Bluetooth   puerta simulada
                 |                |       GPIO 27 + LED/buzzer
             APP MÓVIL        APP ESTACIÓN
                  \              /
                   \            /
                       Firebase
              Auth / Firestore / Realtime DB
```

Ambos ESP32 necesitan su conexión WiFi con acceso al broker. El flujo MQTT principal es **PORTÁTIL → BROKER → ESTACIÓN**, usando TLS 8883 y credenciales separadas según configuración informada. Android no implementa MQTT. Firebase no está en la ruta crítica de alarma.

### Ruta crítica física

ESP32 PORTÁTIL → MQTT/TLS → ESP32 ESTACIÓN → evaluación de puerta → WARNING/CRITICAL → buzzer/LED.

Con sistema ON e infraestructura Estación operativa: PORTABLE OUT + DOOR CLOSED → WARNING; OUT + DOOR OPEN → CRITICAL. Heartbeat PRESENTE cada 10 s y timeout 25 s, declarados para el firmware. La decisión y actuadores son responsabilidad del ESP32 Estación, no de Android/Firebase. La ruta sí depende de alimentación y red/broker para recibir evidencia de presencia; no confundir autonomía de Android con disponibilidad garantizada de MQTT.

### Ruta administrativa

APP ESTACIÓN → Bluetooth Classic/RFCOMM → ESP32 ESTACIÓN → SYSTEM ON/OFF o ALARM ACK. Cuenta Estación requerida. SH1 y CRC sin cambios. HELLO → DEVICE SH-E → ROLE STATION → STATUS ALL. ACK correlacionado confirma recepción; STATE esperado posterior confirma la operación. Android muestra hechos, no decide una alarma por falta de Internet.

### Diagnóstico Portátil

APP MÓVIL → USB OTG/Serial 115200 8N1 → ESP32 PORTÁTIL. Permiso USB y HELLO APP_MOBILE → DEVICE SH-M → ROLE PORTABLE. Comandos limitados a HELLO/PING/STATUS; WIFI/RSSI/MQTT/HEARTBEAT son diagnóstico, no control de actuadores. Quitar USB invalida diagnóstico Android; si también quita alimentación al ESP32, habrá consecuencias físicas reales sobre heartbeat.

### Ruta de historial y presencia digital

STATE válido de Estación verificada → `ProcesadorHardwareSafeHome` → transición significativa → `SincronizadorHardwareHogar` → `ColaEventosHardwareLocal` → commit SharedPreferences privadas en IO → `SincronizadorEventosHardware` → `DestinoFirestoreHistorialHardware` → `hogares/{hogarId}/historial/{idEvento}`.

El UUID permanece estable; transacción lee antes de crear y evita duplicados ante respuesta perdida. Solo se borra localmente tras éxito. La configuración actual se proyecta por separado; reproducir historial no reenvía órdenes ni restaura SYSTEM antiguo. Ocurrencia usa reloj Android; sincronización usa timestamp servidor. El historial antiguo mantiene compatibilidad.

`GestorHistorialHardware` coordina sesión, señal de red, eventos, foreground y reintento manual; Mutex impide vaciados simultáneos. Logout conserva pendientes y cancela contexto; otro hogar no los recibe. No hay envío en proceso muerto: se reanuda al iniciar una sesión válida. UI muestra contador/estado, sin fusionar aún pendientes con páginas remotas.

Firebase Authentication identifica usuarios; Firestore guarda datos/historial; Realtime Database soporta presencia digital Android. `ServicioPresenciaMovil` y `ServicioSupervisionEstacion` son independientes de PORTABLE físico. La caída de heartbeat Android no declara OUT del ESP32; heartbeat MQTT tampoco es presencia de usuario Android ni se guarda periódicamente en la cola.

## Contingencias: responsabilidad y evidencia

| Caso | Contrato esperado | Evidencia local / límite |
|---|---|---|
| Internet Android caído | Hardware sigue y eventos recibidos quedan locales | Cola/reducers JVM; autonomía física pendiente |
| Bluetooth caído | Invalidar UI; hardware continúa; nuevo handshake + STATUS al volver | Reducer y ciclo inspeccionados; radio/actuadores pendientes |
| Firebase caído | Retener historial, no bloquear alarma física | Fakes prueban retención/recuperación; servidor real pendiente |
| App cerrada | Hardware autónomo; conservar lo ya persistido | Recreación motor/almacén fake; proceso Android/commit físico pendientes |
| Portátil pierde WiFi | Cesa heartbeat y Estación evalúa timeout | Firmware no presente; criterio de prueba M05–M08 |
| Estación pierde WiFi/MQTT propio | UNKNOWN, no OUT/CRITICAL solo por infraestructura | Android acepta UNKNOWN sin evento OUT; emisión firmware pendiente C10/C11 |

UNKNOWN no borra automáticamente CRITICAL confirmada. STATUS reconstruye estado actual, no hechos ocurridos durante ausencia de Android/Bluetooth. La primera lectura no inventa transiciones históricas.

## Cobertura automatizada y alcance

Se conservan 200 tests anteriores. `EvaluacionContingenciaTest` añade 15 tests: invalidación, nuevo handshake, reconstrucción STATUS, UNKNOWN, diagnóstico de infraestructura, alarma previa, ciclo combinado de alarma/cola/recreación/aislamiento/recuperación, exclusión de heartbeat, comandos Móvil, roles incorrectos, reconexión USB y guardias estructurales de dependencias/roles/logs.

| Requisito de evaluación | Evidencia JVM o revisión |
|---|---|
| 1–3 desconexión y reconstrucción | EvaluacionContingenciaTest; solo representación Android, no lógica ESP32 ejecutada |
| 4–9 Firebase, respuesta perdida, logout, recreación y hogares | HistorialOfflineTest + ciclo combinado nuevo; almacén/destino inyectados |
| 10 concurrencia | HistorialOfflineTest.sincronizacionesConcurrentesNoDuplicanEnvio |
| 11–12 UNKNOWN e infraestructura | Nuevos tests de reducer; ausencia de firmware impide probar origen de UNKNOWN |
| 13 WARNING/CRITICAL y Firebase | Ciclo nuevo recibe STATE y persiste con destino caído; no verifica generación física |
| 14–16 separación heartbeats y USB | Contrato estructural nuevo de servicios/transportes y reducers, más suites previas; no instrumentación Android |
| 17–18 Móvil sin SYSTEM/ALARM ACK | Lista de comandos generados y guardias de ControlSesion revisadas/protegidas estructuralmente |
| 19–20 roles incorrectos | Handshakes de ambos reducers rechazados |

Las guardias de código fuente detectan cambios en dependencias/condiciones conocidas, no equivalen a pruebas de sesión real ni auditoría formal. Los fakes demuestran contratos del motor, no supervivencia real ante corte eléctrico, reglas cloud o entrega Bluetooth. Resultados finales y total se obtienen de XML Gradle, no de conteos manuales de anotaciones.

## Límites y documentos relacionados

No hay firmware local; autonomía, ACL/TLS y tiempos requieren pruebas del lunes. GPIO 27 simula reed; Portátil representa futura pulsera; no hay GPS ni certificación médica. Heartbeat perdido es evidencia ambigua (batería, red, avería, interferencia o alimentación). La seguridad de cadena DEVICE/ROLE y CRC no es criptográfica.

La protección histórica comienza en commit: canal Bluetooth volátil, disco fallido, límite de 1000, borrado de datos, reloj Android incorrecto y transacciones remotas tardías siguen siendo riesgos. Firebase remoto y backup de datos requieren revisión para producción.

- `RevisionSeguridadSafeHome.md`: amenazas, límites y logs.
- `ConsumoEnergeticoSafeHome.md`: cálculo heartbeat y análisis cualitativo, sin mediciones inventadas.
- `PlanPruebasFisicas.md`: 62 casos pendientes del lunes.
- `ContingenciaHistorialOffline.md`: persistencia, reintentos, timestamps y reglas pendientes.
- `IntegracionHardwareEstacion.md` / `IntegracionUsbPortatil.md`: contratos de transporte existentes.
