# Clasificación de los 22 archivos originales

Referencia íntegra: [reference/README.md](reference/README.md). No hubo eliminaciones
antes de verificar ese ZIP. Rama revisada: `feat/integracion-iot-android`; se conservan
los cambios visuales previos sin commit.

| Archivo original | Clasificación | Migración |
| --- | --- | --- |
| `.gitignore` | Adaptable | Ignorar salida Wrangler y secretos locales |
| `EMQX.md` | Adaptable | Mismo SQL/contrato, URL y secretos Workers |
| `OPERACION.md` | Adaptable | OAuth/IAM/Workers Free en lugar de Blaze/Functions |
| `README.md` | Adaptable | Arquitectura, pruebas y cuotas nuevas |
| `firebase.local.json` | Específico reemplazable | Solo Firestore Emulator; quitar Functions/predeploy |
| `package-lock.json` | Adaptable | Regenerar; preservar original en ZIP |
| `package.json` | Adaptable | Eliminar Functions; Admin solo para tests Node/emulador |
| `security/firestore.proposed.rules` | Reutilizable sin cambios | Propuesta local; no desplegar |
| `src/errors.ts` | Reutilizable sin cambios | Errores HTTP saneados |
| `src/events.ts` | Reutilizable sin cambios | Mismos esquemas, hashes, IDs y contrato |
| `src/http.ts` | Reutilizable sin cambios | Política HTTP compartida; fetch limita stream antes de invocarla |
| `src/index.ts` | Específico reemplazable | Handler fetch, secretos Workers y composición REST |
| `src/processor.ts` | Adaptable | Solo importar Timestamp neutral, sin cambiar transiciones |
| `src/registry.ts` | Reutilizable sin cambios | Misma autoridad identidad–destino–hogar |
| `src/storage.ts` | Adaptable | Conservar interfaces; mover implementación a REST |
| `test/emulator/rules.test.ts` | Reutilizable sin cambios | Mismos 9 tests de autorización cliente |
| `test/emulator/transactions.test.ts` | Adaptable | Mismos 3 escenarios usando REST en lugar de Admin como escritor |
| `test/helpers.ts` | Reutilizable sin cambios | Fixtures solo en tests, commit atómico simulado |
| `test/http.test.ts` | Reutilizable sin cambios | Mismos 9 tests HTTP |
| `test/processor.test.ts` | Adaptable | Mismos 21 tests; Timestamp neutral |
| `tsconfig.json` | Adaptable | ESM/bundler, tipos Workers, sin emitir Node como servidor |
| `tsconfig.test.json` | Adaptable | Tipado Node de tests y runtime Workers local |

## Dependencias y límites de la adaptación

- `firebase-functions`, defineSecret, onRequest, initializeApp y las opciones de Cloud
  Run no pertenecen a Workers: se retiran del camino de producción. No se solicita
  Firebase Blaze ni se configura Google Secret Manager.
- `firebase-admin`/gRPC no se empaquetan en el Worker. Admin y Firebase CLI se conservan
  solo como herramientas de tests contra el emulador, sin credenciales de producción.
- `Timestamp` del Admin SDK pasa a un valor neutral con codec REST `timestampValue`;
  **no** se escriben fechas como strings ni como números en Firestore.
- `node:crypto` (SHA-256 y timingSafeEqual) y Buffer se conservan con `nodejs_compat`;
  se comprueba su ejecución en workerd. Los hashes de eventos/asociación no cambian.
- Zod conserva los esquemas estrictos. La firma OAuth usa Web Crypto nativo, no una
  librería JWT ni RSA implementado en JavaScript.
- Las transacciones REST hacen begin, lecturas con el mismo ID y commit atómico con
  precondiciones. No se sustituye por GET/PATCH independientes ni por caché de identidad.
- La caché OAuth es por isolate y solo de credenciales de servidor; nunca de permisos
  de dispositivo. Su pérdida no altera integridad: renueva el token o falla cerrado.

Compatibilidad Free: límites y evidencia se documentan en `LIMITES.md`. Ninguna
medición local autoriza a afirmar que el percentil alto o un arranque frío en la red
de Cloudflare caben en los 10 ms reales. Ante un incumplimiento se detiene la activación,
sin eliminar transacciones, firmas o validaciones.
