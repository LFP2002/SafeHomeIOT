import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { EventProcessor } from "../../src/processor.js";
import { FirestoreRest } from "../../src/firestore-rest.js";
import { BROKER, connected, disconnected, door, MemoryStorage, NOW, provision } from "../helpers.js";

if (process.env.FIRESTORE_EMULATOR_HOST !== "127.0.0.1:8085") throw new Error("Emulador local requerido");
const projectId = "demo-safehome-backend";
const app = initializeApp({ projectId }, "emqx-tests");
const db = getFirestore(app);
const processor = new EventProcessor(new FirestoreRest({ project: projectId,
  auth: { token: async () => "owner", invalidate() {} }, emulatorOrigin: "http://127.0.0.1:8085" }), BROKER);
let env: RulesTestEnvironment;
before(async () => {
  env = await initializeTestEnvironment({ projectId, firestore: { host: "127.0.0.1", port: 8085,
    rules: readFileSync("security/firestore.proposed.rules", "utf8") } });
});
after(async () => { await env?.cleanup(); await deleteApp(app); });
beforeEach(async () => {
  await env.clearFirestore();
  const seed = new MemoryStorage(); provision(seed);
  const batch = db.batch();
  for (const [path, data] of seed.docs) batch.set(db.doc(path), data);
  await batch.commit();
});

test("REST escribe atómicamente pese a reglas cliente deny-write; SDK confirma tipos Timestamp", async () => {
  assert.equal(await processor.process(connected()), "baseline");
  assert.equal(await processor.process(disconnected()), "applied");
  const state = (await db.doc("hogares/home-a/dispositivos/estacion").get()).data()!;
  assert.equal(state.schemaVersion, 1); assert.equal(state.mqtt, "DESCONECTADO");
  assert.ok(state.actualizadoEn instanceof Timestamp);
  const history = await db.collection("hogares/home-a/historial").get();
  assert.equal(history.size, 1);
  assert.equal(history.docs[0]?.get("creadoEn").toMillis(), NOW - 5000);
});

test("contención real Firestore deduplica entregas concurrentes", async () => {
  await processor.process(connected());
  const results = await Promise.all(Array.from({ length: 6 }, () => processor.process(disconnected())));
  assert.equal(results.filter(r => r === "applied").length, 1);
  assert.equal(results.filter(r => r === "duplicate").length, 5);
  assert.equal((await db.collection("hogares/home-a/historial").get()).size, 1);
});

test("puerta y cursor comparten transacción sin lecturas después de escribir", async () => {
  assert.equal(await processor.process(door()), "baseline");
  assert.equal(await processor.process(door(1, "PUERTA_ABIERTA", NOW - 9000)), "applied");
  assert.equal(await processor.process(door(2, "PUERTA_CERRADA", NOW - 11000)), "stale");
  const state = (await db.doc("hogares/home-a/estadoFisico/actual").get()).data()!;
  assert.equal(state.puerta, "ABIERTA"); assert.equal(state.alarma, undefined);
  assert.equal((await db.collection("hogares/home-a/historial").get()).size, 1);
});
