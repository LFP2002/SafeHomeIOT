import { mkdir, writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { runtime } from "./harness.js";
import { connected, door, station } from "../helpers.js";

/** CDP del isolate workerd, NO profiler de Node. No equivale a la cuota CPU de Cloudflare. */
const s = await runtime(true, true);
let socket: WebSocket | undefined;
try {
  const inspector = await s.mf.getInspectorURL();
  inspector.protocol = "http:"; inspector.pathname = "/json/list";
  const targets = await (await fetch(inspector)).json() as { id: string; title: string; webSocketDebuggerUrl: string }[];
  const target = targets.find(t => `${t.id} ${t.title}`.includes("safehome-test"));
  if (!target) throw new Error(`No está el isolate a medir: ${targets.map(t => t.id).join(",")}`);
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("inspector_timeout")), 10000);
    socket!.addEventListener("open", () => { clearTimeout(timeout); resolve(); }, { once: true });
    socket!.addEventListener("error", () => { clearTimeout(timeout); reject(new Error("inspector_connection_failed")); }, { once: true });
  });
  let sequence = 0;
  const pending = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void }>();
  socket.addEventListener("message", event => {
    const data = JSON.parse(String(event.data));
    const p = pending.get(data.id);
    if (!p) return;
    pending.delete(data.id);
    if (data.error) p.reject(new Error("inspector_command_failed")); else p.resolve(data.result);
  });
  const call = (method: string, params: unknown = {}): Promise<any> => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`inspector_timeout:${method}`)); }, 15000);
    pending.set(id, { resolve: value => { clearTimeout(timeout); resolve(value); },
      reject: error => { clearTimeout(timeout); reject(error); } });
    socket!.send(JSON.stringify({ id, method, params }));
  });
  await call("Profiler.enable");
  await call("Profiler.setSamplingInterval", { interval: 100 });
  const results: Record<string, unknown>[] = [];
  const measure = async (name: string, count: number, action: (i: number) => Promise<void>) => {
    const before = s.fixture.requests.length + s.oauthCalls();
    let maxSubrequests = 0;
    await call("Profiler.start");
    const start = performance.now();
    for (let i = 0; i < count; i++) {
      const last = s.fixture.requests.length + s.oauthCalls();
      await action(i);
      maxSubrequests = Math.max(maxSubrequests, s.fixture.requests.length + s.oauthCalls() - last);
    }
    const wallMs = performance.now() - start;
    const { profile } = await call("Profiler.stop");
    const nodes = new Map<number, string>(profile.nodes.map((n: any) => [n.id, n.callFrame.functionName]));
    if (!profile.samples?.length || !profile.timeDeltas?.length) throw new Error("missing_cpu_samples");
    const activeMicros = profile.samples.reduce((total: number, id: number, i: number) =>
      total + (nodes.get(id) === "(idle)" ? 0 : profile.timeDeltas[i]), 0);
    const spans = new Map<string, number>();
    for (let i = 0; i < profile.samples.length; i++) {
      const frame = nodes.get(profile.samples[i]) ?? "unknown";
      spans.set(frame, (spans.get(frame) ?? 0) + profile.timeDeltas[i]);
    }
    results.push({ name, invocations: count, nonIdleSampleSpanMs: activeMicros / 1000,
      nonIdleSampleSpanMsPerInvocation: activeMicros / 1000 / count, wallMs,
      largestSampleGapMs: Math.max(...profile.timeDeltas) / 1000,
      topSampleSpans: [...spans].sort((a, b) => b[1] - a[1]).slice(0, 8)
        .map(([frame, micros]) => ({ frame, spanMs: micros / 1000 })),
      subrequestsTotal: s.fixture.requests.length + s.oauthCalls() - before, maxSubrequests });
  };
  await measure("cold_mqtt_with_key_import_and_oauth", 1, async () => {
    assert.equal((await s.send(connected(station, s.now - 10000))).status, 200);
  });
  await measure("warm_mqtt", 20, async i => {
    assert.equal((await s.send(connected(station, s.now - 5000 + i))).status, 200);
  });
  await measure("duplicate_mqtt", 20, async () => {
    assert.equal((await s.send(connected(station, s.now - 4981))).status, 200);
  });
  await s.send(door(0, "PUERTA_CERRADA", s.now - 1000));
  await measure("warm_door_transitions", 20, async i => {
    assert.equal((await s.send(door(i + 1, i % 2 ? "PUERTA_CERRADA" : "PUERTA_ABIERTA", s.now - 999 + i))).status, 200);
  });
  s.fixture.aborts = 4;
  await measure("door_four_aborted_transactions", 1, async () => {
    assert.equal((await s.send(door(0, "PUERTA_ABIERTA", s.now, "profile-boot-two"))).status, 503);
  });
  const heap = await call("Runtime.getHeapUsage");
  const report = { generatedAt: new Date().toISOString(), runtime: "workerd via Miniflare 4.20260730.0",
    hostNode: process.version, samplingIntervalMicroseconds: 100,
    warning: "Spans entre muestras, no CPU facturable. Puede incluir espera nativa/backoff y overhead de inspector. No demuestra 10 ms Free.",
    results, heap: { usedBytes: heap.usedSize, totalBytes: heap.totalSize,
      warning: "Heap JS del isolate, no toda la memoria nativa de 128 MB." } };
  await mkdir("dist", { recursive: true });
  await writeFile("dist/profile-local.json", JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report, null, 2));
} finally { socket?.close(); await s.mf.dispose(); }
