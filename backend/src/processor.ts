import { Timestamp } from "./timestamp.js";
import { z } from "zod";
import { HttpError } from "./errors.js";
import { eventId, eventType, fingerprint, hash, type EmqxEvent } from "./events.js";
import { resolveBinding } from "./registry.js";
import type { Data, Storage } from "./storage.js";

const mqttCursor = z.object({ connectedAt: z.number().int().positive(),
  occurredAt: z.number().int().positive(), status: z.enum(["CONECTADO", "DESCONECTADO"]) });
const doorCursor = z.object({ occurredAt: z.number().int().positive(), bootId: z.string(),
  sequence: z.number().int().nonnegative(), status: z.enum(["ABIERTA", "CERRADA"]) });
export type Result = "applied" | "baseline" | "duplicate" | "stale" | "unchanged";

export class EventProcessor {
  constructor(private readonly storage: Storage, private readonly broker: string) {}

  async process(e: EmqxEvent): Promise<Result> {
    return this.storage.transaction(async tx => {
      // Registro y destino se leen dentro de la transacción: revocación sin caché local.
      const b = await resolveBinding(tx, this.broker, e);
      const id = eventId(this.broker, b.revision, e);
      const digest = fingerprint(e);
      const receiptPath = `_emqxRecibos/${id}`;
      const receipt = await tx.get(receiptPath);
      if (receipt) {
        if (receipt.fingerprint !== digest) throw new HttpError(409, "event_identity_conflict");
        return "duplicate";
      }
      const base = `hogares/${b.hogarId}`;
      const scope = hash([this.broker, b.identidadId, b.revision]);
      const cursorPath = `${base}/_ingesta/${b.dispositivo}`;
      const current = await tx.get(cursorPath);
      // Cambio de asociación = nueva línea base, nunca transición ficticia.
      const cursor: Data = current?.scope === scope ? current : {};
      let result: Result;
      let changed = false;
      let update: Data | undefined;
      let publicPath: string;
      let next: Data;

      if (e.source === "station.door") {
        publicPath = `${base}/estadoFisico/actual`;
        const publicState = await tx.get(publicPath);
        const parsed = doorCursor.safeParse(cursor.door);
        if (cursor.door !== undefined && !parsed.success) throw new HttpError(503, "invalid_private_cursor");
        const old = parsed.success ? parsed.data : undefined;
        // Los boots vistos no se eliminan: un boot retirado no puede reaparecer.
        const bootPath = `_emqxBoots/${hash([scope, e.bootId])}`;
        const knownBoot = await tx.get(bootPath);
        const status = e.type === "PUERTA_ABIERTA" ? "ABIERTA" : "CERRADA";
        const publicTime = publicState?.actualizadoEn instanceof Timestamp ? publicState.actualizadoEn.toMillis() : 0;
        const stale = e.occurredAt < publicTime || (old && (e.occurredAt < old.occurredAt ||
          (e.bootId === old.bootId && e.sequence <= old.sequence) ||
          (e.bootId !== old.bootId && (knownBoot !== undefined || e.occurredAt <= old.occurredAt))));
        if (stale) {
          result = "stale";
          next = cursor;
        } else {
          const sameBoot = old?.bootId === e.bootId;
          changed = !!old && sameBoot && old.status !== status;
          result = !old || !sameBoot ? "baseline" : changed ? "applied" : "unchanged";
          next = { ...cursor, scope, door: { occurredAt: e.occurredAt, bootId: e.bootId, sequence: e.sequence, status } };
          update = { schemaVersion: 1, puerta: status, actualizadoEn: Timestamp.fromMillis(e.occurredAt) };
          // No fabrica una fecha de cambio para una primera observación o nuevo boot.
          if (changed) update.puertaCambiadaEn = Timestamp.fromMillis(e.occurredAt);
          else if (sameBoot && publicState?.puertaCambiadaEn instanceof Timestamp &&
              publicState.puertaCambiadaEn.toMillis() >= 0 && publicState.puertaCambiadaEn.toMillis() <= e.occurredAt)
            update.puertaCambiadaEn = publicState.puertaCambiadaEn;
          // Esta versión no calcula ni ingiere alarmas. Conserva solo valores válidos existentes.
          if (typeof publicState?.alarma === "string" && ["NORMAL", "ADVERTENCIA", "CRITICA", "SIN_DATOS"].includes(publicState.alarma))
            update.alarma = publicState?.alarma;
          if (!knownBoot) tx.create(bootPath, { scope, firstObservedAt: Timestamp.fromMillis(e.occurredAt) });
        }
      } else {
        publicPath = `${base}/dispositivos/${b.dispositivo}`;
        const publicState = await tx.get(publicPath);
        const parsed = mqttCursor.safeParse(cursor.mqtt);
        if (cursor.mqtt !== undefined && !parsed.success) throw new HttpError(503, "invalid_private_cursor");
        const old = parsed.success ? parsed.data : undefined;
        const status = e.source === "broker.connected" ? "CONECTADO" : "DESCONECTADO";
        const sameSession = old?.connectedAt === e.connected_at;
        const publicTime = publicState?.actualizadoEn instanceof Timestamp ? publicState.actualizadoEn.toMillis() : 0;
        const stale = (!old && e.occurredAt < publicTime) || (old && (e.connected_at < old.connectedAt ||
          (sameSession && (e.occurredAt < old.occurredAt || old.status === "DESCONECTADO"))));
        if (stale) {
          result = "stale";
          next = cursor;
        } else {
          changed = !!old && old.status !== status && (status === "CONECTADO" || sameSession);
          result = !old || (!sameSession && status === "DESCONECTADO") ? "baseline" : changed ? "applied" : "unchanged";
          next = { ...cursor, scope, mqtt: { connectedAt: e.connected_at, occurredAt: e.occurredAt, status } };
          const previousTime = publicState?.actualizadoEn instanceof Timestamp ? publicState.actualizadoEn.toMillis() : 0;
          const observed = Math.max(e.occurredAt, previousTime);
          update = { schemaVersion: 1, mqtt: status, actualizadoEn: Timestamp.fromMillis(observed) };
          const heartbeat = publicState?.ultimoHeartbeatEn;
          if (heartbeat instanceof Timestamp && heartbeat.toMillis() >= 0 && heartbeat.toMillis() <= observed)
            update.ultimoHeartbeatEn = heartbeat;
        }
      }

      // Todas las lecturas ya finalizaron. Recibo + cursor + estado + historial son atómicos.
      tx.create(receiptPath, { fingerprint: digest, result, hogarId: b.hogarId,
        ocurridoEn: Timestamp.fromMillis(e.occurredAt) });
      if (update) {
        tx.set(publicPath, update);
        tx.set(cursorPath, next);
      }
      if (changed) tx.create(`${base}/historial/${id}`, {
        schemaVersion: 1, tipo: eventType(e, b.dispositivo), dispositivo: b.dispositivo,
        creadoEn: Timestamp.fromMillis(e.occurredAt)
      });
      return result;
    });
  }
}
