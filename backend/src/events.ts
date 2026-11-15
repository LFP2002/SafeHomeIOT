import { createHash } from "node:crypto";
import { z } from "zod";
import { HttpError } from "./errors.js";

// Valores de política de entrega, no timeouts de presencia ni del estado MQTT.
export const MAX_BODY_BYTES = 8192;
export const MAX_EVENT_AGE_MS = 7 * 24 * 60 * 60 * 1000;
export const MAX_CLOCK_SKEW_MS = 60_000;
const MAX_DATE = 253402300799999;
const identity = z.string().min(1).max(128).regex(/^[A-Za-z0-9_.:@-]+$/);
const millis = z.number().int().min(1).max(MAX_DATE);
const common = { clientid: identity, username: identity, occurredAt: millis };
const connected = z.object({ ...common, source: z.literal("broker.connected"), connected_at: millis }).strict();
const disconnected = z.object({ ...common, source: z.literal("broker.disconnected"),
  connected_at: millis, disconnected_at: millis }).strict();
const door = z.object({ ...common, source: z.literal("station.door"),
  type: z.enum(["PUERTA_ABIERTA", "PUERTA_CERRADA"]),
  topic: z.string().min(1).max(256).regex(/^[A-Za-z0-9_./:-]+$/),
  receivedAt: millis, retained: z.literal(false),
  bootId: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/),
  sequence: z.number().int().min(0).max(0xffffffff)
}).strict();
const schema = z.discriminatedUnion("source", [connected, disconnected, door]);
export type EmqxEvent = z.infer<typeof schema>;
export type Device = "estacion" | "portatil";

export function parseEvent(body: unknown, now: number): EmqxEvent {
  const parsed = schema.safeParse(body);
  if (!parsed.success) throw new HttpError(422, "invalid_event");
  const e = parsed.data;
  if (e.occurredAt < now - MAX_EVENT_AGE_MS || e.occurredAt > now + MAX_CLOCK_SKEW_MS)
    throw new HttpError(422, "event_date_out_of_range");
  if (e.source === "broker.connected" && e.occurredAt !== e.connected_at)
    throw new HttpError(422, "inconsistent_session_date");
  if (e.source === "broker.disconnected" &&
      (e.occurredAt !== e.disconnected_at || e.disconnected_at < e.connected_at))
    throw new HttpError(422, "inconsistent_session_date");
  if (e.source === "station.door" && (e.receivedAt < e.occurredAt - MAX_CLOCK_SKEW_MS ||
      e.receivedAt > now + MAX_CLOCK_SKEW_MS || e.receivedAt < now - MAX_EVENT_AGE_MS))
    throw new HttpError(422, "inconsistent_broker_date");
  return e;
}

export function hash(parts: readonly unknown[]): string {
  return createHash("sha256").update(JSON.stringify(parts)).digest("hex");
}

export function identityId(broker: string, e: Pick<EmqxEvent, "username" | "clientid">): string {
  return hash([broker, e.username, e.clientid]);
}

export function eventId(broker: string, revision: string, e: EmqxEvent): string {
  const origin = e.source === "station.door" ? [e.bootId, e.sequence] : [e.connected_at];
  return hash([broker, e.username, e.clientid, revision, e.source, ...origin]);
}

// Una identidad de origen no puede reutilizarse con otro contenido en un reintento.
export function fingerprint(e: EmqxEvent): string {
  return e.source === "station.door"
    ? hash([e.source, e.type, e.occurredAt, e.bootId, e.sequence, e.topic])
    : hash([e.source, e.connected_at, e.occurredAt]);
}

export function eventType(e: EmqxEvent, device: Device): string {
  if (e.source === "station.door") return e.type;
  if (device === "estacion") return e.source === "broker.connected" ? "ESTACION_CONECTADA" : "ESTACION_DESCONECTADA";
  return e.source === "broker.connected" ? "PORTATIL_CONECTADO" : "PORTATIL_DESCONECTADO";
}
