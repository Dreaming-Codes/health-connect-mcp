import { createServer } from "node:http";
import { generateKeyPairSync } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { connect } from "./client.mjs";

const SIM_PORT = Number(process.env.SIM_PORT ?? 8795);
const WORKER_PORT = Number(process.env.WORKER_PORT ?? 8796);
const base = `http://127.0.0.1:${WORKER_PORT}`;
const device = "sim-device-token";
const mcpToken = readFileSync(".dev.vars", "utf8").match(/^MCP_TOKEN=(.*)$/m)[1];
const deviceHash = (await import("node:crypto")).createHash("sha256").update(device).digest("hex");
const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = { project_id: "sim", client_email: "sim@sim.iam", private_key: privateKey.export({ type: "pkcs8", format: "pem" }), token_uri: `http://127.0.0.1:${SIM_PORT}/token` };

const received = [];
const phones = new Map();
let legacy = false;
const LEGACY_METHODS = new Set(["get_steps", "get_sleep", "get_heart_rate", "get_exercise_sessions", "get_weight", "get_nutrition", "read_records", "get_phone_status"]);

function answer(method, params) {
  if (legacy && !LEGACY_METHODS.has(method)) return { error: { code: "unknown_method", message: method } };
  switch (method) {
    case "get_phone_status": return { result: { app_version: legacy ? "0.3.2" : "0.4.0", device: params.__phone ?? "Sim Phone", android_sdk: 37 } };
    case "list_notifications": return { result: { timezone: params.timezone, total_active: 1, count: 1, notifications: [{ key: "0|com.sim|1|null|1", package: "com.sim", actions: [{ index: 0, title: "Reply", reply: true }] }] } };
    case "dismiss_notification": return params.key === "missing" ? { error: { code: "not_found", message: "no active notification with that key" } } : { result: { key: params.key, removed: true } };
    case "perform_notification_action": return { result: { key: params.key, sent: true, opens_activity: false } };
    case "send_notification": return { result: { notification_id: params.tag ?? "nsim0001", channel_id: params.alarm ? "bot_alarm" : "bot_assistant_default" } };
    case "start_call": return { result: { call_id: params.call_id, ringing: true, full_screen: true } };
    case "get_location": return { result: { latitude: 52.37, longitude: 4.89, accuracy_m: 12, source: "current" } };
    case "list_threads": return { result: { threads: [{ thread_id: 3, addresses: ["+31600000000"], group: false }] } };
    case "list_messages": return { result: { count: 0, messages: [] } };
    case "send_message": case "reply_to_thread": return { result: { to: params.to ?? "+31600000000", parts: 1, sent: "sent", delivered: "pending" } };
    case "search_contacts": return { error: { code: "contacts_permission_missing", message: "contacts permission is not granted" } };
    default: return { result: { method } };
  }
}

const postAs = (bearer, path, body) => fetch(base + path, { method: "POST", headers: { ...(bearer && { Authorization: `Bearer ${bearer}` }), "content-type": "application/json" }, body: JSON.stringify(body) });
const post = (path, body) => postAs(device, path, body);

const sim = createServer(async (req, res) => {
  let body = ""; for await (const c of req) body += c;
  if (req.url === "/token") { res.end(JSON.stringify({ access_token: "sim-access", expires_in: 3600 })); return; }
  const { data, token } = JSON.parse(body).message;
  res.end(JSON.stringify({ name: "projects/sim/messages/1" }));
  const phone = phones.get(token) ?? { bearer: device, label: "Sim Phone" };
  const params = JSON.parse(data.params);
  received.push({ method: data.method, params, keys: Object.keys(data).sort(), phone: phone.label });
  setTimeout(async () => {
    await postAs(phone.bearer, `/phone/result/${data.id}`, answer(data.method, { ...params, __phone: phone.label }));
    if (data.method === "start_call" && params.agent_id === "answer-me") setTimeout(() => post("/phone/event", { notification_id: params.call_id, action_id: "answered", at: new Date().toISOString() }), 500);
  }, 50);
}).listen(SIM_PORT);

