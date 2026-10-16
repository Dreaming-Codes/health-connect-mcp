import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { FcmError, mintAccessToken, sendData, type ServiceAccount } from "./fcm";

export interface Env {
  PHONE: DurableObjectNamespace<PhoneHub>;
  DEVICE_TOKEN_HASH: string;
  MCP_TOKEN: string;
  PUSH_TIMEOUT_MS?: string;
  FCM_SERVICE_ACCOUNT?: string;
}

const DEFAULT_TZ = "Europe/Amsterdam";
const OAUTH_REFRESH_MARGIN_MS = 120_000;

const json = (o: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...headers } });
const unauthorized = () => json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });
const isoOrNull = (ms: number | undefined) => (ms ? new Date(ms).toISOString() : null);

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a), eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let r = 0;
  for (let i = 0; i < ea.length; i++) r |= ea[i] ^ eb[i];
  return r === 0;
}

function bearer(req: Request): string | null {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "");
  return m ? m[1].trim() : null;
}

async function isDevice(req: Request, env: Env): Promise<boolean> {
  const t = bearer(req);
  return !!t && !!env.DEVICE_TOKEN_HASH && safeEqual(await sha256Hex(t), env.DEVICE_TOKEN_HASH.toLowerCase());
}

function isMcpClient(req: Request, env: Env): boolean {
  const t = bearer(req);
  return !!t && !!env.MCP_TOKEN && safeEqual(t, env.MCP_TOKEN);
}

const phoneHub = (env: Env) => env.PHONE.get(env.PHONE.idFromName("phone"));

export class PhoneError extends Error {}

const Registration = z.object({
  fcm_token: z.string().min(20),
  app_version: z.unknown(),
  device: z.unknown(),
  mode: z.unknown(),
});
type Registration = z.infer<typeof Registration>;

type PhoneReply = { ok: true; result: unknown } | { ok: false; code?: unknown; message?: unknown };

function parsePhoneReply(body: Record<string, any>): PhoneReply {
  return body.error ? { ok: false, code: body.error.code, message: body.error.message } : { ok: true, result: body.result };
}

type Pending = { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout>; sentAt: number };
type OAuthToken = { token: string; expiresAt: number };

export class PhoneHub extends DurableObject<Env> {
  private pending = new Map<string, Pending>();
  private oauth?: OAuthToken;

  async settle(id: string, reply: PhoneReply): Promise<boolean> {
    const p = this.pending.get(id);
    if (!p) return false;
    this.pending.delete(id);
    clearTimeout(p.timer);
    const now = Date.now();
    await this.ctx.storage.put("lastSeen", now);
    await this.ctx.storage.put("lastPush", { latency_ms: now - p.sentAt, at: now });
    if (reply.ok) p.resolve(reply.result);
    else p.reject(new PhoneError(`phone error ${reply.code ?? ""}: ${reply.message ?? "unknown"}`.trim()));
    return true;
  }

  async updateDevice(info: { app_version?: unknown; device?: unknown }): Promise<Record<string, unknown>> {
    const prev = (await this.ctx.storage.get<Record<string, unknown>>("device")) ?? {};
    const fresh = Object.fromEntries(Object.entries(info).filter(([, v]) => typeof v === "string" && v));
    const next = { ...prev, ...fresh, ts: Date.now() };
    await this.ctx.storage.put("device", next);
    return next;
  }

  async register(info: Registration): Promise<void> {
    await this.ctx.storage.put("fcm", { token: info.fcm_token, registered_at: Date.now() });
    await this.ctx.storage.put("device", { app_version: info.app_version, device: info.device, mode: info.mode, ts: Date.now() });
    await this.ctx.storage.put("lastSeen", Date.now());
  }

  private async accessToken(sa: ServiceAccount): Promise<string> {
    const fresh = (t?: OAuthToken) => !!t && t.expiresAt - Date.now() > OAUTH_REFRESH_MARGIN_MS;
    if (fresh(this.oauth)) return this.oauth!.token;
    const stored = await this.ctx.storage.get<OAuthToken>("oauth");
    if (fresh(stored)) return (this.oauth = stored!).token;
    this.oauth = await mintAccessToken(sa);
    await this.ctx.storage.put("oauth", this.oauth);
    return this.oauth.token;
  }

  async fcmSelfTest(fakeToken: string): Promise<Record<string, unknown>> {
    const sa = this.serviceAccount();
    if (!sa) return { ok: false, error: "FCM_SERVICE_ACCOUNT not configured" };
    const out: Record<string, unknown> = {};
    try {
      const t = await this.accessToken(sa);
      out.oauth = `ok (${t.length} chars, expires ${new Date(this.oauth!.expiresAt).toISOString()})`;
    } catch (e: any) {
      return { ok: false, oauth: String(e.message) };
    }
    try {
      out.send = await sendData(sa, this.oauth!.token, fakeToken, { type: "selftest" }, 10);
    } catch (e: any) {
      out.send_error = String(e.message);
      out.code = e.code;
    }
    return out;
  }

