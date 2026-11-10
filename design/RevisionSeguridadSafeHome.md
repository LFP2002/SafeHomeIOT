# Revisión de seguridad — cuenta única Estación

## Cambios de superficie

- Auth Firebase se conserva; solo un perfil servidor activo con rol exacto `estacion`
  habilita el panel. Roles heredados se rechazan sin actualizar ni borrar sus documentos.
- Se retiran Bluetooth, USB, Realtime Database, servicios foreground, notificaciones,
  presencia de teléfonos y comandos físicos Android. El manifiesto conserva solo INTERNET.
- Android no posee credenciales EMQX ni publica eventos de hardware. Solo lee las cuatro
  rutas del contrato. El backend autenticado y sus permisos aún están pendientes.
- Las únicas escrituras nuevas Android son perfil/hogar del registro explícito. Se
  comprueba inexistencia y se admite el reintento exacto de una inicialización propia;
  nunca se sobrescribe un registro incompatible ni se elimina Auth como compensación.
  Una transacción ya enviada puede finalizar después de logout: conserva la ruta del
  nuevo usuario original; sus callbacks no restauran una sesión cancelada.

## Aislamiento local

Al cerrar sesión, cambiar UID/hogar, revocar perfil o destruir el coordinador se retiran
listeners y se invalida su generación. Las respuestas antiguas no pueden publicar en
la vista nueva. La comprobación cliente no sustituye autorización Firestore por hogar.
Errores Firestore usan textos acotados sin mensajes arbitrarios del SDK, tokens o IDs.
Contraseñas del formulario no se guardan en SavedState. Auth gestiona su propia sesión.

La caché Firestore se identifica visualmente; no se presenta como confirmación actual.
El perfil en caché no habilita acceso. Los datos locales no se presentan a otra cuenta,
pero la persistencia SDK no es cifrado adicional ni protección frente a acceso privilegiado.

`allowBackup=false`, `backup_rules.xml` y `data_extraction_rules.xml` mantienen excluidos
los dominios de datos privados (Android 8–11 y cloud/transferencia Android 12+). Incluye
la caché Firebase y las preferencias históricas `historial_hardware_pendiente.xml`, que
permanecen sin leer/borrar/enviar hasta acordar migración. No se cambia esa política.

## Git y configuración

`google-services.json`, claves privadas, almacenes y credenciales locales siguen
ignorados. No se publica la configuración Firebase real. El workflow CI usa valores
demostrativos solo para Google Services y solo sube XML JUnit, nunca el APK ficticio.

## Evidencia pendiente

No se han inspeccionado ni desplegado reglas Firestore, Cloud Functions, firmware o
ACL/TLS EMQX. No se modifican usuarios existentes desde esta herramienta. Fakes JVM no
demuestran autorización remota, envío desde broker, validez del Last Will, restauración
OEM o correspondencia entre dispositivos físicos y hogar. Esa validación debe preceder
al despliegue del contrato descrito en `ContratoFirestoreSupervision.md`.
