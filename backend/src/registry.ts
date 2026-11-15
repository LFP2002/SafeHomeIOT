import { z } from "zod";
import { HttpError } from "./errors.js";
import { hash, identityId, type EmqxEvent } from "./events.js";
import type { Transaction } from "./storage.js";

const bindingSchema = z.object({
  activo: z.literal(true), username: z.string(), clientid: z.string(),
  hogarId: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  dispositivo: z.enum(["estacion", "portatil"]),
  revision: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  habilitadoDesde: z.number().int().positive().max(253402300799999),
  puertaHabilitada: z.boolean(), topicPuerta: z.string().min(1).max(256).optional()
}).strict();
export type Binding = z.infer<typeof bindingSchema> & { identidadId: string };

export function destinationId(hogar: string, device: string): string { return hash([hogar, device]); }

export async function resolveBinding(tx: Transaction, broker: string, e: EmqxEvent): Promise<Binding> {
  const id = identityId(broker, e);
  const result = bindingSchema.safeParse(await tx.get(`integracionesEmqx/${broker}/identidades/${id}`));
  if (!result.success || result.data.username !== e.username || result.data.clientid !== e.clientid)
    throw new HttpError(403, "unknown_identity");
  const b = result.data;
  const target = await tx.get(`integracionesEmqx/${broker}/destinos/${destinationId(b.hogarId, b.dispositivo)}`);
  if (target?.identidadId !== id || target.revision !== b.revision)
    throw new HttpError(403, "inactive_binding");
  if (!await tx.get(`hogares/${b.hogarId}`)) throw new HttpError(403, "unprovisioned_home");
  // No permite reproducir una sesión anterior al alta/reasignación administrativa.
  if (e.occurredAt < b.habilitadoDesde ||
      (e.source !== "station.door" && e.connected_at < b.habilitadoDesde))
    throw new HttpError(403, "event_before_binding");
  if (e.source === "station.door" && (b.dispositivo !== "estacion" || !b.puertaHabilitada || e.topic !== b.topicPuerta))
    throw new HttpError(403, "unauthorized_door_source");
  return { ...b, identidadId: id };
}
