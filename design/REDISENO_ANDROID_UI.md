# Rediseño de presentación SafeHome

## Alcance

Se conserva el dominio, las conexiones, permisos, callbacks y almacenamiento. Rama
encontrada al iniciar: `main`, árbol limpio; no se cambió de rama ni se realizaron
operaciones Git de escritura. No se agregan dependencias ni se cambia Gradle.

### Distribución

- Cabecera azul/morada compacta, nombre real y tres pestañas originales.
- Panel: resumen semántico, cuadrícula de conexión/Estación/Portátil/vigilancia,
  puerta y alarma, señal del Portátil reportada por Estación y tres controles.
- Historial: títulos legibles, iconos, fechas locales y filas compactas. Sin códigos
  internos ni origen GPIO en pantalla. Vacío y error son estados diferentes.
- Cuenta: nombre, rol y hogar del perfil, cierre de sesión original.
- Espaciado compartido: secciones 24 dp, tarjetas 12 dp, interior 16 dp, radio 18 dp.
  Controles de al menos 48 dp; texto adaptable sin elipsis. Verde/ámbar/rojo/gris y
  morado siempre acompañados de texto e icono. Azul de cabecera y ámbar oscurecidos
  para mejorar contraste en modo claro, preservando el gradiente original.

## Veracidad y presentación

`EstadosPresentacion.kt` transforma el modelo existente, sin persistir estados ni
modificar `permiteControl` o `permiteReconocer`. La prioridad del resumen es conexión,
disponibilidad de Estación, vigencia, alarma y vigilancia. No se infiere presencia
física de disponibilidad. La señal reportada por Estación tiene una etiqueta aparte.

Se adopta una regla **solo visual y conservadora**: estado de una publicación no
retenida, con Android conectado, Estación ONLINE y fecha no futura de hasta 60 s.
Pasado ese intervalo se muestra «Último estado conocido». Un reloj Compose refresca
esta presentación cada 5 s. Esto no es un timeout de red/alarma, no procesa eventos
y no habilita ni deshabilita acciones. Fechas incoherentes se muestran como anteriores,
no como confirmación de normalidad. El dominio no tiene timestamp de recepción local;
por tanto, la frescura depende de la fecha publicada y de la hora del teléfono.

La traducción de resultados usa únicamente `esperando` y el mensaje de confirmación
ya emitido por el coordinador. No confirma por clic ni por ACK, ni reenvía comandos.
RECONOCER se presenta como «Silenciar alarma» sin crear un estado SILENCIADA.

## Paginación local

`paginarHistorial` devuelve ventanas de exactamente cinco elementos como máximo,
sin mutar, eliminar, duplicar o deduplicar la lista. Cero eventos implica cero páginas
y estado vacío. La UI conserva el orden descendente de Room, incluido el desempate;
si recibe una fuente desordenada, la ordena establemente por fecha solo para mostrarla.

El índice empieza en cero y se limita al rango válido antes de extraer la página.
Al llegar actividad nueva, se conserva el índice y se refleja la nueva lista; los
elementos pueden desplazarse dentro de esa página. Al disminuir el total se ajusta
el índice a la última página válida. Anterior/Siguiente se deshabilitan en los límites;
no hay paginador con cinco o menos eventos. El cambio de página desplaza al inicio.

`rememberSaveable(contexto)` y `SaveableStateHolder` en navegación preservan la página
al cambiar de pestaña y la separan por UID/hogar. La ruta offline usa su propio
contexto de cuenta y el mismo paginador. No se cambia su autorización ni se consulta
otro hogar. Los elementos de LazyColumn usan ID estable. No se utiliza Paging 3.

## Archivos

