import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const names = (text, re) => new Set([...text.matchAll(re)].map((m) => m[1]));
const worker = names(readFileSync("src/tools.ts", "utf8"), /(?:phone\(|health\(|hub\.call\()"(\w+)"/g);
const dir = "android/app/src/main/java/dev/dreamingcodes/hcmcp";
const phone = names(readdirSync(dir).map((f) => readFileSync(join(dir, f), "utf8")).join("\n"), /"(\w+)" runs\b/g);
const missing = [...worker].filter((n) => !phone.has(n));
const extra = [...phone].filter((n) => !worker.has(n));
console.log(`worker calls ${worker.size} phone methods, the app handles ${phone.size}`);
if (missing.length) console.log(`FAIL no app handler for: ${missing.join(", ")}`);
if (extra.length) console.log(`FAIL app handles methods the Worker never calls: ${extra.join(", ")}`);
process.exit(missing.length || extra.length ? 1 : 0);
