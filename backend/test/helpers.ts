import { identityId } from "../src/events.js";
import { destinationId } from "../src/registry.js";
import type { Data, Storage, Transaction } from "../src/storage.js";

export const NOW = 1_800_000_000_000;
export const BROKER = "test-broker";
export const SECRET = "only-tests-not-a-real-secret-00000000000000";
export const station = { clientid: "test-station", username: "test-user-station" };
export const portable = { clientid: "test-portable", username: "test-user-portable" };
export const connected = (who = station, at = NOW - 10_000) => ({
  source: "broker.connected" as const, ...who, connected_at: at, occurredAt: at
});
export const disconnected = (who = station, start = NOW - 10_000, at = NOW - 5_000) => ({
  source: "broker.disconnected" as const, ...who, connected_at: start, disconnected_at: at, occurredAt: at
});
export const door = (sequence = 0, type: "PUERTA_ABIERTA" | "PUERTA_CERRADA" = "PUERTA_CERRADA",
  at = NOW - 10_000, bootId = "test-boot-0001") => ({
  source: "station.door" as const, ...station, type, occurredAt: at,
  receivedAt: at, topic: "test/station/door", retained: false as const, bootId, sequence
});

/** Solo para tests. Commit atómico y ejecución serial para simular contención. */
export class MemoryStorage implements Storage {
  docs = new Map<string, Data>();
  failNext = false;
  replayNext = false;
  private tail: Promise<unknown> = Promise.resolve();
  transaction<T>(work: (tx: Transaction) => Promise<T>): Promise<T> {
    const run = this.tail.then(async () => {
      const attempt = async () => {
        const pending = new Map(this.docs);
        let writing = false;
        const result = await work({
          get: async path => {
            if (writing) throw new Error("transaction_read_after_write");
            return pending.get(path);
          },
          set: (path, data, merge = false) => {
            writing = true;
            pending.set(path, merge ? { ...pending.get(path), ...data } : { ...data });
          },
          create: (path, data) => {
            writing = true;
            if (pending.has(path)) throw new Error("already_exists");
            pending.set(path, { ...data });
          }
        });
        return { pending, result };
      };
      if (this.replayNext) { this.replayNext = false; await attempt(); }
      const { pending, result } = await attempt();
      if (this.failNext) { this.failNext = false; throw new Error("fake SDK token=DO_NOT_LOG"); }
      this.docs = pending;
      return result;
    });
    this.tail = run.catch(() => undefined);
    return run;
  }
}

export function provision(db: MemoryStorage, who = station, device = "estacion", home = "home-a") {
  const id = identityId(BROKER, who);
  db.docs.set(`hogares/${home}`, { nombre: "Solo test" });
  db.docs.set(`integracionesEmqx/${BROKER}/identidades/${id}`, {
    activo: true, ...who, hogarId: home, dispositivo: device, revision: "revision-1",
    habilitadoDesde: NOW - 100_000, puertaHabilitada: device === "estacion", topicPuerta: "test/station/door"
  });
  db.docs.set(`integracionesEmqx/${BROKER}/destinos/${destinationId(home, device)}`, {
    identidadId: id, revision: "revision-1"
  });
}
