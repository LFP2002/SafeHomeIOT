/** Valor neutral: el adaptador REST lo codifica como timestampValue, nunca como map. */
export class Timestamp {
  constructor(readonly seconds: number, readonly nanoseconds: number) {
    if (!Number.isInteger(seconds) || seconds < -62135596800 || seconds > 253402300799 ||
        !Number.isInteger(nanoseconds) || nanoseconds < 0 || nanoseconds > 999999999)
      throw new Error("invalid_timestamp");
  }
  static fromMillis(millis: number): Timestamp {
    if (!Number.isFinite(millis)) throw new Error("invalid_timestamp");
    const seconds = Math.floor(millis / 1000);
    return new Timestamp(seconds, Math.floor((millis - seconds * 1000) * 1e6));
  }
  static fromRFC3339(value: string): Timestamp {
    const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value);
    if (!match) throw new Error("invalid_timestamp");
    const millis = Date.parse(`${match[1]}Z`);
    if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== match[1])
      throw new Error("invalid_timestamp");
    return new Timestamp(millis / 1000, Number((match[2] ?? "").padEnd(9, "0")));
  }
  toMillis(): number { return this.seconds * 1000 + this.nanoseconds / 1e6; }
  toRFC3339(): string {
    return new Date(this.seconds * 1000).toISOString().slice(0, 19) +
      (this.nanoseconds ? `.${String(this.nanoseconds).padStart(9, "0")}` : "") + "Z";
  }
}
