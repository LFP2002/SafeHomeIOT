import { test } from "node:test";
import assert from "node:assert/strict";
import { authentic, handle, type RequestInput } from "../src/http.js";
import { EventProcessor } from "../src/processor.js";
import { MAX_BODY_BYTES } from "../src/events.js";
import { BROKER, connected, door, MemoryStorage, NOW, provision, SECRET } from "./helpers.js";

function setup() {
  const db = new MemoryStorage();
  provision(db);
  const processor = new EventProcessor(db, BROKER);
  let logs = 0;
  const deps = { secret: () => SECRET, now: () => NOW,
    process: processor.process.bind(processor), logFailure: () => { logs++; } };
  const input = (body: unknown = connected(), overrides: Partial<RequestInput> = {}): RequestInput => ({
    method: "POST", secret: SECRET, contentType: "application/json", rawBody: Buffer.from(JSON.stringify(body)), ...overrides
  });
  return { db, deps, input, logs: () => logs };
}

test("comparación segura exige secreto configurado y exacto", () => {
  assert.equal(authentic(SECRET, SECRET), true);
  for (const bad of [undefined, "", "bad", `${SECRET}x`, "x".repeat(513)])
    assert.equal(authentic(bad, SECRET), false);
  assert.equal(authentic("short", "short"), false);
});

test("POST autenticado aplica primera observación sin historial", async () => {
  const s = setup();
  assert.deepEqual(await handle(s.input(), s.deps), { status: 200, code: "baseline" });
  assert.equal([...s.db.docs.keys()].filter(p => p.includes("/historial/")).length, 0);
});

test("métodos no POST se rechazan con Allow", async () => {
  const s = setup();
  for (const method of ["GET", "PUT", "DELETE", "OPTIONS", "HEAD"])
    assert.deepEqual(await handle(s.input(undefined, { method }), s.deps),
      { status: 405, code: "method_not_allowed", headers: { Allow: "POST" } });
});

test("autenticación falla antes de consultar Firestore", async () => {
  const s = setup();
  const size = s.db.docs.size;
  for (const secret of [undefined, "wrong", `${SECRET}x`])
    assert.equal((await handle(s.input({}, { secret }), s.deps)).status, 401);
  assert.equal(s.db.docs.size, size);
  assert.equal(s.logs(), 0);
});

test("secreto ausente en servidor falla cerrado", async () => {
  const s = setup();
  assert.equal((await handle(s.input(), { ...s.deps, secret: () => "" })).status, 503);
});

test("validación de JSON, formato, codificación y tamaño", async () => {
  const s = setup();
  for (const contentType of [undefined, "text/plain", "application/json; charset=latin1"])
    assert.equal((await handle(s.input({}, { contentType }), s.deps)).status, 415);
  assert.equal((await handle(s.input({}, { contentEncoding: "gzip" }), s.deps)).status, 415);
  assert.equal((await handle(s.input({}, { rawBody: Buffer.alloc(MAX_BODY_BYTES + 1) }), s.deps)).status, 413);
  assert.equal((await handle(s.input({}, { rawBody: Buffer.from("{") }), s.deps)).status, 400);
});

test("rechaza payloads falsificados y tipos no soportados", async () => {
  const s = setup();
  const payloads = [null, [], {}, { ...connected(), hogarId: "victim" },
    { ...connected(), type: "PUERTA_ABIERTA" }, { ...connected(), source: "heartbeat" },
    { ...connected(), connected_at: undefined }, { ...connected(), occurredAt: "1800000000000" },
    { ...connected(), occurredAt: NOW + 120_000 }, { ...connected(), occurredAt: NOW - 8 * 86400000 },
    { ...door(), bootId: undefined }, { ...door(), sequence: -1 }, { ...door(), sequence: 1.5 },
    { ...door(), retained: true }, { ...door(), type: "ALARMA_CRITICA" },
    { ...door(), receivedAt: NOW - 200_000 }, { ...connected(), clientid: "../victim" }];
  for (const payload of payloads) assert.equal((await handle(s.input(payload), s.deps)).status, 422, JSON.stringify(payload));
});

test("rechaza usuarios MQTT y ClientIDs desconocidos", async () => {
  const s = setup();
  for (const event of [{ ...connected(), username: "forged" }, { ...connected(), clientid: "forged" }])
    assert.equal((await handle(s.input(event), s.deps)).status, 403);
});

test("fallo transitorio no confirma ni escribe parcialmente; el reintento funciona", async () => {
  const s = setup();
  const size = s.db.docs.size;
  s.db.failNext = true;
  const reply = await handle(s.input(), s.deps);
  assert.deepEqual(reply, { status: 503, code: "temporarily_unavailable", headers: { "Retry-After": "10" } });
  assert.equal(s.db.docs.size, size);
  assert.equal(s.logs(), 1);
  assert.equal(JSON.stringify(reply).includes("DO_NOT_LOG"), false);
  assert.equal((await handle(s.input(), s.deps)).code, "baseline");
  assert.equal((await handle(s.input(), s.deps)).code, "duplicate");
});
