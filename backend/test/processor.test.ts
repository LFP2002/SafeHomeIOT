import { test } from "node:test";
import assert from "node:assert/strict";
import { Timestamp } from "../src/timestamp.js";
import { eventId, identityId } from "../src/events.js";
import { EventProcessor } from "../src/processor.js";
import { destinationId } from "../src/registry.js";
import { BROKER, connected, disconnected, door, MemoryStorage, NOW, portable, provision, station } from "./helpers.js";

function setup() {
  const db = new MemoryStorage(); provision(db); provision(db, portable, "portatil");
  return { db, processor: new EventProcessor(db, BROKER) };
}
function history(db: MemoryStorage) { return [...db.docs.entries()].filter(([p]) => p.includes("/historial/")); }

for (const [who, device, prefix] of [[station, "estacion", "ESTACION"], [portable, "portatil", "PORTATIL"]] as const) {
  test(`conexión, desconexión y reconexión ${device}`, async () => {
    const { db, processor } = setup();
    assert.equal(await processor.process(connected(who)), "baseline");
    assert.equal(history(db).length, 0);
    assert.equal(await processor.process(disconnected(who)), "applied");
    const disconnectType = device === "estacion" ? "ESTACION_DESCONECTADA" : "PORTATIL_DESCONECTADO";
    assert.equal(history(db)[0]?.[1].tipo, disconnectType);
    assert.equal(await processor.process(connected(who, NOW - 1000)), "applied");
    assert.equal(history(db)[1]?.[1].tipo, `${prefix}_${device === "estacion" ? "CONECTADA" : "CONECTADO"}`);
    const state = db.docs.get(`hogares/home-a/dispositivos/${device}`)!;
    assert.equal(state.mqtt, "CONECTADO"); assert.equal(state.schemaVersion, 1);
    assert.ok(state.actualizadoEn instanceof Timestamp);
    assert.equal(history(db).length, 2);
  });
}

test("una primera desconexión tampoco inventa transición", async () => {
  const { db, processor } = setup();
  assert.equal(await processor.process(disconnected()), "baseline");
  assert.equal(history(db).length, 0);
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "DESCONECTADO");
});

test("IDs estables y reintentos concurrentes no duplican eventos", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  const e = disconnected();
  const results = await Promise.all(Array.from({ length: 10 }, () => processor.process(e)));
  assert.equal(results.filter(r => r === "applied").length, 1);
  assert.equal(history(db).length, 1);
  assert.equal(history(db)[0]?.[0], `hogares/home-a/historial/${eventId(BROKER, "revision-1", e)}`);
  assert.equal((history(db)[0]?.[1].creadoEn as Timestamp).toMillis(), e.occurredAt);
});

test("desconexión atrasada de sesión anterior no pisa reconexión más reciente", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  await processor.process(connected(station, NOW - 1000));
  assert.equal(await processor.process(disconnected(station, NOW - 10000, NOW)), "stale");
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "CONECTADO");
  assert.equal(history(db).length, 0);
});

test("conexión tardía no revive una sesión ya desconectada", async () => {
  const { db, processor } = setup();
  await processor.process(disconnected());
  assert.equal(await processor.process(connected()), "stale");
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "DESCONECTADO");
});

test("nuevo session disconnect sin connect observado es línea base", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  assert.equal(await processor.process(disconnected(station, NOW - 2000, NOW - 1000)), "baseline");
  assert.equal(history(db).length, 0);
});

test("secuencias de puerta: primera observación, apertura, repetición y cierre", async () => {
  const { db, processor } = setup();
  assert.equal(await processor.process(door()), "baseline");
  assert.equal(db.docs.get("hogares/home-a/estadoFisico/actual")?.puertaCambiadaEn, undefined);
  assert.equal(await processor.process(door(1, "PUERTA_ABIERTA", NOW - 9000)), "applied");
  assert.equal(await processor.process(door(2, "PUERTA_ABIERTA", NOW - 8000)), "unchanged");
  assert.equal(await processor.process(door(3, "PUERTA_CERRADA", NOW - 7000)), "applied");
  assert.deepEqual(history(db).map(([, d]) => d.tipo), ["PUERTA_ABIERTA", "PUERTA_CERRADA"]);
  const current = db.docs.get("hogares/home-a/estadoFisico/actual")!;
  assert.equal(current.alarma, undefined);
  assert.equal((current.puertaCambiadaEn as Timestamp).toMillis(), NOW - 7000);
  assert.ok((current.puertaCambiadaEn as Timestamp).toMillis() <= (current.actualizadoEn as Timestamp).toMillis());
});

