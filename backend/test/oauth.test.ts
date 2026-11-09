import { before, test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { GoogleOAuth } from "../src/oauth.js";
import type { Transport } from "../src/net.js";

let privateKey: string, publicKey: string;
before(() => {
  const keys = generateKeyPairSync("rsa", { modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs8", format: "pem" }, publicKeyEncoding: { type: "spki", format: "pem" } });
  privateKey = keys.privateKey; publicKey = keys.publicKey;
});
const email = "worker-test@demo-safehome-backend.iam.gserviceaccount.com";

test("Web Crypto firma JWT RS256 válido para Google con scope datastore", async () => {
  const now = Date.now(); let count = 0;
  const http: Transport = async (url, init) => {
    count++; assert.equal(url, "https://oauth2.googleapis.com/token"); assert.equal(init.redirect, "manual");
    const form = new URLSearchParams(String(init.body));
    assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
    const [header, payload, signature] = form.get("assertion")!.split(".");
    assert.equal(JSON.parse(Buffer.from(header!, "base64url").toString()).alg, "RS256");
    const claims = JSON.parse(Buffer.from(payload!, "base64url").toString());
    assert.equal(claims.iss, email); assert.equal(claims.aud, url);
    assert.equal(claims.scope, "https://www.googleapis.com/auth/datastore");
    assert.equal(claims.exp - claims.iat, 3600);
    assert.equal(verify("RSA-SHA256", Buffer.from(`${header}.${payload}`), publicKey, Buffer.from(signature!, "base64url")), true);
    return Response.json({ access_token: "test-only-token", token_type: "Bearer", expires_in: 3600 });
  };
  const auth = new GoogleOAuth({ email, privateKey }, http, () => now);
  assert.deepEqual(await Promise.all(Array.from({ length: 8 }, () => auth.token())), Array(8).fill("test-only-token"));
  assert.equal(count, 1);
});

test("renueva con margen, invalida 401 y no comparte caché entre claves", async () => {
  let now = Date.now(); let count = 0;
  const http: Transport = async () => Response.json({ access_token: `test-${++count}`, token_type: "Bearer", expires_in: 3600 });
  const auth = new GoogleOAuth({ email, privateKey }, http, () => now);
  assert.equal(await auth.token(), "test-1"); assert.equal(await auth.token(), "test-1");
  now += 3540001; assert.equal(await auth.token(), "test-2");
  auth.invalidate(); assert.equal(await auth.token(), "test-3");
  assert.equal(auth.matches({ email, privateKey: "other" }), false);
});

test("clave inválida y respuesta OAuth inválida nunca producen token", async () => {
  const http: Transport = async () => Response.json({ error: "DO_NOT_LOG_secret" }, { status: 400 });
  await assert.rejects(new GoogleOAuth({ email, privateKey: "invalid" }, http).token());
  await assert.rejects(new GoogleOAuth({ email, privateKey }, http).token(), /oauth_unavailable/);
  const invalid: Transport = async () => Response.json({ access_token: "fake", token_type: "Bearer", expires_in: 99999 });
  await assert.rejects(new GoogleOAuth({ email, privateKey }, invalid).token());
});

test("OAuth rechaza redirects sin transmitir assertion a otro host", async () => {
  let calls = 0;
  const http: Transport = async (_url, init) => {
    calls++; assert.equal(init.redirect, "manual");
    return new Response(null, { status: 302, headers: { Location: "https://untrusted.test" } });
  };
  await assert.rejects(new GoogleOAuth({ email, privateKey }, http).token(), /oauth_redirect_rejected/);
  assert.equal(calls, 1);
});
