import { HttpError } from "./errors.js";

export type Transport = (url: string, init: RequestInit) => Promise<Response>;
export const transport: Transport = (url, init) => fetch(url, init);

/** Limita el stream antes de concatenar/parsear, incluso sin Content-Length. */
export async function readBytes(body: ReadableStream<Uint8Array> | null, limit: number, status = 503): Promise<Uint8Array> {
  if (!body) return new Uint8Array();
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.byteLength;
      if (size > limit) {
        await reader.cancel();
        throw new HttpError(status, status === 413 ? "payload_too_large" : "upstream_response_too_large");
      }
      chunks.push(part.value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return bytes;
}

export async function readJSON(response: Response, limit: number): Promise<unknown> {
  const bytes = await readBytes(response.body, limit);
  return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
}

export function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HttpError(503, "invalid_upstream_response");
  return value as Record<string, unknown>;
}
