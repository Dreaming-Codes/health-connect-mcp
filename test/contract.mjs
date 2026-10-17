import { connect } from "./client.mjs";
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
const c = await connect(base, mcpToken);
out.push(["tools", (await c.listTools()).tools]);
const r = { start: "2026-10-10", end: "2026-10-13" };
const branchCases = process.env.CONTRACT_BASELINE ? [] : [
  ["list_notifications", {}], ["list_notifications", { package: "com.whatsapp", since: "2026-10-13T08:00", limit: 5 }],
  ["list_notifications", { since: "yesterday" }], ["list_notifications", { limit: 0 }], ["list_notifications", { timezone: "Mars/Base" }],
  ["dismiss_notification", { key: "0|com.example|1|null|10001" }], ["dismiss_notification", {}], ["dismiss_notification", { key: "" }],
  ["perform_notification_action", { key: "k", action: 0, replyText: "on my way" }], ["perform_notification_action", { key: "k", action: "content" }],
  ["perform_notification_action", { key: "k", action: -1 }], ["perform_notification_action", { key: "k", action: "open" }],
  ["perform_notification_action", { key: "k", action: "content", replyText: "x" }], ["perform_notification_action", { key: "k", action: 0, replyText: "" }],
  ["send_notification", { title: "Hi", actions: [{ id: "yes", label: "Yes" }, { id: "say", label: "Say", reply: { placeholder: "Type" } }] }],
  ["send_notification", { title: "Wake", alarm: true, vibrate: false }], ["send_notification", { title: "" }],
  ["send_notification", { title: "x", actions: [{ id: "a", label: "A" }, { id: "a", label: "B" }] }], ["send_notification", { title: "x", actions: [{ id: "content", label: "A" }] }],
  ["send_notification", { title: "x", tag: "has space" }], ["send_notification", { title: "x", importance: "max" }],
  ["get_notification_responses", {}], ["get_notification_responses", { wait_seconds: 21 }], ["get_notification_responses", { since: "nope" }],
  ["start_call", { agent_id: "agent_1", reason: "Standup in 5" }], ["start_call", { agent_id: "bad id", reason: "x" }], ["start_call", { agent_id: "a" }],
  ["start_call", { agent_id: "a", reason: "x".repeat(141) }], ["start_call", { agent_id: "a", reason: "x", ring_seconds: 61 }],
  ["get_location", {}], ["get_location", { accuracy: "high", max_age_s: -1 }],
  ["list_threads", {}], ["list_messages", { box: "inbox", since: "2026-10-12" }], ["list_messages", { since: "2026-10-13", until: "2026-10-12" }], ["list_messages", { box: "spam" }],
  ["send_message", { to: "+31 6 1234 5678", text: "hi" }], ["send_message", { to: "abc", text: "hi" }], ["send_message", { to: "+316", text: "" }],
  ["reply_to_thread", { thread_id: 3, text: "hi" }], ["reply_to_thread", { thread_id: 0, text: "hi" }],
  ["search_contacts", { query: "Ann" }], ["search_contacts", { query: "" }],
];
for (const [n, a] of [["get_steps", r], ["get_steps", { start: "13/10/2026", end: "2026-10-14" }], ["get_steps", { start: "2026-10-13", end: "2026-10-12" }],
  ["get_steps", { ...r, timezone: "Mars/Base" }], ["read_records", { ...r, recordType: "Bogus" }], ["get_phone_status", {}],
  ...branchCases]) {
  const res = await c.callTool({ name: n, arguments: a });
  out.push([n, a, res.isError ?? false, res.content[0].text.replace(/"(push_registered_at|last_seen|ts|at)": [^,}\n]+/g, '"$1": X')]);
}
await c.close();
console.log(JSON.stringify(out, null, 1));