| Archivo | Responsabilidad |
| --- | --- |
| `app/src/main/java/com/example/safehome/SafeHomeApp.kt` | Cabecera, textos offline y estado guardado de pantallas por cuenta/hogar |
| `presentacion/PantallaSupervision.kt` | Panel reorganizado, resumen, sensores y callbacks originales |
| `presentacion/PantallaHistorial.kt` | Filas, estados vacíos/error y paginador Compose |
| `presentacion/PantallaCuenta.kt` | Perfil real y cierre de sesión |
| `presentacion/ComponentesSafeHome.kt` | Espaciado, tarjetas, chips, avisos, acciones, fechas y distribución adaptable |
| `presentacion/EstadosPresentacion.kt` | Funciones puras de veracidad, títulos y ventanas de cinco eventos |
| `presentacion/IconosSafeHome.kt` | Paleta semántica existente, textos e icono de silencio |
| `presentacion/PreviewsSafeHome.kt` | Previews aislados de Panel, alerta, Historial y Cuenta |
| `ui/theme/Color.kt` | Azul y ámbar accesibles en modo claro |
| `ui/theme/Theme.kt` | Morado de identidad como color primario de controles |
| `app/src/main/res/drawable/bi_bell_slash.xml` | Path oficial Bootstrap Icons 1.11.3, licencia MIT |
| `app/src/test/java/com/example/safehome/HistorialPaginadoTest.kt` | Conteos, límites, orden, nuevas filas y reducción |
| `app/src/test/java/com/example/safehome/PanelVisualTest.kt` | Estados, prioridad, vigencia, confirmaciones y permisos originales |
| `app/src/androidTest/java/com/example/safehome/PresentacionSafeHomeTest.kt` | Regresión Compose, paginación, callbacks, layouts y capturas con fixtures |

Las rutas `presentacion/` y `ui/theme/` de la tabla son relativas a
`app/src/main/java/com/example/safehome/`. Este documento registra las decisiones.

## Comprobación

Comandos de compilación y pruebas:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
```

Las pruebas Compose usan `createComposeRule` sin construir `ControlSesion`, Firebase,
Room ni MQTT. Los callbacks solo incrementan contadores; los fixtures no se escriben
en la base real. Las capturas de fixtures se guardan como artefactos locales fuera de
Git. No contienen credenciales, UID reales ni datos de un hogar real.

Resultado de compilación: `assembleDebug`, `assembleDebugAndroidTest` y
`testDebugUnitTest` correctos. **96 pruebas unitarias aprobadas**, incluidas 13 nuevas
en los dos archivos de paginación/presentación. Sin errores ni pruebas omitidas.

La ejecución mediante `connectedDebugAndroidTest` encontró pérdidas transitorias del
emulador en ADB; se utilizó el mismo APK de tests con el runner Android directamente:

```text
adb -s emulator-5554 shell am instrument -w -e class com.example.safehome.PresentacionSafeHomeTest com.example.safehome.test/androidx.test.runner.AndroidJUnitRunner
```

Resultado final: **16/16 pruebas Compose aprobadas individualmente** usando
`-e class com.example.safehome.PresentacionSafeHomeTest#nombreDelMetodo`, en
`SafeHome_Simulador` (Android 15). La ejecución agrupada no se declara estable:
hubo pérdidas de transporte ADB tanto en Gradle como en alguna ejecución directa.
No se alteraron flags, configuración del SDK o conexiones de la aplicación para
sortear esa dificultad. Se revisaron capturas de Panel, Historial y Cuenta con el
marco completo, Panel a 320/360/800 dp y navegación a 320 dp con fuente 1.8.

La suite incluye viewports sintéticos de 320 dp con fuente 1.8, 360 dp y 800 dp,
escalando el último para caber sin recorte en el emulador. No equivale a tres
dispositivos físicos. Hay además previews de 390/412 dp y horizontal 840 × 500 dp;
su disponibilidad/compilación no constituye una prueba visual de todas esas variantes.

No se ha realizado una auditoría TalkBack ni una validación física con ESP32. La
compilación y los callbacks de pruebas no demuestran el funcionamiento del hardware.
No se publicaron mensajes MQTT ni se cambiaron secretos, filtros de historial,
esquema Room, Auth, topics, TLS, timeout, UUID, reconexión o permisos.
