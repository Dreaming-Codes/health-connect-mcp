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
let legacy = false;
const LEGACY_METHODS = new Set(["get_steps", "get_sleep", "get_heart_rate", "get_exercise_sessions", "get_weight", "get_nutrition", "read_records", "get_phone_status"]);

function answer(method, params) {
  if (legacy && !LEGACY_METHODS.has(method)) return { error: { code: "unknown_method", message: method } };
  switch (method) {
    case "get_phone_status": return { result: { app_version: legacy ? "0.3.2" : "0.4.0", device: "Sim Phone", android_sdk: 37 } };
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

const post = (path, body) => fetch(base + path, { method: "POST", headers: { Authorization: `Bearer ${device}`, "content-type": "application/json" }, body: JSON.stringify(body) });

const sim = createServer(async (req, res) => {
  let body = ""; for await (const c of req) body += c;
  if (req.url === "/token") { res.end(JSON.stringify({ access_token: "sim-access", expires_in: 3600 })); return; }
  const data = JSON.parse(body).message.data;
  res.end(JSON.stringify({ name: "projects/sim/messages/1" }));
  const params = JSON.parse(data.params);
  received.push({ method: data.method, params, keys: Object.keys(data).sort() });
  setTimeout(async () => {
    await post(`/phone/result/${data.id}`, answer(data.method, params));
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
