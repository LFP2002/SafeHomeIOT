import { Buffer } from "node:buffer";
import { authentic, handle, type RequestInput, type Reply } from "./http.js";
import { EventProcessor } from "./processor.js";
import { FirestoreRest } from "./firestore-rest.js";
import { GoogleOAuth } from "./oauth.js";
import { HttpError } from "./errors.js";
import { MAX_BODY_BYTES } from "./events.js";
import { readBytes } from "./net.js";

export interface Env {
  EMQX_WEBHOOK_SECRET: string;
  EMQX_BROKER_ID: string;
  GOOGLE_PROJECT_ID: string;
  GOOGLE_SERVICE_ACCOUNT_EMAIL: string;
  GOOGLE_PRIVATE_KEY: string;
  INGESTION_ENABLED: string;
}
let oauth: GoogleOAuth | undefined;
function reply(r: Reply): Response {
  return Response.json({ code: r.code }, { status: r.status,
    headers: { "Cache-Control": "no-store", ...r.headers } });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (new URL(request.url).pathname !== "/emqx") return reply({ status: 404, code: "not_found" });
    const expected = env.EMQX_WEBHOOK_SECRET ?? "";
    const input: RequestInput = { method: request.method,
      secret: request.headers.get("x-safehome-integration-secret") ?? undefined,
      contentType: request.headers.get("content-type") ?? undefined,
      contentEncoding: request.headers.get("content-encoding") ?? undefined, rawBody: Buffer.alloc(0) };
    const deps = {
      secret: () => expected, now: Date.now,
      process: async (event: Parameters<EventProcessor["process"]>[0]) => {
        if (env.INGESTION_ENABLED !== "true") throw new HttpError(503, "ingestion_disabled");
        if (!/^[A-Za-z0-9_-]{1,64}$/.test(env.EMQX_BROKER_ID ?? "")) throw new HttpError(503, "integration_not_configured");
        const credentials = { email: env.GOOGLE_SERVICE_ACCOUNT_EMAIL ?? "", privateKey: env.GOOGLE_PRIVATE_KEY ?? "" };
        if (!oauth?.matches(credentials)) oauth = new GoogleOAuth(credentials);
        const storage = new FirestoreRest({ project: env.GOOGLE_PROJECT_ID ?? "", auth: oauth });
        return new EventProcessor(storage, env.EMQX_BROKER_ID).process(event);
      },
      logFailure: () => { console.error("emqx_ingestion_failed"); }
    };
    // No consumir un body no autenticado. La política HTTP existente sigue siendo autoridad.
    if (request.method !== "POST" || !authentic(input.secret, expected)) return reply(await handle(input, deps));
    try {
      input.rawBody = Buffer.from(await readBytes(request.body, MAX_BODY_BYTES, 413));
    } catch (error) {
      if (error instanceof HttpError && error.status === 413) return reply({ status: 413, code: error.code });
      return reply({ status: 400, code: "invalid_request_body" });
    }
    return reply(await handle(input, deps));
  }
};
