// Simulated Android companion: connects to /phone/connect and answers with fake data.
// Usage: node test/sim-phone.mjs <baseUrl> <deviceToken> [--slow]
import WebSocket from "ws";
const [base = "http://127.0.0.1:8787", token = "dev-device-token", flag] = process.argv.slice(2);
const ws = new WebSocket(base.replace(/^http/, "ws") + "/phone/connect", { headers: { Authorization: `Bearer ${token}` } });
const days = (s, e) => { const out = []; for (let d = new Date(s); d < new Date(e); d = new Date(+d + 864e5)) out.push(d.toISOString().slice(0, 10)); return out; };
const fake = {
  get_steps: (p) => ({ timezone: p.timezone, bucket: p.bucket, buckets: days(p.start, p.end).map((d) => ({ start: d, steps: 8000 + d.length * 37 })), total: 0 }),
  get_sleep: (p) => ({ sessions: [{ start: p.start, end: p.end, duration_min: 452, stages: [{ stage: "deep", minutes: 80 }, { stage: "rem", minutes: 95 }] }] }),
  get_heart_rate: (p) => ({ buckets: [{ start: p.start, min: 52, avg: 68, max: 141 }], resting_bpm: 55 }),
  get_exercise_sessions: () => ({ sessions: [{ type: "RUNNING", start: "2026-10-12T07:00:00Z", end: "2026-10-12T07:40:00Z", distance_m: 7100, kcal: 520, avg_hr: 151 }] }),
  get_weight: () => ({ records: [{ time: "2026-10-12T06:30:00Z", kg: 74.2 }] }),
  get_nutrition: () => ({ days: [{ date: "2026-10-12", kcal: 2310, protein_g: 120, carbs_g: 260, fat_g: 80 }], entries: [] }),
  read_records: (p) => ({ recordType: p.recordType, records: [{ time: p.start, value: 1 }], page_token: null }),
  get_phone_status: () => ({ app_version: "sim-0.1", battery: 0.81, health_connect: "available", granted: ["Steps", "SleepSession", "HeartRate"] }),
};
ws.on("open", () => { console.log("SIM connected"); ws.send(JSON.stringify({ type: "hello", app_version: "sim-0.1", device: "Simulated Pixel", permissions: ["Steps"] })); setInterval(() => ws.send('{"type":"ping"}'), 20000); });
ws.on("unexpected-response", (_r, res) => { console.log("SIM rejected", res.statusCode); process.exit(2); });
ws.on("message", (m) => {
  const req = JSON.parse(m.toString());
  if (req.type === "pong") return;
  console.log("SIM <-", req.method);
  if (flag === "--slow") return; // never answer -> timeout test
  const f = fake[req.method];
  ws.send(JSON.stringify(f ? { id: req.id, result: f(req.params) } : { id: req.id, error: { code: "unknown_method", message: req.method } }));
});
ws.on("close", (c, r) => { console.log("SIM closed", c, r.toString()); process.exit(0); });
