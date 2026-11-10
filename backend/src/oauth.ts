import { HttpError } from "./errors.js";
import { readJSON, record, transport, type Transport } from "./net.js";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/datastore";
export interface Credentials { email: string; privateKey: string }
export interface TokenProvider { token(): Promise<string>; invalidate(): void }

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
const jsonPart = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));

/** JWT bearer grant Google, RS256 Web Crypto nativo. Ningún token se escribe en Firestore. */
export class GoogleOAuth implements TokenProvider {
  #credentials: Credentials;
  #key?: Promise<CryptoKey>;
  #refresh?: Promise<string>;
  #cached?: { value: string; expires: number };
  constructor(credentials: Credentials, private readonly http: Transport = transport,
    private readonly now: () => number = Date.now) {
    if (!/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$/.test(credentials.email) ||
        credentials.privateKey.length > 4900) throw new HttpError(503, "invalid_oauth_configuration");
    this.#credentials = credentials;
  }
  matches(c: Credentials): boolean {
    return this.#credentials.email === c.email && this.#credentials.privateKey === c.privateKey;
  }
  invalidate(): void { this.#cached = undefined; }
  async token(): Promise<string> {
    if (this.#cached && this.now() < this.#cached.expires) return this.#cached.value;
    if (this.#refresh) return this.#refresh;
    this.#refresh = this.issue();
    try { return await this.#refresh; } finally { this.#refresh = undefined; }
  }
  private importKey(): Promise<CryptoKey> {
    if (!this.#key) this.#key = (async () => {
      const pem = this.#credentials.privateKey.replace(/\\n/g, "\n").trim();
      const match = /^-----BEGIN PRIVATE KEY-----\s+([A-Za-z0-9+/=\s]+)\s+-----END PRIVATE KEY-----$/.exec(pem);
      if (!match) throw new HttpError(503, "invalid_oauth_key");
      const binary = atob(match[1]!.replace(/\s/g, ""));
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
      const key = await crypto.subtle.importKey("pkcs8", bytes,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
      // Google proporciona normalmente RSA 2048. No aceptar claves débiles ni RSA JS.
      const bits = (key.algorithm as { modulusLength?: number }).modulusLength;
      if (typeof bits !== "number" || bits < 2048 || bits > 4096) throw new HttpError(503, "invalid_oauth_key_size");
      return key;
    })().catch(error => { this.#key = undefined; throw error; });
    return this.#key;
  }
  private async issue(): Promise<string> {
    const start = this.now();
    const iat = Math.floor(start / 1000);
    const signingInput = `${jsonPart({ alg: "RS256", typ: "JWT" })}.${jsonPart({
      iss: this.#credentials.email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600
    })}`;
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", await this.importKey(),
      new TextEncoder().encode(signingInput));
    const response = await this.http(TOKEN_URL, {
      method: "POST", redirect: "manual", signal: AbortSignal.timeout(8000),
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${signingInput}.${base64url(new Uint8Array(signature))}` }).toString()
    });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new HttpError(503, "oauth_redirect_rejected");
    }
    const data = record(await readJSON(response, 16_384));
    if (!response.ok || data.token_type !== "Bearer" || typeof data.access_token !== "string" ||
        data.access_token.length < 1 || data.access_token.length > 8192 ||
        !Number.isInteger(data.expires_in) || Number(data.expires_in) < 120 || Number(data.expires_in) > 3600)
      throw new HttpError(503, "oauth_unavailable");
    this.#cached = { value: data.access_token, expires: start + Number(data.expires_in) * 1000 - 60_000 };
    return data.access_token;
  }
}
