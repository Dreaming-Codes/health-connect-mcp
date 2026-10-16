import { connect } from "./client.mjs";
const [base, token, ...only] = process.argv.slice(2);
const c = await connect(base, token);
const r = { start: "2026-10-10T00:00:00+02:00", end: "2026-10-13T00:00:00+02:00" };
const calls = [["get_phone_status", {}], ["get_steps", r], ["get_heart_rate", r], ["get_sleep", r], ["get_exercise_sessions", r], ["get_weight", r], ["get_nutrition", r]].filter(([n]) => !only.length || only.includes(n));
for (const [n, a] of calls) { const t = Date.now(); const res = await c.callTool({ name: n, arguments: a }); console.log(`--- ${n} ${res.isError ? "ERROR" : "OK"} ${Date.now() - t}ms\n${res.content[0].text}`); }
await c.close();
