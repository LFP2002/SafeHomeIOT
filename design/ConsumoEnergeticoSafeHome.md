# Consumo energético SafeHome

> **Actualización de arquitectura:** las secciones de USB OTG, Bluetooth, servicios
> de presencia Android y cola corresponden al prototipo anterior y ya no aplican
> a la APK actual. Los cálculos físicos siguientes siguen siendo estimaciones
> históricas, no mediciones ni parámetros verificados del firmware vigente.
> Android actual solo mantiene Auth y observadores Firestore mientras vive su sesión.

## Evidencia

**MEDIDO:** nada todavía; no hay mediciones físicas de corriente, energía o autonomía aportadas por el proyecto.

**ESTIMADO / CUALITATIVO:** análisis siguiente. No se asignan mAh, horas de batería ni porcentajes de ahorro sin medición. Los parámetros informados del firmware son heartbeat 10 s y timeout 25 s; no se modifican.

## ESP32 Portátil

Principales consumidores: WiFi (asociación, escucha, transmisiones y reconexiones), TLS (handshake y cifrado), CPU, regulador/LED de placa y conversor USB serie. Puede recibir alimentación desde el teléfono. Una sesión TLS persistente no realiza necesariamente un handshake por heartbeat; reconectar repetidamente puede costar más que publicar un payload pequeño.

El intervalo de 10 s, frente al intervalo anterior más corto informado, reduce publicaciones MQTT, tráfico y carga del broker. Se espera menos actividad de transmisión y menor consumo asociado, pero la radio puede seguir conectada/activa: menos mensajes **no equivale** a una reducción proporcional del consumo total. No se presupone deep sleep ni otra optimización no verificada en firmware.

### Cálculo para presentar

| Parámetro | Valor |
|---|---|
| Intervalo | 10 segundos |
| Frecuencia | 60 / 10 = 6 mensajes/minuto |
| Una hora | 360 mensajes |
| Un día continuo | 8640 mensajes |
| Timeout desde último heartbeat recibido | 25 segundos |

Son publicaciones de aplicación nominales, no todo el tráfico WiFi/TCP/TLS/MQTT: se excluyen keepalives, ACK, retransmisiones y reconexiones. Payload declarado: PRESENTE.

### Tiempo de detección

Heartbeat recibido en segundo 0, luego 10 y luego 20. Si desaparece inmediatamente después de 20, el timeout se detectaría alrededor de **45**. No es instantáneo.

Con intervalos regulares de 10 s y evaluación puntual, la pérdida en un punto cualquiera entre dos mensajes daría aproximadamente **15–25 s desde la pérdida** hasta vencer los 25 s desde el último recibido. No es una garantía: jitter, mensajes en tránsito, criterio `>` frente a `>=`, frecuencia de evaluación y reconexiones pueden modificarlo. Tampoco equivale a tiempo desde cruzar la puerta; el heartbeat no mide posición. No existe una cota física garantizada sin conocer y medir firmware/red.

10 s + 25 s permite tolerar pequeños retrasos y una pérdida aislada: si falta el de 10, el de 20 aún llega antes del vencimiento en 25. Pérdidas consecutivas pueden superar el margen. Se priorizó reducir falsas alarmas por retrasos, a costa de latencia. Una caída del enlace de la propia Estación debe diferenciarse mediante UNKNOWN, no atribuirse automáticamente al Portátil.

## ESP32 Estación

Dispositivo fijo alimentado permanentemente, conceptualmente similar a un router. Prioridades: estabilidad, disponibilidad y autonomía funcional respecto de Android/Firebase, **no autonomía de batería**. WiFi/TLS, CPU, LED y especialmente buzzer durante alarma contribuyen al consumo. También depende de alimentación y conectividad hacia el broker privado: autonomía respecto de Android no significa funcionamiento MQTT sin Internet.

## Android Móvil y Estación

Durante el prototipo, USB OTG puede alimentar ESP32 Portátil y conversor; aumenta descarga y calentamiento del teléfono, además del consumo de pantalla, servicios, USB y radio del propio Android. El heartbeat digital Android es otro mecanismo; no se calculó su energía con los 8640 mensajes del ESP32.

Android Estación consume por Bluetooth, pantalla y sincronización Firebase. La cola protege historial pero tiene IO y reintentos acotados; no es una optimización energética medida. En producto futuro, el portable necesitaría batería dedicada, circuito de carga/protección y diseño energético propio.

## Plan de medición pendiente

Medir tensión y corriente con instrumento adecuado en reposo, conexión WiFi/TLS, heartbeat estable, reconexión, diagnóstico USB y alarma LED/buzzer. Registrar placa, firmware, señal WiFi, alimentación, instrumento, duración y promedio/picos. Comparar teléfono con/sin carga OTG bajo condiciones equivalentes; considerar capacidad útil y pérdidas antes de estimar autonomía. No realizar cambios de firmware de ahorro en esta fase.
