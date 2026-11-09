import { before, after, test } from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { runtime } from "../workers/harness.js";
import { MemoryStorage, provision, connected, disconnected, station } from "../helpers.js";

if (process.env.FIRESTORE_EMULATOR_HOST !== "127.0.0.1:8085") throw new Error("Solo emulador local");
const app = initializeApp({ projectId: "demo-safehome-backend" }, "worker-rest-test");
const db = getFirestore(app);
before(async () => {
  const response = await fetch("http://127.0.0.1:8085/emulator/v1/projects/demo-safehome-backend/databases/(default)/documents", { method: "DELETE" });
  assert.equal(response.ok, true);
  const seed = new MemoryStorage(); provision(seed);
  const batch = db.batch();
  for (const [path, data] of seed.docs) batch.set(db.doc(path), path.includes("/identidades/")
    ? { ...data, habilitadoDesde: Date.now() - 100000 } : data);
  await batch.commit();
});
after(async () => { await deleteApp(app); });

test("workerd → REST transaccional → emulador → lector SDK confirma contrato", async () => {
  const s = await runtime(true, false, async (url, init) => {
    if (!url.startsWith("https://firestore.googleapis.com/v1/projects/demo-safehome-backend/"))
      throw new Error("remote_project_forbidden");
    // El proxy local no reenvía headers hop-by-hop que añade workerd (Host/length/encoding).
    return fetch(url.replace("https://firestore.googleapis.com", "http://127.0.0.1:8085"), {
      method: init.method, body: init.body, redirect: "manual",
      headers: { Authorization: "Bearer owner", "Content-Type": "application/json" }
    });
  });
  try {
    assert.equal((await s.send(connected(station, s.now - 10000))).status, 200);
    const event = disconnected(station, s.now - 10000, s.now - 5000);
    assert.equal((await s.send(event)).status, 200);
    assert.equal((await (await s.send(event)).json() as { code: string }).code, "duplicate");
    const state = (await db.doc("hogares/home-a/dispositivos/estacion").get()).data()!;
    assert.equal(state.schemaVersion, 1); assert.equal(state.mqtt, "DESCONECTADO");
    assert.ok(state.actualizadoEn instanceof Timestamp);
    const history = await db.collection("hogares/home-a/historial").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("creadoEn").toMillis(), event.occurredAt);
  } finally { await s.mf.dispose(); }
});