const state = mkdtempSync(join(tmpdir(), "sim-"));
const wrangler = spawn("npx", ["wrangler", "dev", "--port", String(WORKER_PORT), "--persist-to", state,
  "--var", `DEVICE_TOKEN_HASH:${deviceHash}`, "--var", `FCM_ENDPOINT:http://127.0.0.1:${SIM_PORT}`, "--var", `FCM_SERVICE_ACCOUNT:${JSON.stringify(sa)}`], { stdio: "ignore", detached: true });
for (let i = 0; i < 90; i++) { try { if ((await fetch(base + "/")).ok) break; } catch {} await new Promise((r) => setTimeout(r, 1000)); }

let failures = 0;
const check = (ok, label) => { console.log(`${ok ? "PASS" : "FAIL"} ${label}`); if (!ok) failures++; };
const last = () => received[received.length - 1];

try {
  check((await post("/phone/register", { fcm_token: "sim-fcm-token-0123456789", app_version: "0.4.0", device: "Sim", mode: "push" })).status === 200, "phone registers");
  const c = await connect(base, mcpToken);
  const call = async (name, args) => { const r = await c.callTool({ name, arguments: args }); return { error: !!r.isError, text: r.content[0].text, json: (() => { try { return JSON.parse(r.content[0].text); } catch { return null; } })() }; };

  let r = await call("get_phone_status", {});
  check(r.json?.phone?.app_version === "0.4.0", "get_phone_status round trip");
  check(last().keys.join() === "id,method,params", "push data carries only id, method, params");

  r = await call("list_notifications", { since: "2026-10-13T08:00", limit: 5 });
  check(!r.error && r.json.count === 1, "list_notifications returns the phone's answer");
  check(last().params.since === "2026-10-13T08:00:00+02:00" && last().params.timezone === "Europe/Amsterdam" && last().params.limit === 5, "list_notifications normalizes since to an offset time");

  r = await call("dismiss_notification", { key: "missing" });
  check(r.error && r.text.includes("not_found"), "dismiss_notification surfaces not_found");

  r = await call("perform_notification_action", { key: "k", action: 0, replyText: "on my way" });
  check(!r.error && r.json.sent && last().params.replyText === "on my way" && last().params.action === 0, "perform_notification_action forwards reply");

  r = await call("send_notification", { title: "Hi", text: "Pick one", tag: "poll1", actions: [{ id: "yes", label: "Yes" }, { id: "say", label: "Say", reply: { placeholder: "Type" } }] });
  check(!r.error && r.json.notification_id === "poll1", "send_notification returns the notification id");
  check(last().params.importance === "default" && last().params.channel === "Assistant" && last().params.alarm === false, "send_notification fills defaults");

  r = await call("get_notification_responses", { notification_id: "poll1" });
  check(!r.error && r.json.responses.length === 0, "no responses before a tap");
  setTimeout(() => post("/phone/event", { notification_id: "poll1", action_id: "say", reply_text: "sounds good", at: new Date().toISOString() }), 1500);
  const t0 = Date.now();
  r = await call("get_notification_responses", { notification_id: "poll1", wait_seconds: 10 });
  check(r.json?.responses?.[0]?.reply_text === "sounds good" && Date.now() - t0 < 8000, "get_notification_responses long-polls until a reply arrives");
  check((await post("/phone/event", { notification_id: "poll1" })).status === 400, "malformed event is rejected");
  check((await fetch(base + "/phone/event", { method: "POST", headers: { Authorization: "Bearer nope" }, body: "{}" })).status === 401, "event needs the device token");

  r = await call("send_notification", { title: "Find me", alarm: true });
  check(!r.error && last().params.alarm === true && last().params.insistent === true, "alarm notification forwards alarm and insistent");

  r = await call("start_call", { agent_id: "answer-me", reason: "Your 9:00 starts in 5 minutes", ring_seconds: 5 });
  check(r.json?.outcome === "answered" && r.json.call_id?.startsWith("call-"), "start_call waits for answered");
  r = await call("start_call", { agent_id: "nobody", reason: "Ping", ring_seconds: 5 });
  check(r.json?.outcome === "missed", "start_call reports missed after ring_seconds + grace");
  r = await call("start_call", { agent_id: "bad id!", reason: "x" });
  check(r.error, "start_call rejects an invalid agent_id");

  r = await call("get_location", {});
  check(!r.error && r.json.latitude === 52.37 && last().params.max_age_s === 120, "get_location round trip with defaults");
  r = await call("list_messages", { since: "2026-10-12", until: "2026-10-13", box: "inbox" });
  check(!r.error && last().params.since === "2026-10-12T00:00:00+02:00" && last().params.until === "2026-10-13T00:00:00+02:00", "list_messages normalizes since/until");
  r = await call("list_messages", { since: "2026-10-13", until: "2026-10-12" });
  check(r.error && r.text.includes("until must be after since"), "list_messages rejects an inverted range");
  r = await call("send_message", { to: "+31 6 0000 0000", text: "hello" });
  check(!r.error && r.json.sent === "sent", "send_message round trip");
  r = await call("reply_to_thread", { thread_id: 3, text: "hello" });
  check(!r.error, "reply_to_thread round trip");
  r = await call("search_contacts", { query: "Ann" });
  check(r.error && r.text.includes("contacts_permission_missing"), "search_contacts surfaces a missing permission");

  const enroll = (body, bearer) => postAs(bearer, "/enroll", body).then(async (r) => ({ status: r.status, body: await r.json() }));
  const code = (bearer, kind) => postAs(bearer, `/phone/${kind}-code`, {}).then((r) => r.json());
  const claim = (c) => postAs(null, "/claim", { code: c }).then(async (r) => ({ status: r.status, body: await r.json() }));
  const online = async (bearer, label) => {
    const fcm = `sim-fcm-${label}-0123456789`;
    phones.set(fcm, { bearer, label });
    return (await postAs(bearer, "/phone/register", { fcm_token: fcm, app_version: "0.4.0", device: label, mode: "push" })).status;
  };
  const mcpAs = async (token) => {
    const m = await connect(base, token);
    return { close: () => m.close(), call: async (name, args = {}) => { const x = await m.callTool({ name, arguments: args }); let j = null; try { j = JSON.parse(x.content[0].text); } catch {} return { error: !!x.isError, text: x.content[0].text, json: j }; } };
  };

  const a = await enroll({ name: "Phone A" });
  check(a.status === 200 && /^hcd_[0-9a-f]{16}_/.test(a.body.device_token) && a.body.linked === false, "anonymous enroll creates a device in a new owner", a);
  check(await online(a.body.device_token, "Phone A") === 200, "enrolled device registers FCM with its own token");
  const cc = await code(a.body.device_token, "connector");
  check(/^[0-9A-Z]{4}-[0-9A-Z]{4}$/.test(cc.code) && cc.claim_url.endsWith("/claim"), "device gets a connector claim code", cc);
  const cl = await claim(cc.code.toLowerCase().replace("-", ""));
  check(cl.status === 200 && cl.body.name === "Android Connect (Phone A)" && cl.body.mcp_url === `${base}/mcp`, "claim returns the connector URL, header and name", cl.body.name);
  check((await claim(cc.code)).status === 400, "claim code works only once");
  check((await claim("ZZZZ-ZZZZ")).status === 400, "unknown claim code is rejected");
  const ownerToken = cl.body.headers.Authorization.slice(7);
  const mx = await mcpAs(ownerToken);
  r = await mx.call("list_devices");
  check(r.json?.devices?.length === 1 && r.json.devices[0].name === "Phone A", "new owner sees only its own device", r.json);
  r = await mx.call("get_phone_status");
  check(r.json?.phone?.device === "Phone A", "owner tools route to the owner's device", r.json?.phone);

  const pc = await code(a.body.device_token, "pairing");
  const b = await enroll({ name: "Phone B", pairing_code: pc.code });
  check(b.status === 200 && b.body.linked && b.body.owner_id === a.body.owner_id, "pairing code links a second device to the same owner", b.body.owner_id);
  check((await enroll({ name: "Phone X", pairing_code: pc.code })).status === 400, "pairing code works only once");
  await online(b.body.device_token, "Phone B");
  r = await mx.call("list_devices");
  check(r.json?.devices?.length === 2 && r.json.devices[0].name === "Phone B" && r.json.devices[0].default, "list_devices shows both, most recently seen is default", r.json);
  r = await mx.call("get_phone_status", { device: "phone a" });
  check(r.json?.phone?.device === "Phone A", "device param selects by name", r.json?.phone);
  r = await mx.call("list_notifications", { device: a.body.device_id });
  check(!r.error && last().phone === "Phone A", "device param selects by id");
  r = await mx.call("get_location");
  check(!r.error && last().phone === "Phone A", "default device follows last_seen (Phone A answered last)");
  const cdev = await enroll({ name: "Phone C" }, ownerToken);
  check(cdev.status === 200 && cdev.body.owner_id === a.body.owner_id && cdev.body.linked, "owner MCP token links a device");

  const stranger = await enroll({ name: "Stranger" });
  const sc = await claim((await code(stranger.body.device_token, "connector")).code);
  const ms = await mcpAs(sc.body.headers.Authorization.slice(7));
  r = await ms.call("list_devices");
  check(r.json?.devices?.length === 1 && r.json.devices[0].name === "Stranger", "a stranger's connector sees only the stranger's device", r.json);
  r = await ms.call("get_phone_status", { device: a.body.device_id });
  check(r.error && r.text.includes("unknown device"), "a stranger cannot address another owner's device", r.text);
  r = await call("list_devices");
  check(r.json?.devices?.map((d) => d.device_id).join() === "phone", "legacy connector sees only the legacy phone", r.json);
  const le = await enroll({ name: "Legacy tablet" }, mcpToken);
  check(le.status === 200 && le.body.linked, "legacy MCP token links a device into the legacy owner");
  r = await call("list_devices");
  check(r.json?.devices?.length === 2, "legacy connector now sees both", r.json);
  await ms.close(); await mx.close();

  check((await fetch(base + "/mcp", { method: "POST", headers: { Authorization: `Bearer ${a.body.device_token}` } })).status === 401, "device token is refused on /mcp");
  check((await postAs(ownerToken, "/phone/register", { fcm_token: "x".repeat(30) })).status === 401, "connector token is refused on /phone");
  check((await postAs("hcd_0123456789abcdef_" + "A".repeat(43), "/phone/register", { fcm_token: "x".repeat(30) })).status === 401, "forged device token is refused");
  check((await enroll({ name: "Bad" }, "nope")).status === 401, "enroll with a wrong bearer is refused");
  let limited = false;
  for (let i = 0; i < 10 && !limited; i++) limited = (await enroll({ name: `Flood ${i}` })).status === 429;
  check(limited, "enroll is rate limited per IP");

  legacy = true;
  r = await call("get_steps", { start: "2026-10-12", end: "2026-10-13" });
  check(!r.error, "0.3.2 phone still answers health tools");
  r = await call("list_notifications", {});
  check(r.error && r.text.includes("unknown_method"), "0.3.2 phone reports unknown_method for new tools");
  await c.close();
} finally {
  process.kill(-wrangler.pid); sim.close();
}
console.log(failures ? `${failures} FAILED` : "ALL PASS");
process.exit(failures ? 1 : 0);
