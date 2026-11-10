import { HttpError } from "./errors.js";
import { decodeFields, encodeFields } from "./firestore-codec.js";
import { readJSON, record, transport, type Transport } from "./net.js";
import type { TokenProvider } from "./oauth.js";
import type { Data, Storage, Transaction } from "./storage.js";

class RestError extends Error {
  constructor(readonly status: string) { super("firestore_rest_failure"); }
}
interface Snapshot { data?: Data; updateTime?: string }
interface Budget { calls: number; deadline: number }
export interface RestOptions {
  project: string;
  auth: TokenProvider;
  http?: Transport;
  /** Exclusivo tests: el entrypoint Workers nunca expone este parámetro. */
  emulatorOrigin?: "http://127.0.0.1:8085";
  sleep?: (ms: number) => Promise<void>;
}

export class FirestoreRest implements Storage {
  private readonly root: string;
  private readonly endpoint: string;
  private readonly http: Transport;
  private readonly sleep: (ms: number) => Promise<void>;
  constructor(private readonly options: RestOptions) {
    if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(options.project) ||
        (options.emulatorOrigin && (!options.project.startsWith("demo-") || options.emulatorOrigin !== "http://127.0.0.1:8085")))
      throw new HttpError(503, "invalid_firestore_configuration");
    this.root = `projects/${options.project}/databases/(default)/documents`;
    this.endpoint = `${options.emulatorOrigin ?? "https://firestore.googleapis.com"}/v1/${this.root}`;
    this.http = options.http ?? transport;
    this.sleep = options.sleep ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  }
  private document(path: string): string {
    const parts = path.split("/");
    if (parts.length % 2 !== 0 || parts.some(s => !/^[A-Za-z0-9_-]{1,128}$/.test(s)))
      throw new Error("invalid_document_path");
    return `${this.root}/${path}`;
  }
  private async call(budget: Budget, suffix: string, method: string, body?: unknown): Promise<unknown> {
    // Hasta 4 intentos: 9 llamadas + rollback por intento de puerta, <= 40 + OAuth.
    if (++budget.calls > 40 || Date.now() >= budget.deadline) throw new HttpError(503, "transaction_budget_exhausted");
    const token = await this.options.auth.token();
    const remaining = budget.deadline - Date.now();
    if (remaining <= 0) throw new HttpError(503, "transaction_deadline_exceeded");
    const response = await this.http(this.endpoint + suffix, {
      method, redirect: "manual", signal: AbortSignal.timeout(Math.min(8000, remaining)),
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new HttpError(503, "firestore_redirect_rejected");
    }
    const result = await readJSON(response, 65_536);
    if (response.ok) return result;
    if (response.status === 401) this.options.auth.invalidate();
    const code = record(record(result).error).status;
    throw new RestError(typeof code === "string" ? code : "UNKNOWN");
  }
  private async rollback(budget: Budget, id: string): Promise<void> {
    try { await this.call(budget, ":rollback", "POST", { transaction: id }); }
    catch { /* Solo limpieza: nunca convierte un fallo de commit en éxito. */ }
  }
  async transaction<T>(work: (tx: Transaction) => Promise<T>): Promise<T> {
    const budget: Budget = { calls: 0, deadline: Date.now() + 20_000 };
    for (let attempt = 0; attempt < 4; attempt++) {
      let id: string | undefined;
      try {
        const begin = record(await this.call(budget, ":beginTransaction", "POST", { options: { readWrite: {} } }));
        if (typeof begin.transaction !== "string" || !begin.transaction) throw new Error("missing_transaction_id");
        id = begin.transaction;
        const snapshots = new Map<string, Snapshot>();
        const writes: Record<string, unknown>[] = [];
        const written = new Set<string>();
        const write = (path: string, data: Data, create: boolean, merge = false) => {
          const name = this.document(path);
          if (written.has(path)) throw new Error("duplicate_transaction_write");
          const old = snapshots.get(path);
          if (!create && !snapshots.has(path)) throw new Error("set_requires_transaction_read");
          const precondition = create || !old?.updateTime ? { exists: false } : { updateTime: old.updateTime };
          writes.push({ update: { name, fields: encodeFields(merge ? { ...old?.data, ...data } : data) },
            currentDocument: precondition });
          written.add(path);
        };
        const result = await work({
          get: async path => {
            if (writes.length) throw new Error("transaction_read_after_write");
            if (snapshots.has(path)) return snapshots.get(path)?.data;
            const name = this.document(path);
            const value = await this.call(budget, ":batchGet", "POST", { documents: [name], transaction: id });
            if (!Array.isArray(value) || value.length !== 1) throw new Error("invalid_firestore_read_response");
            const row = record(value[0]);
            if (row.missing === name && row.found === undefined) { snapshots.set(path, {}); return undefined; }
            const doc = record(row.found);
            if (doc.name !== this.document(path) || typeof doc.updateTime !== "string") throw new Error("invalid_firestore_document");
            const data = decodeFields(doc.fields ?? {});
            snapshots.set(path, { data, updateTime: doc.updateTime });
            return data;
          },
          set: (path, data, merge = false) => write(path, data, false, merge),
          create: (path, data) => write(path, data, true)
        });
        if (writes.length) await this.call(budget, ":commit", "POST", { transaction: id, writes });
        else await this.rollback(budget, id);
        return result;
      } catch (error) {
        if (id) await this.rollback(budget, id);
        // ABORTED: repetir TODO, incluidas autoridad y recibos. No reintentar un commit ambiguo.
        if (error instanceof RestError && error.status === "ABORTED" && attempt < 3) {
          await this.sleep(20 * 2 ** attempt + Math.floor(Math.random() * 20));
          continue;
        }
        throw error;
      }
    }
    throw new HttpError(503, "transaction_retries_exhausted");
  }
}