  private serviceAccount(): ServiceAccount | undefined {
    if (!this.env.FCM_SERVICE_ACCOUNT) return undefined;
    try { return JSON.parse(this.env.FCM_SERVICE_ACCOUNT); } catch { return undefined; }
  }

  private async lastSeen(): Promise<number | undefined> {
    return this.ctx.storage.get<number>("lastSeen");
  }

  async status(): Promise<Record<string, unknown>> {
    const fcm = await this.ctx.storage.get<{ token: string; registered_at: number }>("fcm");
    const lastPush = await this.ctx.storage.get<{ latency_ms: number; at: number }>("lastPush");
    return {
      mode: fcm ? "push" : "none",
      push_registered: !!fcm,
      push_registered_at: isoOrNull(fcm?.registered_at),
      last_push: lastPush ? { latency_ms: lastPush.latency_ms, at: new Date(lastPush.at).toISOString() } : null,
      last_seen: isoOrNull(await this.lastSeen()),
      device: (await this.ctx.storage.get("device")) ?? null,
    };
  }

  async call(method: string, params: unknown): Promise<unknown> {
    const id = crypto.randomUUID();
    const lastSeen = async () => isoOrNull(await this.lastSeen()) ?? "never";
    const fcm = await this.ctx.storage.get<{ token: string }>("fcm");
    const sa = this.serviceAccount();
    if (!fcm || !sa) throw new PhoneError(`phone offline (last seen ${await lastSeen()}; ${!fcm ? "no push registration" : "push not configured on server"})`);
    const timeoutMs = Number(this.env.PUSH_TIMEOUT_MS ?? 20000);
    const result = new Promise((resolve, reject) => {
      const timer = setTimeout(async () => {
        this.pending.delete(id);
        reject(new PhoneError(`phone did not answer push within ${timeoutMs / 1000}s (method ${method}; last seen ${await lastSeen()})`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, sentAt: Date.now() });
    });
    try {
      // The push payload transits Google: request id, method and params only, never health data.
      await sendData(sa, await this.accessToken(sa), fcm.token, { id, method, params: JSON.stringify(params ?? {}) }, Math.ceil(timeoutMs / 1000));
    } catch (e: any) {
      const p = this.pending.get(id);
      if (p) { clearTimeout(p.timer); this.pending.delete(id); }
      if (e instanceof FcmError && (e.code === "UNREGISTERED" || e.status === 404)) {
        await this.ctx.storage.delete("fcm");
        throw new PhoneError("push token is no longer valid (app uninstalled or data cleared); open the app to re-register");
      }
      if (e instanceof FcmError && e.status === 401) { this.oauth = undefined; await this.ctx.storage.delete("oauth"); }
      throw new PhoneError(`push send failed: ${e.message ?? e}`);
    }
    return result;
  }
}

const HAS_OFFSET = /(Z|[+-]\d{2}:?\d{2})$/i;
const LOCAL_RE = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?)?$/;

/** Local wall time minus UTC for `tz` at instant `ms`. */
function tzOffsetMs(ms: number, tz: string): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).formatToParts(new Date(ms));
  const g = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return Date.UTC(g("year"), g("month") - 1, g("day"), g("hour"), g("minute"), g("second")) - Math.floor(ms / 1000) * 1000;
}
const pad = (n: number) => String(Math.abs(n)).padStart(2, "0");

/** Offset-less input is wall time in `tz`; a bare date is local midnight. Output always carries an explicit offset. */
export function normalizeTime(s: string, tz: string): string {
  s = s.trim();
  if (HAS_OFFSET.test(s)) {
    if (Number.isNaN(Date.parse(s))) throw new Error(`invalid date-time: ${s}`);
    return s;
  }
  const m = LOCAL_RE.exec(s);
  if (!m) throw new Error(`invalid date-time: ${s} (use YYYY-MM-DD, YYYY-MM-DDTHH:mm[:ss] or ISO-8601 with offset)`);
  const [y, mo, d, h = "0", mi = "0", se = "0", ms = "0"] = m.slice(1).map((v) => v ?? undefined) as string[];
  const wall = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se, +ms.padEnd(3, "0"));
  // Second pass: the offset at the wall time can differ from the offset at the true instant across a DST change.
  const off = tzOffsetMs(wall - tzOffsetMs(wall, tz), tz);
  const oMin = Math.round(off / 60000);
  const sign = oMin < 0 ? "-" : "+";
  return `${y}-${mo}-${d}T${pad(+h)}:${pad(+mi)}:${pad(+se)}${ms !== "0" ? "." + ms.padEnd(3, "0") : ""}${sign}${pad(Math.trunc(Math.abs(oMin) / 60))}:${pad(Math.abs(oMin) % 60)}`;
}

function validTz(tz: string) {
  try { new Intl.DateTimeFormat("en-US", { timeZone: tz }); return true; } catch { return false; }
}

const iso = z.string().refine((s) => HAS_OFFSET.test(s.trim()) ? !Number.isNaN(Date.parse(s)) : LOCAL_RE.test(s.trim()), "must be YYYY-MM-DD, a local date-time (YYYY-MM-DDTHH:mm[:ss], read in `timezone`) or ISO-8601 with offset");
const range = {
  start: iso.describe("Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00"),
  end: iso.describe("Exclusive end, same formats as start (a plain date means local midnight starting that day)"),
  timezone: z.string().default(DEFAULT_TZ).describe(`IANA timezone used for day buckets (default ${DEFAULT_TZ})`),
};
const bucket = z.enum(["none", "hour", "day"]).default("day").describe("Aggregation bucket");

