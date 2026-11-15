import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { Miniflare, Request as MFRequest, Response as MFResponse, Log, LogLevel } from "miniflare";
import { BROKER, SECRET } from "../helpers.js";
import { RestFixture, PROJECT } from "../rest-fixture.js";
import type { Env } from "../../src/index.js";
import type { Transport } from "../../src/net.js";

/** Todo el tráfico saliente está interceptado; la clave se genera solo en memoria. */
export async function runtime(enabled = true, inspector = false, firestore?: Transport) {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048,
    privateKeyEncoding: { format: "pem", type: "pkcs8" }, publicKeyEncoding: { format: "pem", type: "spki" } });
  const now = Date.now();
  const fixture = new RestFixture(now);
  let oauthCalls = 0;
  const env: Env = { EMQX_WEBHOOK_SECRET: SECRET, EMQX_BROKER_ID: BROKER,
    GOOGLE_PROJECT_ID: PROJECT, GOOGLE_SERVICE_ACCOUNT_EMAIL: `test-worker@${PROJECT}.iam.gserviceaccount.com`,
    GOOGLE_PRIVATE_KEY: privateKey, INGESTION_ENABLED: String(enabled) };
  const mf = new Miniflare({ name: "safehome-test", modules: true, scriptPath: "dist/worker.mjs",
    compatibilityDate: "2026-07-30", compatibilityFlags: ["nodejs_compat"],
    ...(inspector ? { inspectorPort: 0 } : {}),
    log: new Log(LogLevel.NONE), bindings: { ...env },
    outboundService: async (request: MFRequest) => {
      const url = new URL(request.url);
      let response: Response;
      if (url.origin === "https://oauth2.googleapis.com" && url.pathname === "/token") {
        oauthCalls++;
        const form = new URLSearchParams(await request.text());
        const [header, payload, signature] = form.get("assertion")!.split(".");
        const claims = JSON.parse(Buffer.from(payload!, "base64url").toString());
        assert.equal(claims.iss, env.GOOGLE_SERVICE_ACCOUNT_EMAIL);
        assert.equal(claims.aud, request.url);
        assert.equal(claims.scope, "https://www.googleapis.com/auth/datastore");
        assert.equal(verify("RSA-SHA256", Buffer.from(`${header}.${payload}`), publicKey, Buffer.from(signature!, "base64url")), true);
        response = Response.json({ access_token: "only-workerd-test-token", token_type: "Bearer", expires_in: 3600 });
      } else if (url.origin === "https://firestore.googleapis.com") {
        response = await (firestore ?? fixture.http)(request.url, { method: request.method,
          headers: Object.fromEntries(request.headers),
          ...(request.method === "GET" ? {} : { body: await request.text() }) });
      } else throw new Error("External network forbidden in Workers tests");
      return new MFResponse(await response.text(), { status: response.status,
        headers: Object.fromEntries(response.headers) });
    }
  });
  await mf.ready;
  const send = (event: unknown, secret = SECRET) => mf.dispatchFetch("https://worker.test/emqx", {
    method: "POST", headers: { "content-type": "application/json", "x-safehome-integration-secret": secret },
    body: JSON.stringify(event)
  });
  return { mf, fixture, now, send, oauthCalls: () => oauthCalls };
}