test("puerta: duplicados, conflictos y secuencias fuera de orden", async () => {
  const { db, processor } = setup();
  const e = door(4, "PUERTA_ABIERTA");
  await processor.process(e);
  assert.equal(await processor.process({ ...e, receivedAt: NOW }), "duplicate");
  await assert.rejects(processor.process({ ...e, type: "PUERTA_CERRADA" }), /event_identity_conflict/);
  assert.equal(await processor.process(door(3, "PUERTA_CERRADA", NOW - 9000)), "stale");
  assert.equal(await processor.process(door(5, "PUERTA_CERRADA", NOW - 11000)), "stale");
  assert.equal(db.docs.get("hogares/home-a/estadoFisico/actual")?.puerta, "ABIERTA");
  assert.equal(history(db).length, 0);
});

test("reinicio crea línea base; boot retirado no reaparece aunque tenga fecha mayor", async () => {
  const { db, processor } = setup();
  await processor.process(door());
  assert.equal(await processor.process(door(0, "PUERTA_ABIERTA", NOW - 9000, "test-boot-0002")), "baseline");
  assert.equal(await processor.process(door(99, "PUERTA_CERRADA", NOW - 8000)), "stale");
  assert.equal(history(db).length, 0);
});

test("broker, identidad, destino activo y hogar son autoridades administrativas", async () => {
  const { db, processor } = setup();
  await assert.rejects(new EventProcessor(db, "otro-broker").process(connected()), /unknown_identity/);
  const path = `integracionesEmqx/${BROKER}/destinos/${destinationId("home-a", "estacion")}`;
  db.docs.delete(path);
  await assert.rejects(processor.process(connected()), /inactive_binding/);
  provision(db);
  db.docs.delete("hogares/home-a");
  await assert.rejects(processor.process(connected()), /unprovisioned_home/);
});

test("registro desactivado, sesión anterior al alta y puerta de portátil se rechazan", async () => {
  const { db, processor } = setup();
  await assert.rejects(processor.process({ ...door(), ...portable }), /unauthorized_door_source/);
  await assert.rejects(processor.process({ ...door(), topic: "otro/door" }), /unauthorized_door_source/);
  const path = `integracionesEmqx/${BROKER}/identidades/${identityId(BROKER, station)}`;
  const original = db.docs.get(path)!;
  db.docs.set(path, { ...original, habilitadoDesde: NOW });
  await assert.rejects(processor.process(connected()), /event_before_binding/);
  db.docs.set(path, { ...original, activo: false });
  await assert.rejects(processor.process(connected()), /unknown_identity/);
});

test("aislamiento entre hogares con identidades y recibos independientes", async () => {
  const { db, processor } = setup();
  const other = { username: "test-other", clientid: "test-other-station" };
  provision(db, other, "estacion", "home-b");
  await processor.process(connected()); await processor.process(connected(other));
  await processor.process(disconnected());
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "DESCONECTADO");
  assert.equal(db.docs.get("hogares/home-b/dispositivos/estacion")?.mqtt, "CONECTADO");
  assert.equal(history(db).every(([p]) => p.startsWith("hogares/home-a/")), true);
});

test("reintento automático de transacción no produce escrituras dobles", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  db.replayNext = true;
  assert.equal(await processor.process(disconnected()), "applied");
  assert.equal(history(db).length, 1);
});

