import { createServer } from "node:http";
import { generateKeyPairSync } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { claimConnector, connect, enroll, postAs as postTo } from "./client.mjs";

const SIM_PORT = Number(process.env.SIM_PORT ?? 8795);
const WORKER_PORT = Number(process.env.WORKER_PORT ?? 8796);
const base = `http://127.0.0.1:${WORKER_PORT}`;
const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = { project_id: "sim", client_email: "sim@sim.iam", private_key: privateKey.export({ type: "pkcs8", format: "pem" }), token_uri: `http://127.0.0.1:${SIM_PORT}/token` };

const received = [];
const executed = [];
const phones = new Map();

function answer(method, params) {
  switch (method) {
    case "get_phone_status": return { result: { app_version: "0.4.0", device: params.__phone ?? "Sim Phone", android_sdk: 37, battery_percent: 81 } };
    case "list_notifications": return { result: { timezone: params.timezone, total_active: 1, count: 1, notifications: [{ key: "0|com.sim|1|null|1", package: "com.sim", actions: [{ index: 0, title: "Reply", reply: true }] }] } };
    case "dismiss_notification": if (params.notification_id) return { result: { notification_id: params.notification_id, removed: true } };
      return params.key === "missing" ? { error: { code: "not_found", message: "no active notification with that key" } } : { result: { key: params.key, removed: true } };
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

const postAs = (bearer, path, body) => postTo(base, bearer, path, body);
let device, deviceId;
const post = (path, body) => postAs(device, path, body);

const sim = createServer(async (req, res) => {
  let body = ""; for await (const c of req) body += c;
  if (req.url === "/token") { res.end(JSON.stringify({ access_token: "sim-access", expires_in: 3600 })); return; }
  const { data, token } = JSON.parse(body).message;
  res.end(JSON.stringify({ name: "projects/sim/messages/1" }));
  const phone = phones.get(token) ?? { bearer: device, label: "Sim Phone", deviceId };
  if (data.device_id !== phone.deviceId) return;
  const params = JSON.parse(data.params);
  executed.push({ method: data.method, phone: phone.label });
  received.push({ method: data.method, params, keys: Object.keys(data).sort(), phone: phone.label, device_id: data.device_id });
  setTimeout(async () => {
    await postAs(phone.bearer, `/phone/result/${data.id}`, answer(data.method, { ...params, __phone: phone.label }));
    if (data.method === "start_call" && params.agent_id === "answer-me") setTimeout(() => post("/phone/event", { notification_id: params.call_id, action_id: "answered", at: new Date().toISOString() }), 500);
  }, 50);
}).listen(SIM_PORT);

const state = mkdtempSync(join(tmpdir(), "sim-"));
const wrangler = spawn("npx", ["wrangler", "dev", "--port", String(WORKER_PORT), "--persist-to", state,
  "--var", `FCM_ENDPOINT:http://127.0.0.1:${SIM_PORT}`, "--var", `FCM_SERVICE_ACCOUNT:${JSON.stringify(sa)}`, "--var", "PUSH_TIMEOUT_MS:5000"], { stdio: "ignore", detached: true });
for (let i = 0; i < 90; i++) { try { if ((await fetch(base + "/")).ok) break; } catch {} await new Promise((r) => setTimeout(r, 1000)); }

let failures = 0;
const check = (ok, label) => { console.log(`${ok ? "PASS" : "FAIL"} ${label}`); if (!ok) failures++; };
const last = () => received[received.length - 1];

try {
  ({ device_token: device, device_id: deviceId } = (await enroll(base, "Sim Phone")).body);
  const mcpToken = (await claimConnector(base, device)).token;
  check((await post("/phone/register", { fcm_token: "sim-fcm-token-0123456789", app_version: "0.4.0", device: "Sim", mode: "push" })).status === 200, "phone registers");
  const c = await connect(base, mcpToken);
  const call = async (name, args) => { const r = await c.callTool({ name, arguments: args }); return { error: !!r.isError, text: r.content[0].text, json: (() => { try { return JSON.parse(r.content[0].text); } catch { return null; } })() }; };

  let r = await call("get_phone_status", {});
  check(r.json?.phone?.app_version === "0.4.0" && r.json.phone.battery_percent === 81, "get_phone_status round trip");
  check(last().keys.join() === "device_id,id,method,params" && last().device_id === deviceId, "push data carries only device_id, id, method, params");

  r = await call("list_notifications", { since: "2026-10-13T08:00", limit: 5 });
  check(!r.error && r.json.count === 1, "list_notifications returns the phone's answer");
  check(last().params.since === "2026-10-13T08:00:00+02:00" && last().params.timezone === "Europe/Amsterdam" && last().params.limit === 5, "list_notifications normalizes since to an offset time");

  r = await call("dismiss_notification", { key: "missing" });
  check(r.error && r.text.includes("not_found"), "dismiss_notification surfaces not_found");
  r = await call("dismiss_notification", { notification_id: "poll1" });
  check(!r.error && r.json.removed && last().params.notification_id === "poll1" && !("key" in last().params), "dismiss_notification by notification_id");
  r = await call("dismiss_notification", { key: "k", notification_id: "poll1" });
  check(r.error && r.text.includes("exactly one"), "dismiss_notification refuses key and notification_id together");
  r = await call("dismiss_notification", {});
  check(r.error && r.text.includes("exactly one"), "dismiss_notification needs key or notification_id");

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
  const zoned = (v, offset) => typeof v === "string" && v.endsWith(offset) && !Number.isNaN(Date.parse(v));
  r = await call("get_notification_responses", { notification_id: "poll1", timezone: "America/Los_Angeles" });
  const resp = r.json?.responses?.[0] ?? {};
  check(zoned(resp.phone_time, "-07:00") && zoned(resp.received_at, "-07:00") && !("at" in resp), "get_notification_responses gives phone_time and received_at in the timezone");
  check((await post("/phone/event", { notification_id: "poll1" })).status === 400, "malformed event is rejected");
  check((await fetch(base + "/phone/event", { method: "POST", headers: { Authorization: "Bearer nope" }, body: "{}" })).status === 401, "event needs the device token");

  r = await call("send_notification", { title: "Find me", alarm: true });
  check(!r.error && last().params.alarm === true && last().params.insistent === true, "alarm notification forwards alarm and insistent");

  r = await call("start_call", { agent_id: "answer-me", reason: "Your 9:00 starts in 5 minutes", ring_seconds: 5 });
  check(r.json?.outcome === "answered" && r.json.call_id?.startsWith("call-") && zoned(r.json.phone_time, "+02:00"), "start_call waits for answered");
  check(!("timezone" in last().params), "start_call keeps timezone on the Worker");
  r = await call("start_call", { agent_id: "nobody", reason: "Ping", ring_seconds: 5 });
  check(r.json?.outcome === "missed", "start_call reports missed after ring_seconds + grace");
  r = await call("start_call", { agent_id: "bad id!", reason: "x" });
  check(r.error, "start_call rejects an invalid agent_id");

  r = await call("get_location", {});
  check(!r.error && r.json.latitude === 52.37 && last().params.max_age_s === 120 && last().params.timezone === "Europe/Amsterdam", "get_location round trip with defaults");
  r = await call("get_phone_status", { timezone: "Asia/Tokyo" });
  check(r.json?.timezone === "Asia/Tokyo" && zoned(r.json.last_seen, "+09:00") && zoned(r.json.push_registered_at, "+09:00") && zoned(r.json.last_push?.at, "+09:00") && zoned(r.json.device?.updated_at, "+09:00") && !("ts" in r.json.device), "get_phone_status times follow timezone");
  r = await call("get_phone_status", { timezone: "Mars/Olympus" });
  check(r.error && r.text.includes("invalid timezone"), "get_phone_status rejects an invalid timezone");
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

  const online = async (bearer, label, fcm = `sim-fcm-${label}-0123456789`) => {
    phones.set(fcm, { bearer, label, deviceId: bearer.split("_")[1] });
    return (await postAs(bearer, "/phone/register", { fcm_token: fcm, app_version: "0.4.0", device: label, mode: "push" })).status;
  };
  const mcpAs = async (token) => {
    const m = await connect(base, token);
    return { close: () => m.close(), call: async (name, args = {}) => { const x = await m.callTool({ name, arguments: args }); let j = null; try { j = JSON.parse(x.content[0].text); } catch {} return { error: !!x.isError, text: x.content[0].text, json: j }; } };
  };

  const a = await enroll(base, "Phone A");
  check(a.status === 200 && /^hcd_[0-9a-f]{16}_/.test(a.body.device_token) && Object.keys(a.body).sort().join() === "device_id,device_token", "enroll returns only the device id and token", a.body.device_id);
  check(await online(a.body.device_token, "Phone A") === 200, "enrolled device registers FCM with its own token");
  const cc = await (await postAs(a.body.device_token, "/phone/connector-code", {})).json();
  check(/^[0-9A-Z]{4}-[0-9A-Z]{4}$/.test(cc.code) && cc.claim_url.endsWith("/claim"), "device gets a connector claim code", cc);
  const claim = (code) => postAs(null, "/claim", { code }).then(async (r) => ({ status: r.status, body: await r.json() }));
  const cl = await claim(cc.code.toLowerCase().replace("-", ""));
  check(cl.status === 200 && cl.body.name === "Android Connect (Phone A)" && cl.body.mcp_url === `${base}/mcp`, "claim returns the connector URL, header and device-named connector", cl.body.name);
  check((await claim(cc.code)).status === 400, "claim code works only once");
  check((await claim("ZZZZ-ZZZZ")).status === 400, "unknown claim code is rejected");
  const ownerToken = cl.body.headers.Authorization.slice(7);

  const b = await enroll(base, "Phone B");
  check(b.status === 200 && b.body.device_id !== a.body.device_id, "a second phone enrolls independently");
  await online(b.body.device_token, "Phone B");
  const bc = await claimConnector(base, b.body.device_token);
  check(bc.body.name === "Android Connect (Phone B)", "the second phone's connector is named after it, so the two do not collide", bc.body.name);
  const ma = await mcpAs(ownerToken), mb = await mcpAs(bc.token);
  r = await ma.call("get_phone_status");
  check(r.json?.name === "Phone A" && r.json?.phone?.device === "Phone A", "phone A's connector reaches only phone A", r.json?.phone);
  r = await mb.call("get_phone_status");
  check(r.json?.name === "Phone B" && r.json?.phone?.device === "Phone B", "phone B's connector reaches only phone B", r.json?.phone);
  r = await ma.call("list_notifications");
  check(!r.error && last().phone === "Phone A", "phone tools on A's connector push to phone A");
  check(!(await c.listTools()).tools.some((t) => t.name === "list_devices" || t.inputSchema.properties?.device), "no tool takes a device, so a connector reaches exactly one phone");
  await ma.close(); await mb.close();

  check((await fetch(base + "/mcp", { method: "POST", headers: { Authorization: `Bearer ${a.body.device_token}` } })).status === 401, "device token is refused on /mcp");
  check((await postAs(ownerToken, "/phone/register", { fcm_token: "x".repeat(30) })).status === 401, "connector token is refused on /phone");
  check((await postAs("hcd_0123456789abcdef_" + "A".repeat(43), "/phone/register", { fcm_token: "x".repeat(30) })).status === 401, "forged device token is refused");
  const mcpStatus = (token, name = "send_message", args = { to: "+31600000000", text: "stale" }) => fetch(base + "/mcp", { method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }) }).then((x) => x.status);
  const ran = (label) => executed.filter((x) => x.phone === label).map((x) => x.method);

  const d = await enroll(base, "Phone D");
  await online(d.body.device_token, "Phone D");
  const d1 = await claimConnector(base, d.body.device_token);
  const d2 = await claimConnector(base, d.body.device_token);
  check(await mcpStatus(d1.token) === 401 && await mcpStatus(d2.token, "list_threads", {}) !== 401, "claiming a new connector revokes the previous one");
  const pendingCode = (await (await postAs(d.body.device_token, "/phone/connector-code", {})).json()).code;
  check((await postAs(d.body.device_token, "/phone/disconnect", {})).status === 200, "disconnect succeeds with the device token");
  check((await postAs(null, "/claim", { code: pendingCode })).status === 400, "a claim code issued before disconnect mints nothing");
  check(await mcpStatus(d2.token) === 401, "connector token is refused after disconnect");
  check((await postAs(d.body.device_token, "/phone/connector-code", {})).status === 401, "device token is refused after disconnect");
  check((await postAs(d.body.device_token, "/phone/disconnect", {})).status === 401, "disconnect cannot be replayed with the revoked token");
  const d3 = await enroll(base, "Phone D");
  await online(d3.body.device_token, "Phone D", "sim-fcm-Phone D-rotated-0123456789");
  check(await mcpStatus(d2.token) === 401, "the old connector stays refused after re-enrolling");
  check(!ran("Phone D").includes("send_message"), "no stale key made Phone D send a message", ran("Phone D"));

  const l1 = await enroll(base, "Legacy");
  await online(l1.body.device_token, "Legacy", "sim-fcm-legacy-0123456789");
  const lc = await claimConnector(base, l1.body.device_token);
  const l2 = await enroll(base, "Legacy");
  await online(l2.body.device_token, "Legacy", "sim-fcm-legacy-0123456789");
  const ml = await mcpAs(lc.token);
  for (const [name, args] of [["send_message", { to: "+31600000000", text: "stale" }], ["reply_to_thread", { thread_id: 3, text: "stale" }],
    ["perform_notification_action", { key: "k", action: 0, replyText: "stale" }], ["start_call", { agent_id: "nobody", reason: "stale", ring_seconds: 5 }]]) {
    r = await ml.call(name, args);
    check(r.error && !ran("Legacy").includes(name), `a hub left behind by re-enrolling cannot make the phone run ${name}`, r.text.slice(0, 80));
  }
  await ml.close();

  let limited = false;
  for (let i = 0; i < 10 && !limited; i++) limited = (await enroll(base, `Flood ${i}`)).status === 429;
  check(limited, "enroll is rate limited per IP");

  await c.close();
} finally {
  process.kill(-wrangler.pid); sim.close();
}
console.log(failures ? `${failures} FAILED` : "ALL PASS");
process.exit(failures ? 1 : 0);
