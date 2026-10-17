import { connect } from "../test/client.mjs";

const [base, mcpToken] = process.argv.slice(2);
const c = await connect(base, mcpToken);
const { tools } = await c.listTools();
await c.close();

const cell = (s) => String(s ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ");
function typeOf(p) {
  if (p.enum) return p.enum.map((v) => `\`${v}\``).join(", ");
  if (p.anyOf) return p.anyOf.map(typeOf).join(" or ");
  if (p.const !== undefined) return `\`${JSON.stringify(p.const)}\``;
  if (p.type === "array") return `array of ${p.items?.type === "object" ? "objects" : typeOf(p.items ?? {})}`;
  const bounds = [p.minimum !== undefined && `≥ ${p.minimum}`, p.maximum !== undefined && `≤ ${p.maximum}`, p.maxLength !== undefined && `≤ ${p.maxLength} chars`, p.maxItems !== undefined && `≤ ${p.maxItems} items`].filter(Boolean);
  return [p.type ?? "any", ...bounds].join(", ");
}
function fields(schema, prefix = "") {
  const req = new Set(schema.required ?? []);
  return Object.entries(schema.properties ?? {}).flatMap(([k, p]) => {
    const row = `| \`${prefix}${k}\` | ${cell(typeOf(p))} | ${req.has(k) && p.default === undefined ? "yes" : "no"} | ${p.default === undefined ? "" : `\`${JSON.stringify(p.default)}\``} | ${cell(p.description)} |`;
    const nested = p.type === "array" && p.items?.type === "object" ? fields(p.items, `${prefix}${k}[].`) : p.type === "object" ? fields(p, `${prefix}${k}.`) : [];
    return [row, ...nested];
  });
}

const out = [
  "# MCP tool reference",
  "",
  `The Worker exposes ${tools.length} tools on \`/mcp\`. Every tool returns one text content item that holds JSON. A failed call returns \`isError: true\` with the error message as text.`,
  "",
  "This file is generated from the live tool list. To regenerate it, run `npm run docs:tools`.",
  "",
];
for (const t of tools.sort((a, b) => a.name.localeCompare(b.name))) {
  out.push(`## \`${t.name}\``, "", cell(t.description), "");
  const rows = fields(t.inputSchema);
  if (rows.length) out.push("| Parameter | Type | Required | Default | Description |", "|---|---|---|---|---|", ...rows, "");
  else out.push("No parameters.", "");
}
console.log(out.join("\n").trimEnd());
