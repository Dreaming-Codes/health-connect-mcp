import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { connect } from "./client.mjs";

const { WORKER_URL, ADB = "adb", SERIAL = "emulator-5554", SCREENS = "dist/screens" } = process.env;
const P = "dev.dreamingcodes.hcmcp";
const adb = (...a) => execFileSync("timeout", ["180", ADB, "-s", SERIAL, ...a], { encoding: "utf8" }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(SCREENS, { recursive: true });
const shot = (name) => writeFileSync(`${SCREENS}/${name}.png`, execFileSync("timeout", ["120", ADB, "-s", SERIAL, "exec-out", "screencap", "-p"]));
const ui = () => { adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"); return adb("shell", "cat", "/sdcard/ui.xml"); };
function tapText(text) {
  const m = ui().match(new RegExp(`(?:text|content-desc)="${text}"[^>]*?bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!m) return false;
  adb("shell", "input", "tap", String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1));
  return true;
}
async function waitFor(re, ms) {
  for (const end = Date.now() + ms; Date.now() < end; await sleep(3000)) { const m = ui().match(re); if (m) return m; }
  return null;
}

let failures = 0;
const check = (ok, label, detail) => { console.log(`${ok ? "PASS" : "FAIL"} ${label}${ok || detail === undefined ? "" : "  -> " + JSON.stringify(detail).slice(0, 300)}`); if (!ok) failures++; };
adb("shell", "input", "keyevent", "WAKEUP"); adb("shell", "wm", "dismiss-keyguard");
adb("shell", "am", "start", "-W", "-n", `${P}/.MainActivity`);
await waitFor(/text="Worker URL"/, 120000);
shot("setup-connect");
tapText("Worker URL");
adb("shell", "input", "keycombination", "113", "29"); adb("shell", "input", "keyevent", "DEL");
adb("shell", "input", "text", WORKER_URL);
adb("shell", "input", "keyevent", "BACK");
tapText("Connect");
const connected = await waitFor(/text="Register with Grok Bot"/, 180000);
check(!!connected, "setup: URL + Connect enrolls the phone");
await sleep(5000);
shot("setup-connected");
tapText("Register with Grok Bot");
const codeMatch = await waitFor(/text="([0-9A-Z]{4}-[0-9A-Z]{4})"/, 60000);
check(!!codeMatch, "Register with Grok Bot shows a claim code", null);
shot("grok-bot-claim-code");
tapText("Done");
const claimed = await fetch(`${WORKER_URL}/claim`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code: codeMatch?.[1] }) }).then((r) => r.json());
check(claimed.headers?.Authorization?.startsWith("Bearer hco_"), "claim code from the app yields a connector", claimed.name);
adb("shell", "input", "swipe", "360", "1200", "360", "300", "300"); await sleep(3000);
adb("shell", "input", "swipe", "360", "1200", "360", "300", "300"); await sleep(3000);
shot("permission-cards");
for (const perm of ["POST_NOTIFICATIONS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "SEND_SMS", "READ_CONTACTS"])
  try { adb("shell", "pm", "grant", P, `android.permission.${perm}`); } catch {}
adb("shell", "cmd", "notification", "allow_listener", `${P}/${P}.NotificationListener`);
adb("shell", "cmd", "notification", "allow_dnd", P);
adb("shell", "appops", "set", P, "USE_FULL_SCREEN_INTENT", "allow");
adb("shell", "input", "keyevent", "HOME"); await sleep(8000);
const c = await connect(WORKER_URL, claimed.headers.Authorization.slice(7));
const call = async (name, args = {}) => {
  const t0 = Date.now();
  const r = await c.callTool({ name, arguments: args });
  const text = r.content[0].text;
  let json = null; try { json = JSON.parse(text); } catch {}
  return { error: !!r.isError, text, json, ms: Date.now() - t0 };
};
const byTitle = async (title) => (await call("list_notifications", { package: P, limit: 50 })).json?.notifications?.find((n) => n.title === title);

let r = await call("get_phone_status");
check(r.json?.phone?.app_version?.startsWith("0.4.0"), `get_phone_status over real FCM (${r.ms} ms)`, r.json);
check(r.json?.phone?.capabilities?.notification_access === true, "capabilities report notification access", r.json?.phone?.capabilities);

r = await call("send_notification", { title: "Live test", text: "Pick one", importance: "high", actions: [{ id: "yes", label: "Yes" }, { id: "say", label: "Say", reply: { placeholder: "Type" } }] });
check(!r.error && r.json.channel_id === "bot_assistant_high", "send_notification", r.text);
const nid = r.json?.notification_id;
await sleep(2000);
adb("shell", "cmd", "statusbar", "expand-notifications"); await sleep(3000); shot("notification-shade"); adb("shell", "cmd", "statusbar", "collapse");
const bot = await byTitle("Live test");
check(bot?.actions?.map((a) => a.title).join() === "Yes,Say", "bot notification visible through list_notifications", bot);
if (bot) {
  r = await call("perform_notification_action", { key: bot.key, action: 1, replyText: "live reply" });
  check(!r.error, "reply to the bot notification through perform_notification_action", r.text);
}
r = await call("get_notification_responses", { notification_id: nid, wait_seconds: 20 });
check(r.json?.responses?.[0]?.action_id === "say" && r.json.responses[0].reply_text === "live reply", `event reached the Worker (${r.ms} ms)`, r.json);

r = await call("send_notification", { title: "Live alarm", alarm: true, vibrate: false });
check(!r.error && r.json.channel_id.startsWith("bot_alarm") && typeof r.json.alarm_volume === "number", "alarm notification reports volume", r.json);
await sleep(2000);
const alarm = await byTitle("Live alarm");
r = alarm ? await call("dismiss_notification", { key: alarm.key }) : { error: true, text: "alarm not listed" };
check(!r.error && r.json.removed, "dismiss_notification stops the alarm", r.text);

const ring = (agent_id, seconds) => call("start_call", { agent_id, caller_name: "Grok Bot", reason: "Your standup starts in 5 minutes", ring_seconds: seconds });
adb("shell", "input", "keyevent", "SLEEP"); await sleep(3000);
let pending = ring("live_answer", 40);
await sleep(12000);
shot("incoming-call");
const answered = tapText("Answer");
r = await pending;
check(answered && r.json?.outcome === "answered", "start_call answered from the call screen", r.json);
await sleep(3000); adb("shell", "input", "keyevent", "WAKEUP"); adb("shell", "wm", "dismiss-keyguard"); adb("shell", "input", "keyevent", "HOME");

pending = ring("live_decline", 40);
await sleep(12000);
const declined = tapText("Decline");
r = await pending;
check(declined && r.json?.outcome === "declined", "start_call declined", r.json);

r = await ring("live_missed", 5);
check(r.json?.outcome === "missed", `start_call missed (${r.ms} ms)`, r.json);

adb("emu", "geo", "fix", "4.8952", "52.3702");
await sleep(3000);
r = await call("get_location", { max_age_s: 0, timeout_s: 12 });
check(!r.error && Math.abs(r.json.latitude - 52.3702) < 0.01, "get_location returns the emulator fix", r.json);

adb("emu", "sms", "send", "5551234", "live inbound sms");
await sleep(5000);
r = await call("list_messages", { box: "inbox", limit: 5 });
check(r.json?.messages?.some((m) => m.text === "live inbound sms"), "list_messages sees an inbound SMS", r.json);
r = await call("list_threads", { limit: 5 });
const thread = r.json?.threads?.find((t) => t.addresses?.some((a) => a.endsWith("5551234")));
check(!!thread, "list_threads lists the thread", r.json);
r = await call("send_message", { to: "5551234", text: "live outbound sms" });
check(!r.error && r.json.sent === "sent", "send_message reports sent", r.json);
if (thread) {
  r = await call("reply_to_thread", { thread_id: thread.thread_id, text: "live thread reply" });
  check(!r.error && r.json.sent === "sent", "reply_to_thread reports sent", r.json);
}
r = await call("list_messages", { address: "5551234", box: "sent", limit: 5 });
check(r.json?.messages?.some((m) => m.text === "live outbound sms"), "sent SMS appears in the sent box", r.json);
check(r.json?.messages?.some((m) => m.contact_name === "Ann Example"), "contact name resolved on messages", r.json?.messages);
r = await call("search_contacts", { query: "Ann" });
check(r.json?.contacts?.some((x) => x.name === "Ann Example" && x.phones?.length), "search_contacts by name", r.json);
r = await call("search_contacts", { query: "5551234" });
check(r.json?.contacts?.some((x) => x.name === "Ann Example"), "search_contacts by number", r.json);

r = await call("list_devices");
check(r.json?.devices?.length === 1 && r.json.devices[0].default, "connector sees exactly the emulator", r.json);
r = await call("get_steps", { start: "2026-10-16", end: "2026-10-17" });
check(!r.error || !/unknown_method|offline|timed out/.test(r.text), "health tools still answer", r.text);
await c.close();
console.log(failures ? `${failures} FAILED` : "ALL PASS");
process.exit(failures ? 1 : 0);