export const RECORD_TYPES = [
  "Steps", "Distance", "ActiveCaloriesBurned", "TotalCaloriesBurned", "FloorsClimbed", "SleepSession", "HeartRate",
  "RestingHeartRate", "HeartRateVariabilityRmssd", "ExerciseSession", "Weight", "Height", "BodyFat", "Nutrition",
  "Hydration", "OxygenSaturation", "RespiratoryRate", "BloodPressure", "BloodGlucose", "BodyTemperature", "Vo2Max", "Speed", "Power",
] as const;

const PHONE_TOOLS: [name: string, description: string, shape: z.ZodRawShape][] = [
  ["get_steps", "Step counts from Health Connect, aggregated per bucket (live from the phone).", { ...range, bucket }],
  ["get_sleep", "Sleep sessions with stages and durations overlapping the range.", range],
  ["get_heart_rate", "Heart rate: min/avg/max per bucket, plus resting HR when available.", { ...range, bucket }],
  ["get_exercise_sessions", "Exercise/workout sessions (type, duration, calories, distance, avg HR).", range],
  ["get_weight", "Weight measurements (kg) in the range.", range],
  ["get_nutrition", "Nutrition entries and per-day totals (energy, macros).", range],
  ["read_records", "Generic raw read of any Health Connect record type in a time range.", {
    recordType: z.enum(RECORD_TYPES).describe("Health Connect record type name"),
    ...range,
    limit: z.number().int().min(1).max(1000).default(200),
  }],
];

type RangeArgs = { start: string; end: string; timezone?: string; [k: string]: unknown };

function normalizeRange(args: RangeArgs): RangeArgs {
  const tz = args.timezone ?? DEFAULT_TZ;
  if (!validTz(tz)) throw new Error(`invalid timezone: ${tz}`);
  const out = { ...args, timezone: tz, start: normalizeTime(args.start, tz), end: normalizeTime(args.end, tz) };
  if (Date.parse(out.end) <= Date.parse(out.start)) throw new Error("end must be after start");
  return out;
}

const text = (o: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(o, null, 2) }] });

function buildServer(env: Env): McpServer {
  const hub = phoneHub(env);
  const server = new McpServer({ name: "health-connect", version: "0.1.0" });
  for (const [name, description, shape] of PHONE_TOOLS) {
    server.registerTool(name, { description, inputSchema: shape }, async (args: any) => {
      try {
        return text(await hub.call(name, normalizeRange(args)));
      } catch (e: any) {
        return { isError: true, content: [{ type: "text" as const, text: String(e?.message ?? e) }] };
      }
    });
  }
  server.registerTool("get_phone_status", { description: "Whether the phone is connected, last-seen time, app/permission info.", inputSchema: {} }, async () => {
    const s: Record<string, unknown> = await hub.status();
    if (s.push_registered) {
      try {
        const phone: any = await hub.call("get_phone_status", {});
        s.phone = phone;
        // Registration-time device info goes stale after app updates; the live answer wins.
        if (phone && typeof phone === "object") s.device = await hub.updateDevice({ app_version: phone.app_version, device: phone.device });
      } catch (e: any) { s.phone_error = String(e?.message ?? e); }
    }
    return text(s);
  });
  return server;
}

const RESULT_PREFIX = "/phone/result/";

async function handlePhone(req: Request, env: Env, path: string): Promise<Response> {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  if (!(await isDevice(req, env))) return unauthorized();
  const body: unknown = await req.json().catch(() => null);
  if (!body || typeof body !== "object") return json({ error: "invalid json" }, 400);
  if (path === "/phone/register") {
    const reg = Registration.safeParse(body);
    if (!reg.success) return json({ error: "fcm_token required" }, 400);
    await phoneHub(env).register(reg.data);
    return json({ ok: true, push_configured: !!env.FCM_SERVICE_ACCOUNT });
  }
  const id = decodeURIComponent(path.slice(RESULT_PREFIX.length));
  const ok = await phoneHub(env).settle(id, parsePhoneReply(body));
  return ok ? json({ ok: true }) : json({ error: "unknown or expired request id" }, 410);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname, searchParams } = new URL(req.url);
    if (pathname === "/phone/register" || pathname.startsWith(RESULT_PREFIX)) return handlePhone(req, env, pathname);
    if (pathname === "/admin/fcm-selftest") {
      if (!isMcpClient(req, env)) return unauthorized();
      return json(await phoneHub(env).fcmSelfTest(searchParams.get("token") ?? "fake-token-for-selftest"));
    }
    if (pathname === "/mcp") {
      if (!isMcpClient(req, env)) return unauthorized();
      const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      await buildServer(env).connect(transport);
      return transport.handleRequest(req);
    }
    if (pathname === "/") return new Response("health-connect-mcp ok\n");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
