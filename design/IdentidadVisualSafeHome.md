# Identidad visual de SafeHome

## Referencia revisada antes de implementar

- Refactorización funcional conservada: `2ea719d`.
- Referencia visual inmediatamente anterior: `585629d`, especialmente `SafeHomeApp.kt`,
  `ui/theme/Color.kt`, `ui/theme/Type.kt` y `ui/theme/Theme.kt`.
- Los archivos de tema no habían cambiado durante la refactorización. Se conservan
  sus colores y las fuentes Nunito locales (regular, semibold y bold).
- Se retoman el degradado azul/violeta, el fondo claro, las tarjetas redondeadas
  con borde suave, los medallones de iconos, el acento coral y la selección lila.
- No se recuperan las pantallas, los roles ni los mecanismos de conexión antiguos.

## Alcance

Cambios exclusivamente en presentación Compose, navegación visual, estilos
tipográficos y recursos. Los callbacks de acceso, cierre de sesión y reintento
continúan siendo los mismos. No hay cambios en autenticación, sesión, modelos,
repositorios, consultas ni reglas de Firebase.

Los estados se comunican mediante texto, icono y color, no solo mediante color:

| Estado | Tratamiento |
| --- | --- |
| MQTT conectado | Icono de conexión, verde, texto explícito MQTT |
| MQTT desconectado | Icono de conexión interrumpida, ámbar |
| Desconocido / sin datos | Interrogación y color neutro |
| Error / contrato incompatible | Cruz y color crítico |
| Caché | Aviso explícito y etiqueta «Último dato guardado» sin verde de confirmación |
| Puerta | Iconos distintos para abierta y cerrada; no equivale a seguridad confirmada |
| Alarma | Normal, advertencia, crítica y desconocida diferenciadas |

Se conserva la advertencia de posible desactualización de la puerta, la distinción
entre MQTT y presencia física y el aviso de integración EMQX → Firebase pendiente.
El historial conserva la fecha, el tipo, el dispositivo y la advertencia de registro
anterior; no deriva el estado actual de un evento ni altera su orden.

La interfaz usa una columna en móviles y con fuente ampliada; el panel usa dos
columnas a partir de 640 dp disponibles y escala tipográfica de hasta 1.3. El
contenedor principal está centrado y limitado a 960 dp; acceso a 480 dp. Las
pantallas permiten desplazamiento y no fijan la altura del texto.

Se mantiene el tema claro que ya utiliza la aplicación. No se introduce soporte
oscuro o dinámico inexistente ni una dependencia web.

## Bootstrap Icons

- Fuente oficial fijada: <https://github.com/twbs/icons/tree/v1.11.3/icons>.
- Licencia MIT incluida en el APK: `app/src/main/assets/licenses/bootstrap-icons.txt`.
- Los 17 recursos `app/src/main/res/drawable/bi_*.xml` corresponden al SVG del mismo
  nombre, sustituyendo guiones bajos por guiones (por ejemplo, `bi_clock_history.xml`
  procede de `clock-history.svg`).
- Conversión: `viewBox="0 0 16 16"` pasa a viewport de 16 × 16; tamaño intrínseco
  24 dp; cada `path d` se conserva como `android:pathData`; `fill-rule="evenodd"`
  pasa a `android:fillType="evenOdd"`. El relleno es negro opaco y Compose aplica
  el tinte. El icono de salida admite reflejo RTL.
- Se verificaron los paths de todos los vectores contra los SVG oficiales.
- `IconoSafeHome` centraliza los recursos. Los iconos junto a texto son decorativos
  (`contentDescription = null`) para evitar lecturas duplicadas de accesibilidad.
  Un icono independiente puede recibir descripción explícita.

No se incorporan Bootstrap CSS, HTML, JavaScript, WebView ni descargas en ejecución.

## Validación

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest :app:lintDebug --console=plain
```

Se añaden pruebas de presentación para los estados e iconos de todos los tipos de
evento admitidos, la navegación seleccionada, los errores, la caché, el historial,
el renderizado de todos los vectores, una pantalla de 320 dp con fuente a 1.8
y la disposición en dos columnas a 800 dp.
Los datos de prueba se encuentran exclusivamente en `src/test` y `src/androidTest`;
no existen datos simulados añadidos a pantallas de producción.
