import { test } from "node:test";
import assert from "node:assert/strict";
import { FirestoreRest } from "../src/firestore-rest.js";
import { EventProcessor } from "../src/processor.js";
import { decodeFields, encodeFields } from "../src/firestore-codec.js";
import { Timestamp } from "../src/timestamp.js";
import { BROKER, connected, disconnected, door } from "./helpers.js";
import { RestFixture, PROJECT } from "./rest-fixture.js";

function setup() {
  const fixture = new RestFixture(); let invalidations = 0;
  const storage = new FirestoreRest({ project: PROJECT, http: fixture.http, sleep: async () => {},
    auth: { token: async () => "only-test-token", invalidate() { invalidations++; } } });
  return { fixture, storage, processor: new EventProcessor(storage, BROKER), invalidations: () => invalidations };
}

test("codec conserva Timestamp real, nanosegundos, integerValue y campos opcionales", () => {
  const ts = Timestamp.fromRFC3339("2026-11-09T12:30:01.123456789Z");
  const input = { schemaVersion: 1, ahora: ts, flag: false, optional: null, nested: { value: 2 }, array: ["x", true] };
  const encoded = encodeFields(input);
  assert.deepEqual(encoded.schemaVersion, { integerValue: "1" });
  assert.deepEqual(encoded.ahora, { timestampValue: "2026-11-09T12:30:01.123456789Z" });
  assert.deepEqual(decodeFields(encoded), input);
  assert.throws(() => Timestamp.fromRFC3339("2026-02-30T12:00:00Z"));
  assert.throws(() => decodeFields({ x: { integerValue: "9007199254740993" } }));
});

test("cada lectura REST lleva ID de transacción; commit atómico tiene precondiciones", async () => {
  const s = setup(); await s.processor.process(connected()); await s.processor.process(disconnected());
  const reads = s.fixture.requests.filter(r => r.url.endsWith(":batchGet"));
  assert.ok(reads.length > 0);
  assert.ok(reads.every(r => r.method === "POST" && typeof r.body.transaction === "string"));
  assert.ok(s.fixture.requests.every(r => r.authorization === "Bearer only-test-token"));
  const last = s.fixture.commits.at(-1);
  assert.ok(last.transaction); assert.equal(last.writes.length, 4);
  for (const w of last.writes) assert.ok(w.currentDocument.exists === false || typeof w.currentDocument.updateTime === "string");
  assert.equal(last.writes.find((w: any) => w.update.name.includes("/historial/")).update.fields.creadoEn.timestampValue,
    Timestamp.fromMillis(disconnected().occurredAt).toRFC3339());
});

test("ABORTED repite toda la autoridad y las lecturas, no solo el commit", async () => {
  const s = setup(); const size = s.fixture.docs.size; s.fixture.aborts = 2;
  assert.equal(await s.processor.process(connected()), "baseline");
  assert.equal(s.fixture.nextTx, 3);
  assert.equal(s.fixture.requests.filter(r => r.url.endsWith(":batchGet") && r.body.documents[0].includes("/identidades/")).length, 3);
  assert.equal(s.fixture.docs.size, size + 3); // recibo/cursor/estado
});

test("contención optimista conserva un historial único", async () => {
  const s = setup(); await s.processor.process(connected());
  const results = await Promise.all(Array.from({ length: 4 }, () => s.processor.process(disconnected())));
  assert.equal(results.filter(r => r === "applied").length, 1);
  assert.equal([...s.fixture.docs.keys()].filter(p => p.includes("/historial/")).length, 1);
});

test("commit con respuesta perdida no se reintenta a ciegas; webhook repetido deduplica", async () => {
  const s = setup(); await s.processor.process(connected());
  s.fixture.dropCommitResponse = true;
  await assert.rejects(s.processor.process(disconnected()));
  assert.equal(await s.processor.process(disconnected()), "duplicate");
  assert.equal([...s.fixture.docs.keys()].filter(p => p.includes("/historial/")).length, 1);
});

test("fallos de cuota, IAM y 401 fallan cerrado sin degradar transacción", async () => {
  for (const [code, status] of [["RESOURCE_EXHAUSTED", 429], ["PERMISSION_DENIED", 403], ["UNAUTHENTICATED", 401]] as const) {
    const s = setup(); const size = s.fixture.docs.size;
    s.fixture.failure = { operation: "commit", code, status };
    await assert.rejects(s.processor.process(connected()));
    assert.equal(s.fixture.docs.size, size);
    assert.equal(s.fixture.nextTx, 1);
    assert.equal(s.invalidations(), status === 401 ? 1 : 0);
  }
});

test("cuatro abortos acotan subsolicitudes sin confirmar nada", async () => {
  const s = setup(); s.fixture.aborts = 99;
  const size = s.fixture.docs.size;
  await assert.rejects(s.processor.process(door()));
  assert.equal(s.fixture.nextTx, 4);
  assert.ok(s.fixture.requests.length <= 40);
  assert.equal(s.fixture.docs.size, size);
});

test("no admite lectura después de escritura, set sin leer ni endpoint alternativo real", async () => {
  const s = setup();
  await assert.rejects(s.storage.transaction(async tx => { tx.create("test/doc", { value: 1 }); await tx.get("test/doc"); }));
  await assert.rejects(s.storage.transaction(async tx => { tx.set("test/doc", { value: 1 }); }));
  assert.throws(() => new FirestoreRest({ project: "real-project", emulatorOrigin: "http://127.0.0.1:8085",
    auth: { token: async () => "fake", invalidate() {} } }));
});