test("solo conserva heartbeat compatible y alarma válida sin calcular valores", async () => {
  const { db, processor } = setup();
  db.docs.set("hogares/home-a/dispositivos/estacion", { ultimoHeartbeatEn: Timestamp.fromMillis(NOW - 20000) });
  await processor.process(connected());
  assert.ok(db.docs.get("hogares/home-a/dispositivos/estacion")?.ultimoHeartbeatEn instanceof Timestamp);
  db.docs.set("hogares/home-a/estadoFisico/actual", { alarma: "ADVERTENCIA" });
  await processor.process(door());
  assert.equal(db.docs.get("hogares/home-a/estadoFisico/actual")?.alarma, "ADVERTENCIA");
});

test("documentos públicos más recientes sin cursor no son pisados por primera entrega atrasada", async () => {
  const { db, processor } = setup();
  db.docs.set("hogares/home-a/dispositivos/estacion", {
    schemaVersion: 1, mqtt: "DESCONECTADO", actualizadoEn: Timestamp.fromMillis(NOW)
  });
  db.docs.set("hogares/home-a/estadoFisico/actual", {
    schemaVersion: 1, puerta: "ABIERTA", actualizadoEn: Timestamp.fromMillis(NOW)
  });
  assert.equal(await processor.process(connected()), "stale");
  assert.equal(await processor.process(door()), "stale");
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "DESCONECTADO");
  assert.equal(db.docs.get("hogares/home-a/estadoFisico/actual")?.puerta, "ABIERTA");
  assert.equal(history(db).length, 0);
});

test("caché de reintento no elude una revocación administrativa", async () => {
  const { db, processor } = setup();
  const e = connected(); await processor.process(e);
  const path = `integracionesEmqx/${BROKER}/identidades/${identityId(BROKER, station)}`;
  db.docs.set(path, { ...db.docs.get(path), activo: false });
  await assert.rejects(processor.process(e), /unknown_identity/);
});

test("puerta deshabilitada por administración no escribe aunque identidad sea válida", async () => {
  const { db, processor } = setup();
  const path = `integracionesEmqx/${BROKER}/identidades/${identityId(BROKER, station)}`;
  db.docs.set(path, { ...db.docs.get(path), puertaHabilitada: false });
  await assert.rejects(processor.process(door()), /unauthorized_door_source/);
  assert.equal(db.docs.has("hogares/home-a/estadoFisico/actual"), false);
});

test("revisión nueva inicia línea base y frontera de alta bloquea sesiones anteriores", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  const path = `integracionesEmqx/${BROKER}/identidades/${identityId(BROKER, station)}`;
  db.docs.set(path, { ...db.docs.get(path), revision: "revision-2", habilitadoDesde: NOW - 2000 });
  db.docs.set(`integracionesEmqx/${BROKER}/destinos/${destinationId("home-a", "estacion")}`, {
    identidadId: identityId(BROKER, station), revision: "revision-2"
  });
  await assert.rejects(processor.process(disconnected()), /event_before_binding/);
  assert.equal(await processor.process(disconnected(station, NOW - 1000, NOW)), "baseline");
  assert.equal(history(db).length, 0);
});

test("no conserva tipos ni timestamps inválidos de diagnósticos previos", async () => {
  const { db, processor } = setup();
  db.docs.set("hogares/home-a/dispositivos/estacion", { ultimoHeartbeatEn: Timestamp.fromMillis(-1) });
  db.docs.set("hogares/home-a/estadoFisico/actual", { alarma: ["NORMAL"] });
  await processor.process(connected()); await processor.process(door());
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.ultimoHeartbeatEn, undefined);
  assert.equal(db.docs.get("hogares/home-a/estadoFisico/actual")?.alarma, undefined);
});

test("fallo antes de commit en una transición no deja recibo ni historial parcial", async () => {
  const { db, processor } = setup();
  await processor.process(connected());
  db.failNext = true;
  await assert.rejects(processor.process(disconnected()));
  assert.equal(db.docs.get("hogares/home-a/dispositivos/estacion")?.mqtt, "CONECTADO");
  assert.equal(history(db).length, 0);
  assert.equal(await processor.process(disconnected()), "applied");
  assert.equal(history(db).length, 1);
});
