import { createHash, timingSafeEqual } from "node:crypto";
import { HttpError } from "./errors.js";
import { MAX_BODY_BYTES, parseEvent, type EmqxEvent } from "./events.js";
import type { Result } from "./processor.js";

export interface RequestInput {
  method: string;
  secret: string | undefined;
  contentType: string | undefined;
  contentEncoding?: string;
  rawBody: Buffer;
}
export interface Reply { status: number; code: string; headers?: Record<string, string> }
export interface HandlerDeps {
  secret: () => string;
  now: () => number;
  process: (event: EmqxEvent) => Promise<Result>;
  logFailure: () => void;
}

export function authentic(secret: string | undefined, expected: string): boolean {
  if (!secret || secret.length > 512 || expected.length < 32 || expected.length > 512) return false;
  const digest = (s: string) => createHash("sha256").update(s, "utf8").digest();
  return timingSafeEqual(digest(secret), digest(expected));
}

export async function handle(input: RequestInput, deps: HandlerDeps): Promise<Reply> {
  if (input.method !== "POST") return { status: 405, code: "method_not_allowed", headers: { Allow: "POST" } };
  try {
    const expected = deps.secret();
    if (expected.length < 32 || expected.length > 512) throw new HttpError(503, "integration_not_configured");
    if (!authentic(input.secret, expected)) return { status: 401, code: "unauthorized" };
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(input.contentType ?? ""))
      return { status: 415, code: "json_required" };
    if (input.contentEncoding && input.contentEncoding.toLowerCase() !== "identity")
      return { status: 415, code: "encoding_not_supported" };
    if (input.rawBody.byteLength > MAX_BODY_BYTES) return { status: 413, code: "payload_too_large" };
    let body: unknown;
    try { body = JSON.parse(input.rawBody.toString("utf8")); }
    catch { return { status: 400, code: "invalid_json" }; }
    const event = parseEvent(body, deps.now());
    const result = await deps.process(event);
    return { status: 200, code: result };
  } catch (error) {
    if (error instanceof HttpError && error.status < 500) return { status: error.status, code: error.code };
    // Nunca loguear request, cabeceras, body, secretos ni mensajes arbitrarios del SDK.
    deps.logFailure();
    return { status: 503, code: "temporarily_unavailable", headers: { "Retry-After": "10" } };
  }
}
