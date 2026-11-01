import { readFileSync } from "node:fs";

const app = "android/app/src/main/java/dev/dreamingcodes/hcmcp";
const read = (path, re, what) => {
  const m = re.exec(readFileSync(path, "utf8"));
  if (!m) { console.log(`FAIL cannot find ${what} in ${path}`); process.exit(1); }
  return Number(m[1].replace(/_/g, ""));
};
const smsSentMs = read(`${app}/Messages.kt`, /SENT_WAIT_MS = ([\d_]+)L/, "SENT_WAIT_MS");
const smsDeliveredMs = read(`${app}/Messages.kt`, /DELIVERY_WAIT_MS = ([\d_]+)L/, "DELIVERY_WAIT_MS");
const dispatchMs = read(`${app}/Push.kt`, /DISPATCH_BUDGET_MS = ([\d_]+)L/, "DISPATCH_BUDGET_MS");
const pushTimeoutMs = read("src/phone-hub.ts", /PUSH_TIMEOUT_MS \?\? (\d+)/, "PUSH_TIMEOUT_MS default");

console.log(`SMS wait ${smsSentMs} + ${smsDeliveredMs} ms, dispatch budget ${dispatchMs} ms, push timeout ${pushTimeoutMs} ms`);
const failures = [
  [smsSentMs + smsDeliveredMs < dispatchMs, "send_message's sent and delivered waits end inside the app's dispatch budget"],
  [dispatchMs < pushTimeoutMs, "the app replies before the Worker gives up on the push"],
].filter(([ok]) => !ok);
for (const [, what] of failures) console.log(`FAIL ${what}`);
process.exit(failures.length ? 1 : 0);
