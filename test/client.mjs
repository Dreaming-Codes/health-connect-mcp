import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

export const postAs = (base, bearer, path, body) =>
  fetch(base + path, { method: "POST", headers: { ...(bearer && { Authorization: `Bearer ${bearer}` }), "content-type": "application/json" }, body: JSON.stringify(body) });

export async function enroll(base, name) {
  const r = await postAs(base, null, "/enroll", { name });
  return { status: r.status, body: await r.json() };
}

export async function claimConnector(base, deviceToken) {
  const { code } = await (await postAs(base, deviceToken, "/phone/connector-code", {})).json();
  const r = await postAs(base, null, "/claim", { code });
  const body = await r.json();
  return { status: r.status, body, token: body.headers?.Authorization?.slice(7) };
}

export async function connect(base, token) {
  const c = new Client({ name: "test", version: "1" });
  await c.connect(new StreamableHTTPClientTransport(new URL(base + "/mcp"), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
  return c;
}
