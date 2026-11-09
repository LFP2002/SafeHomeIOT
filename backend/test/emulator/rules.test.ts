import { after, before, beforeEach, test } from "node:test";
import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, limit, orderBy, query, runTransaction,
  serverTimestamp, setDoc, Timestamp, updateDoc, writeBatch, deleteDoc, setLogLevel } from "firebase/firestore";

const projectId = "demo-safehome-backend";
if (process.env.FIRESTORE_EMULATOR_HOST !== "127.0.0.1:8085")
  throw new Error("Solo puede ejecutarse en el emulador local 127.0.0.1:8085");
let env: RulesTestEnvironment;
before(async () => {
  // Los PERMISSION_DENIED esperados se verifican con assertFails, sin ruido del SDK.
  setLogLevel("silent");
  env = await initializeTestEnvironment({ projectId, firestore: {
    host: "127.0.0.1", port: 8085,
    rules: readFileSync("security/firestore.proposed.rules", "utf8")
  } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    const batch = writeBatch(db);
    for (const [uid, home, rol, activo] of [
      ["alice", "home-a", "estacion", true], ["bob", "home-b", "estacion", true],
      ["inactive", "home-a", "estacion", false], ["legacy", "home-a", "movil", true]
    ] as const) batch.set(doc(db, `usuarios/${uid}`), { rol, activo, hogarId: home, nombre: "Nombre Test" });
    for (const home of ["home-a", "home-b"]) {
      batch.set(doc(db, `hogares/${home}`), { nombre: "Hogar test", creadoEn: Timestamp.now() });
      batch.set(doc(db, `hogares/${home}/dispositivos/estacion`), { schemaVersion: 1, mqtt: "CONECTADO", actualizadoEn: Timestamp.now() });
      batch.set(doc(db, `hogares/${home}/dispositivos/portatil`), { schemaVersion: 1, mqtt: "DESCONECTADO", actualizadoEn: Timestamp.now() });
      batch.set(doc(db, `hogares/${home}/estadoFisico/actual`), { schemaVersion: 1, puerta: "CERRADA", actualizadoEn: Timestamp.now() });
      batch.set(doc(db, `hogares/${home}/historial/test-event`), { schemaVersion: 1, tipo: "PUERTA_ABIERTA", dispositivo: "estacion", creadoEn: Timestamp.now() });
      batch.set(doc(db, `hogares/${home}/_ingesta/estacion`), { privado: true });
    }
    batch.set(doc(db, "integracionesEmqx/broker/identidades/test"), { privado: true });
    batch.set(doc(db, "_emqxRecibos/test"), { privado: true });
    batch.set(doc(db, "_emqxBoots/test"), { privado: true });
    await batch.commit();
  });
});

test("Estación activa lee exactamente los documentos y consulta historial como Android", async () => {
  const db = env.authenticatedContext("alice").firestore();
  for (const path of ["dispositivos/estacion", "dispositivos/portatil", "estadoFisico/actual"])
    await assertSucceeds(getDoc(doc(db, `hogares/home-a/${path}`)));
  await assertSucceeds(getDocs(query(collection(db, "hogares/home-a/historial"), orderBy("creadoEn", "desc"), limit(100))));
});

test("otro hogar, usuario ausente, rol antiguo, inactivo y anónimo no leen supervisión", async () => {
  const clients = [env.authenticatedContext("bob"), env.authenticatedContext("missing"),
    env.authenticatedContext("legacy"), env.authenticatedContext("inactive"), env.unauthenticatedContext()];
  for (const ctx of clients) {
    const db = ctx.firestore();
    await assertFails(getDoc(doc(db, "hogares/home-a/dispositivos/estacion")));
    await assertFails(getDoc(doc(db, "hogares/home-a/estadoFisico/actual")));
    await assertFails(getDocs(query(collection(db, "hogares/home-a/historial"), orderBy("creadoEn", "desc"), limit(100))));
  }
});

