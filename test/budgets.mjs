import { readFileSync } from "node:fs";

const app = "android/app/src/main/java/dev/dreamingcodes/hcmcp";
const read = (path, re, what) => {
  const m = re.exec(readFileSync(path, "utf8"));
  if (!m) { console.log(`FAIL cannot find ${what} in ${path}`); process.exit(1); }
  return Number(m[1].replace(/_/g, ""));
};
const schemaMaxS = read("src/tools.ts", /timeout_s: z\.number\(\)\.int\(\)\.min\(1\)\.max\((\d+)\)/, "get_location timeout_s max");
const locationMaxMs = read(`${app}/Location.kt`, /optLong\("timeout_s", \d+\)\.coerceIn\(1, (\d+)\) \* 1000/, "location timeout cap") * 1000;
const dispatchMs = read(`${app}/Push.kt`, /DISPATCH_BUDGET_MS = ([\d_]+)L/, "DISPATCH_BUDGET_MS");
const pushTimeoutMs = read("src/phone-hub.ts", /PUSH_TIMEOUT_MS \?\? (\d+)/, "PUSH_TIMEOUT_MS default");

console.log(`location cap ${locationMaxMs} ms, dispatch budget ${dispatchMs} ms, push timeout ${pushTimeoutMs} ms, schema max ${schemaMaxS} s`);
const failures = [
  [schemaMaxS * 1000 === locationMaxMs, "get_location's timeout_s max matches the app's cap"],
  [locationMaxMs < dispatchMs, "a location wait ends inside the app's dispatch budget"],
  [dispatchMs < pushTimeoutMs, "the app replies before the Worker gives up on the push"],
].filter(([ok]) => !ok);
for (const [, what] of failures) console.log(`FAIL ${what}`);
process.exit(failures.length ? 1 : 0);
