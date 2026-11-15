import { test } from "node:test";
import assert from "node:assert/strict";
import { BROKER, connected, disconnected, door, portable, SECRET, station } from "../helpers.js";
import { runtime } from "./harness.js";

test("workerd real: autenticación y contratos se validan antes de OAuth/Firestore", async () => {
  const s = await runtime();
  try {
    assert.equal((await s.mf.dispatchFetch("https://worker.test/emqx")).status, 405);
    assert.equal((await s.mf.dispatchFetch("https://worker.test/other")).status, 404);
    assert.equal((await s.send({}, "wrong")).status, 401);
    assert.equal((await s.send({ ...connected(station, s.now - 10000), hogarId: "forged" })).status, 422);
    assert.equal((await s.send({ value: "x".repeat(9000) })).status, 413);
    assert.equal(s.oauthCalls(), 0); assert.equal(s.fixture.requests.length, 0);
  } finally { await s.mf.dispose(); }
});

test("workerd real: firma RS256, native hashes, REST y deduplicación de ambos ESP32", async () => {
  const s = await runtime();
  try {
    for (const who of [station, portable]) {
      const initial = await s.send(connected(who, s.now - 10000));
      assert.equal(initial.status, 200, JSON.stringify({ oauth: s.oauthCalls(), calls: s.fixture.requests.length,
        reply: await initial.json() }));
      const e = disconnected(who, s.now - 10000, s.now - 5000);
      assert.equal((await s.send(e)).status, 200);
      const repeated = await s.send(e);
      assert.equal((await repeated.json() as { code: string }).code, "duplicate");
    }
    assert.equal(s.oauthCalls(), 1);
    assert.equal([...s.fixture.docs.keys()].filter(p => p.includes("/historial/")).length, 2);
    assert.equal(s.fixture.data("hogares/home-a/dispositivos/estacion")?.mqtt, "DESCONECTADO");
    assert.equal(s.fixture.data("hogares/home-a/dispositivos/portatil")?.mqtt, "DESCONECTADO");
  } finally { await s.mf.dispose(); }
});

test("workerd real: puerta, secuencia, boot y disconnect de sesión vieja", async () => {
  const s = await runtime();
  try {
    await s.send(connected(station, s.now - 10000));
    await s.send(connected(station, s.now - 1000));
    await s.send(disconnected(station, s.now - 10000, s.now));
    assert.equal(s.fixture.data("hogares/home-a/dispositivos/estacion")?.mqtt, "CONECTADO");
    for (const e of [door(3, "PUERTA_CERRADA", s.now - 8000), door(4, "PUERTA_ABIERTA", s.now - 7000),
      door(5, "PUERTA_CERRADA", s.now - 6000), door(2, "PUERTA_ABIERTA", s.now - 9000)])
      assert.equal((await s.send(e)).status, 200);
    assert.equal(s.fixture.data("hogares/home-a/estadoFisico/actual")?.puerta, "CERRADA");
    assert.equal([...s.fixture.docs.keys()].filter(p => p.includes("/historial/")).length, 2);
  } finally { await s.mf.dispose(); }
});

test("workerd real: ABORTED, respuesta de commit perdida y reintento seguro", async () => {
  const s = await runtime();
  try {
    s.fixture.aborts = 1;
    assert.equal((await s.send(connected(station, s.now - 10000))).status, 200);
    s.fixture.dropCommitResponse = true;
    const e = disconnected(station, s.now - 10000, s.now - 5000);
    assert.equal((await s.send(e)).status, 503);
    assert.equal((await (await s.send(e)).json() as { code: string }).code, "duplicate");
    assert.equal([...s.fixture.docs.keys()].filter(p => p.includes("/historial/")).length, 1);
  } finally { await s.mf.dispose(); }
});

test("workerd real: configuración inicial deshabilitada falla cerrado", async () => {
  const s = await runtime(false);
  try {
    assert.equal((await s.send(connected(station, s.now - 10000))).status, 503);
    assert.equal(s.oauthCalls(), 0); assert.equal(s.fixture.requests.length, 0);
  } finally { await s.mf.dispose(); }
});

test("workerd real: concurrencia fría comparte OAuth y conserva un único recibo", async () => {
  const s = await runtime();
  try {
    const responses = await Promise.all(Array.from({ length: 4 }, () => s.send(connected(station, s.now - 10000))));
    assert.ok(responses.every(r => r.status === 200));
    const codes = await Promise.all(responses.map(r => r.json() as Promise<{ code: string }>));
    assert.equal(codes.filter(r => r.code === "baseline").length, 1);
    assert.equal(codes.filter(r => r.code === "duplicate").length, 3);
    assert.equal(s.oauthCalls(), 1);
  } finally { await s.mf.dispose(); }
});
