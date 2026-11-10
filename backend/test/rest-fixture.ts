import { encodeFields, decodeFields } from "../src/firestore-codec.js";
import { Timestamp } from "../src/timestamp.js";
import type { Data } from "../src/storage.js";
import type { Transport } from "../src/net.js";
import { MemoryStorage, provision, portable } from "./helpers.js";

interface Version { data: Data; version: number }
interface Tx { snapshot: Map<string, Version>; reads: Set<string> }
export const PROJECT = "demo-safehome-backend";
const PREFIX = `projects/${PROJECT}/databases/(default)/documents/`;

/** Servidor REST falso solo de tests; el emulador prueba además el comportamiento real. */
export class RestFixture {
  docs = new Map<string, Version>();
  transactions = new Map<string, Tx>();
  requests: { method: string; url: string; body: any; authorization: string | null }[] = [];
  commits: any[] = [];
  aborts = 0;
  dropCommitResponse = false;
  failure?: { operation: string; code: string; status: number };
  version = 1;
  nextTx = 0;
  constructor(now?: number) {
    const seed = new MemoryStorage(); provision(seed); provision(seed, portable, "portatil");
    for (const [p, data] of seed.docs) this.docs.set(p, { data: now && p.includes("/identidades/")
      ? { ...data, habilitadoDesde: now - 100000 } : data, version: this.version++ });
  }
  data(path: string): Data | undefined { return this.docs.get(path)?.data; }
  private updateTime(version: number) { return Timestamp.fromMillis(1_700_000_000_000 + version).toRFC3339(); }
  private error(code: string, status: number) { return Response.json({ error: { status: code } }, { status }); }
  readonly http: Transport = async (url, init) => {
    const request = new Request(url, init);
    const body: any = init.body ? JSON.parse(String(init.body)) : undefined;
    this.requests.push({ method: request.method, url, body, authorization: request.headers.get("authorization") });
    const parsed = new URL(url);
    const restPath = decodeURIComponent(parsed.pathname.replace(/^\/v1\//, ""));
    const operation = restPath.split(":")[1] ?? "get";
    if (this.failure?.operation === operation) return this.error(this.failure.code, this.failure.status);
    if (operation === "beginTransaction") {
      const id = btoa(`tx+/${++this.nextTx}`);
      this.transactions.set(id, { snapshot: new Map(this.docs), reads: new Set() });
      return Response.json({ transaction: id });
    }
    const id = body?.transaction ?? parsed.searchParams.get("transaction");
    const tx = this.transactions.get(id);
    if (!tx) return this.error("ABORTED", 409);
    if (operation === "rollback") { this.transactions.delete(id); return Response.json({}); }
    if (operation === "batchGet") {
      const path = body.documents[0].slice(PREFIX.length);
      tx.reads.add(path);
      const found = tx.snapshot.get(path);
      return Response.json([found ? { found: { name: PREFIX + path, fields: encodeFields(found.data), updateTime: this.updateTime(found.version) } }
        : { missing: PREFIX + path }]);
    }
    if (operation !== "commit") throw new Error("unknown_test_operation");
    this.commits.push(body);
    if (this.aborts > 0) { this.aborts--; return this.error("ABORTED", 409); }
    for (const path of tx.reads) {
      if (this.docs.get(path)?.version !== tx.snapshot.get(path)?.version) return this.error("ABORTED", 409);
    }
    for (const w of body.writes) {
      const path = w.update.name.slice(PREFIX.length);
      const existing = this.docs.get(path);
      if (w.currentDocument?.exists === false && existing) return this.error("ALREADY_EXISTS", 409);
      if (w.currentDocument?.updateTime && (!existing || this.updateTime(existing.version) !== w.currentDocument.updateTime))
        return this.error("FAILED_PRECONDITION", 400);
    }
    for (const w of body.writes) {
      const path = w.update.name.slice(PREFIX.length);
      this.docs.set(path, { data: decodeFields(w.update.fields), version: this.version++ });
    }
    this.transactions.delete(id);
    if (this.dropCommitResponse) { this.dropCommitResponse = false; throw new Error("test_network_failure_DO_NOT_LOG"); }
    return Response.json({ writeResults: body.writes.map(() => ({})), commitTime: this.updateTime(this.version) });
  };
}
