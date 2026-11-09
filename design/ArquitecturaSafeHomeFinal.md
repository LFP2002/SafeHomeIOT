# SafeHome — cuenta única Estación y supervisión Firebase

## Arquitectura vigente

```text
ESP32 Portátil -- heartbeat MQTT/TLS --> EMQX <-- MQTT/TLS -- ESP32 Estación
                                          |
                          backend autenticado (PENDIENTE)
                                          |
                                       Firestore
                                          |
                   RepositorioFirestoreSupervision (solo lectura)
                                          |
                   CoordinadorSupervision / PanelSupervision
                                          |
                     Panel / Historial / Cuenta (Compose)
```

Android no se conecta a EMQX, Bluetooth ni USB. El firmware Estación ya no dispone
de Bluetooth Classic. La lógica de alarma física continúa fuera de Android y no se
modifica ni se declara comprobada aquí. La pérdida de MQTT no demuestra una salida
del hogar y no se presenta como presencia de personas.

## Separación de responsabilidades

- `PerfilUsuario`, `RegistroEstacion`, `PoliticaContrasena`, `IdentidadHogar`:
  validación pura de acceso Estación, formularios y nombres.
- `autenticacion/RepositorioCuentas`: interfaz de Auth/perfil/registro.
- `autenticacion/RepositorioFirebaseCuentas`: implementación Firebase real.
- `autenticacion/SesionEstacion`: coordina Auth, confirmación de perfil desde servidor,
  estados de acceso, registro explícito y callbacks por generación. Solo autoriza
  `rol=estacion`, `activo=true`, hogar y nombre válidos; observa revocaciones.
- `iot/ModelosSupervision` y `iot/HistorialSupervision`: dominio sin Firebase/Android,
  parsing estricto, tiempos explícitos y compatibilidad histórica etiquetada.
- `iot/RepositorioSupervision`: interfaz de suscripciones de solo lectura.
- `iot/RepositorioFirestoreSupervision`: cuatro observadores, metadatos de caché,
  adaptación Timestamp y errores saneados. No publica eventos, estado ni comandos.
- `iot/CoordinadorSupervision`: cuatro suscripciones independientes y cancelables;
  reinicia la vista e invalida callbacks al cambiar UID/hogar/generación.
- `ControlSesion`: compone repositorios/coordinadores; no concentra reglas ni UI.
- `SafeHomeApp`: navegación. `presentacion/` contiene acceso, panel, historial, cuenta
  y componentes visuales. Se conservan tema, tipografía y colores existentes.

## Cuenta y datos existentes

Solo hay registro de Estación, login, recuperación genérica y logout. Los roles
heredados no obtienen panel ni observadores del hogar; la pantalla ofrece cierre de
sesión y reintento, sin borrar datos ni cambiar roles. Perfil de caché no autoriza.
No se ejecuta una migración de usuarios/documentos ni se ofrece eliminación de cuentas.
El registro crea exclusivamente perfil/hogar de la nueva cuenta, sin estados físicos
iniciales inventados, códigos de vinculación ni borrado compensatorio de Auth.

Se retiraron Realtime Database, servicios/notificaciones de teléfonos, vinculación,
actuadores/comandos Android, transportes, procesadores SH1 y sincronización de la cola.
La cola privada anterior no se abre, elimina ni envía. Su posible migración requiere
acuerdo explícito; los respaldos privados permanecen desactivados/excluidos.

## Datos y errores

El esquema completo y la responsabilidad de cada escritor están en
[ContratoFirestoreSupervision.md](ContratoFirestoreSupervision.md), documentado antes
de implementar los lectores. Android muestra carga, ausencia, incompatibilidad y
errores por recurso. Nunca usa `estado/actual.personaPresente` para derivar MQTT.
Muestra fechas de observación, último heartbeat y último cambio de puerta cuando
existen; no aplica timeouts MQTT ni calcula alarmas físicas.

Los datos en caché o con escrituras pendientes sin confirmar se etiquetan. Una Estación desconectada/desconocida, un error o
caché marcan la puerta conocida como posiblemente desactualizada. El historial muestra
los 100 documentos recientes por creadoEn, advierte el límite y cuenta inválidos.
Deduplicación de UI por ID; ni heartbeats ni snapshots generan escrituras Android.
No se confunden eventos históricos de teléfonos con los ESP32.

## Verificación y límites

Los tests actuales cubren validadores, parsing, compatibilidad, duplicados, errores,
cancelación, cambios de UID/hogar, revocación, registro y callbacks tardíos con repositorios
fake. Las suites exclusivas del diseño retirado se reemplazan; el total anterior no
representa cobertura del diseño nuevo. No se finge conservar ese número de tests.

CI conserva Ubuntu, Temurin 25 (criterio JVM del proyecto), API 37, Wrapper 9.8.2,
tests y assembleDebug; su Firebase ficticio es exclusivamente de compilación CI y no
se instala ni distribuye. Producción usa el archivo Firebase real local, ignorado por Git.
No se ejecutan tests contra usuarios/documentos reales durante esta refactorización.

Pendientes: backend EMQX → Firebase, reglas revisadas, contrato de publicación firmware,
pruebas reales de seguridad entre hogares, tiempos y hechos físicos. Ver
[PlanPruebasFisicas.md](PlanPruebasFisicas.md). La integración física **no está terminada**.
