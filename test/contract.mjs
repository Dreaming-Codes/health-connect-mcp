import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
const [base, mcpToken, deviceToken] = process.argv.slice(2);
const out = [];
const http = async (label, path, init) => { const r = await fetch(base + path, init); out.push([label, r.status, await r.text()]); };
const dev = (body) => ({ method: "POST", headers: { Authorization: `Bearer ${deviceToken}`, "content-type": "application/json" }, body });
await http("root", "/");
await http("404", "/nope");
await http("mcp no auth", "/mcp", { method: "POST" });
await http("register GET", "/phone/register");
await http("register bad token", "/phone/register", { method: "POST", headers: { Authorization: "Bearer x" }, body: "{}" });
await http("register bad json", "/phone/register", dev("nope"));
await http("register no fcm", "/phone/register", dev("{}"));
await http("register short fcm", "/phone/register", dev(JSON.stringify({ fcm_token: "short" })));
await http("result unknown", "/phone/result/abc", dev(JSON.stringify({ result: 1 })));
await http("result array", "/phone/result/abc", dev("[]"));
const c = new Client({ name: "contract", version: "1" });
await c.connect(new StreamableHTTPClientTransport(new URL(base + "/mcp"), { requestInit: { headers: { Authorization: `Bearer ${mcpToken}` } } }));
out.push(["tools", (await c.listTools()).tools]);
const r = { start: "2026-10-10", end: "2026-10-13" };
for (const [n, a] of [["get_steps", r], ["get_steps", { start: "13/10/2026", end: "2026-10-14" }], ["get_steps", { start: "2026-10-13", end: "2026-10-12" }],
  ["get_steps", { ...r, timezone: "Mars/Base" }], ["read_records", { ...r, recordType: "Bogus" }], ["get_phone_status", {}]]) {
  const res = await c.callTool({ name: n, arguments: a });
  out.push([n, a, res.isError ?? false, res.content[0].text.replace(/"(push_registered_at|last_seen|ts|at)": [^,}\n]+/g, '"$1": X')]);
}
await c.close();
console.log(JSON.stringify(out, null, 1));
