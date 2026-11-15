# Referencia recuperable: Cloud Functions v2 original

Antes de modificar o retirar archivos se creó `cloud-functions-v2-original.zip` con
los **22 archivos** originales, incluidas sus pruebas, documentación y lockfile.
Cada entrada fue verificada byte a byte mediante SHA-256 contra el archivo original.
No contiene node_modules, compilados, logs, archivos .env ni credenciales reales.

SHA-256 del ZIP:

```text
cb51d19d9f4b42087fa7cb27f033be8b73c96a4582c9189fedb726529f49f812
```

Restaurar únicamente en una carpeta de referencia independiente:

```powershell
Get-FileHash backend/reference/cloud-functions-v2-original.zip -Algorithm SHA256
Expand-Archive backend/reference/cloud-functions-v2-original.zip -DestinationPath <carpeta-nueva>
```

Es una referencia histórica, **no un segundo servicio activo**. No desplegar Cloud
Functions ni seguir sus instrucciones antiguas de Blaze/Secret Manager. Restaurar
una referencia no autoriza a reemplazar datos, reglas remotas o cambios de Android.