test("Android no puede crear, actualizar ni borrar telemetría o eventos", async () => {
  const db = env.authenticatedContext("alice").firestore();
  for (const path of ["dispositivos/estacion", "dispositivos/nuevo", "estadoFisico/actual", "historial/test-event", "historial/nuevo"]) {
    await assertFails(setDoc(doc(db, `hogares/home-a/${path}`), { schemaVersion: 1 }));
    await assertFails(deleteDoc(doc(db, `hogares/home-a/${path}`)));
  }
  await assertFails(updateDoc(doc(db, "hogares/home-a/dispositivos/estacion"), { mqtt: "DESCONECTADO" }));
});

test("perfiles propios se leen aun inactivos; perfiles ajenos y listados no", async () => {
  await assertSucceeds(getDoc(doc(env.authenticatedContext("inactive").firestore(), "usuarios/inactive")));
  const db = env.authenticatedContext("alice").firestore();
  await assertSucceeds(getDoc(doc(db, "usuarios/alice")));
  await assertFails(getDoc(doc(db, "usuarios/bob")));
  await assertFails(getDocs(collection(db, "usuarios")));
  await assertFails(getDocs(collection(db, "hogares")));
});

test("registro atómico de perfil y hogar propio coincide con Android", async () => {
  const db = env.authenticatedContext("new-user").firestore();
  await assertSucceeds(runTransaction(db, async tx => {
    const profile = doc(db, "usuarios/new-user"); const home = doc(db, "hogares/new-user");
    await tx.get(profile); await tx.get(home);
    tx.set(profile, { rol: "estacion", activo: true, hogarId: "new-user", nombre: "María Test" });
    tx.set(home, { nombre: "Hogar de María Test", creadoEn: serverTimestamp() });
  }));
  // Un reintento exacto de Android solo lee; no intenta actualizar documentos existentes.
  await assertSucceeds(runTransaction(db, async tx => {
    await tx.get(doc(db, "usuarios/new-user")); await tx.get(doc(db, "hogares/new-user"));
  }));
});

test("registro parcial, autoridad ajena y campos privilegiados son rechazados", async () => {
  const db = env.authenticatedContext("new-user").firestore();
  const profile = { rol: "estacion", activo: true, hogarId: "new-user", nombre: "Nombre Test" };
  await assertFails(setDoc(doc(db, "usuarios/new-user"), profile));
  await assertFails(setDoc(doc(db, "hogares/new-user"), { nombre: "Hogar de Nombre Test", creadoEn: serverTimestamp() }));
  for (const invalid of [{ ...profile, hogarId: "home-a" }, { ...profile, rol: "admin" },
    { ...profile, admin: true }, { ...profile, nombre: " Nombre Test" }]) {
    const batch = writeBatch(db);
    batch.set(doc(db, "usuarios/new-user"), invalid);
    batch.set(doc(db, "hogares/new-user"), { nombre: "Hogar de Nombre Test", creadoEn: serverTimestamp() });
    await assertFails(batch.commit());
  }
  await assertFails(setDoc(doc(db, "usuarios/victim"), profile));
});

test("usuario no puede modificar rol, hogar, actividad o borrar perfil", async () => {
  const db = env.authenticatedContext("alice").firestore();
  for (const field of [{ hogarId: "home-b" }, { activo: true }, { rol: "admin" }])
    await assertFails(updateDoc(doc(db, "usuarios/alice"), field));
  await assertFails(deleteDoc(doc(db, "usuarios/alice")));
});

test("revocación servidor corta lectura posterior", async () => {
  const db = env.authenticatedContext("alice").firestore();
  await assertSucceeds(getDoc(doc(db, "hogares/home-a/dispositivos/estacion")));
  await env.withSecurityRulesDisabled(ctx => updateDoc(doc(ctx.firestore(), "usuarios/alice"), { activo: false }));
  await assertFails(getDoc(doc(db, "hogares/home-a/dispositivos/estacion")));
});

test("metadatos administrativos, cursores y rutas heredadas no son accesibles", async () => {
  const db = env.authenticatedContext("alice").firestore();
  for (const path of ["integracionesEmqx/broker/identidades/test", "integracionesEmqx/broker/destinos/test",
    "_emqxRecibos/test", "_emqxBoots/test", "hogares/home-a/_ingesta/estacion",
    "hogares/home-a/moviles/test", "hogares/home-a/estado/actual", "hogares/home-a/comandos/test"]) {
    await assertFails(getDoc(doc(db, path)));
    await assertFails(setDoc(doc(db, path), { forged: true }));
  }
});
