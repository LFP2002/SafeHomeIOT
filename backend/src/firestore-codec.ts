import { Timestamp } from "./timestamp.js";
import { record } from "./net.js";
import type { Data } from "./storage.js";

export type Fields = Record<string, Record<string, unknown>>;

export function encodeValue(value: unknown): Record<string, unknown> {
  if (value instanceof Timestamp) return { timestampValue: value.toRFC3339() };
  if (value === null) return { nullValue: null };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number" && Number.isFinite(value)) {
    if (Number.isInteger(value)) {
      if (!Number.isSafeInteger(value)) throw new Error("unsafe_firestore_integer");
      return { integerValue: String(value) };
    }
    return { doubleValue: value };
  }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeValue) } };
  if (value && typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype)
    return { mapValue: { fields: encodeFields(value as Data) } };
  throw new Error("unsupported_firestore_value");
}

export function encodeFields(data: Data): Fields {
  return Object.fromEntries(Object.entries(data).map(([key, value]) => [key, encodeValue(value)]));
}

export function decodeValue(input: unknown): unknown {
  const v = record(input);
  if (Object.keys(v).length !== 1) throw new Error("invalid_firestore_value");
  if (typeof v.timestampValue === "string") return Timestamp.fromRFC3339(v.timestampValue);
  if (typeof v.stringValue === "string") return v.stringValue;
  if (typeof v.booleanValue === "boolean") return v.booleanValue;
  if ("nullValue" in v && v.nullValue === null) return null;
  if (typeof v.integerValue === "string" && /^-?\d+$/.test(v.integerValue)) {
    const n = Number(v.integerValue);
    if (!Number.isSafeInteger(n)) throw new Error("unsafe_firestore_integer");
    return n;
  }
  if (typeof v.doubleValue === "number" && Number.isFinite(v.doubleValue)) return v.doubleValue;
  if (v.mapValue) return decodeFields(record(v.mapValue).fields ?? {});
  if (v.arrayValue) {
    const values = record(v.arrayValue).values ?? [];
    if (!Array.isArray(values)) throw new Error("invalid_firestore_array");
    return values.map(decodeValue);
  }
  throw new Error("unsupported_firestore_value");
}

export function decodeFields(fields: unknown): Data {
  return Object.fromEntries(Object.entries(record(fields)).map(([key, value]) => [key, decodeValue(value)]));
}
