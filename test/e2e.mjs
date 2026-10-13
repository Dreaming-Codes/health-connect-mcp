import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
const [base, token, mode] = process.argv.slice(2);
const c = new Client({ name: "e2e", version: "1" });
await c.connect(new StreamableHTTPClientTransport(new URL(base + "/mcp"), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
const r = { start: "2026-10-10T00:00:00+02:00", end: "2026-10-13T00:00:00+02:00" };
if (mode === "list") { console.log((await c.listTools()).tools.map((t) => t.name).join(",")); }
const calls = mode === "status" ? [["get_phone_status", {}]] : mode === "one" ? [["get_steps", r]] : [
  ["get_steps", r], ["get_sleep", r], ["get_heart_rate", { ...r, bucket: "hour" }], ["get_exercise_sessions", r], ["get_weight", r],
  ["get_nutrition", r], ["read_records", { ...r, recordType: "Hydration" }], ["get_phone_status", {}], ["get_steps", { start: "nope", end: "x" }]];
for (const [n, a] of calls) { const res = await c.callTool({ name: n, arguments: a }); console.log(n, res.isError ? "ERROR" : "OK", res.content[0].text.replace(/\s+/g, " ").slice(0, 160)); }
await c.close();
