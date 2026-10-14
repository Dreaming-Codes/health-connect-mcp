import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
const [base, token, quiet] = process.argv.slice(2);
const c = new Client({ name: "t", version: "1" });
await c.connect(new StreamableHTTPClientTransport(new URL(base + "/mcp"), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
for (const a of [{ start: "2026-10-13T00:00:00", end: "2026-10-14T00:00:00" }, { start: "2026-10-12", end: "2026-10-13" }, { start: "2026-10-12T08:00", end: "2026-10-12T20:00", timezone: "America/Los_Angeles" }, { start: "2026-10-12T00:00:00+02:00", end: "2026-10-13T00:00:00Z" }, { start: "13/10/2026", end: "2026-10-14" }, { start: "2026-10-13", end: "2026-10-12" }, { start: "2026-10-12", end: "2026-10-13", timezone: "Mars/Base" }]) {
  const r = await c.callTool({ name: "read_records", arguments: { recordType: "Weight", ...a } });
  const t = r.content[0].text; console.log(JSON.stringify(a), r.isError ? "ERROR " + t.slice(0, 150) : "OK" + (quiet ? "" : " " + t.replace(/\s+/g, " ").slice(0, 120)));
}
await c.close();
