import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { FcmError, mintAccessToken, sendData, type ServiceAccount } from "./fcm";

export interface Env {
  PHONE: DurableObjectNamespace<PhoneHub>;
  DEVICE_TOKEN_HASH: string; // hex sha256 of the device token
  MCP_TOKEN: string;
  PHONE_TIMEOUT_MS?: string;
  PUSH_TIMEOUT_MS?: string;
  FCM_SERVICE_ACCOUNT?: string; // service-account JSON
}

const DEFAULT_TZ = "Europe/Amsterdam";
const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

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
  const h = req.headers.get("Authorization") ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(h);
  return m ? m[1].trim() : null;
}
const unauthorized = () =>
  new Response(JSON.stringify({ error: "unauthorized" }), { status: 401, headers: { "content-type": "application/json", "WWW-Authenticate": "Bearer" } });

export class PhoneError extends Error {}

/** One instance ("phone") per user. Holds the phone's hibernatable WebSocket. */
export class PhoneHub extends DurableObject<Env> {
  private pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout>; via: "ws" | "push"; sentAt: number }>();
  private oauth?: { token: string; expiresAt: number };

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Answered by the runtime without waking the DO.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"type":"ping"}', '{"type":"pong"}'));
  }

  async fetch(req: Request): Promise<Response> {
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("expected websocket", { status: 426 });
    // Only one live phone socket: replace older ones.
    for (const old of this.ctx.getWebSockets("phone")) {
      try { old.close(4000, "replaced by new connection"); } catch {}
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], ["phone"]);
    await this.ctx.storage.put("lastSeen", Date.now());
    await this.ctx.storage.put("connectedAt", Date.now());
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer): Promise<void> {
    await this.ctx.storage.put("lastSeen", Date.now());
    let data: any;
    try { data = JSON.parse(typeof msg === "string" ? msg : new TextDecoder().decode(msg)); } catch { return; }
    if (data?.type === "hello") {
      await this.ctx.storage.put("device", { app_version: data.app_version, device: data.device, permissions: data.permissions, ts: Date.now() });
      return;
    }
    if (typeof data?.id !== "string") return;
    await this.settle(data.id, data);
  }

  /** Resolve a pending request from either transport. Returns false if unknown/expired. */
  async settle(id: string, data: any): Promise<boolean> {
    const p = this.pending.get(id);
    if (!p) return false;
    this.pending.delete(id);
    clearTimeout(p.timer);
    const latency = Date.now() - p.sentAt;
    await this.ctx.storage.put("lastSeen", Date.now());
    if (p.via === "push") await this.ctx.storage.put("lastPush", { latency_ms: latency, at: Date.now() });
    if (data?.error) p.reject(new PhoneError(`phone error ${data.error.code ?? ""}: ${data.error.message ?? "unknown"}`.trim()));
    else p.resolve(data?.result);
    return true;
  }

  /** Phone registers/refreshes its FCM token (authenticated by the Worker). */
  async register(info: { fcm_token: string; app_version?: string; device?: string; mode?: string }): Promise<void> {
    await this.ctx.storage.put("fcm", { token: info.fcm_token, registered_at: Date.now() });
    await this.ctx.storage.put("device", { app_version: info.app_version, device: info.device, mode: info.mode, ts: Date.now() });
    await this.ctx.storage.put("lastSeen", Date.now());
  }

  private async accessToken(sa: ServiceAccount): Promise<string> {
    if (this.oauth && this.oauth.expiresAt - Date.now() > 120_000) return this.oauth.token;
    const stored = await this.ctx.storage.get<{ token: string; expiresAt: number }>("oauth");
    if (stored && stored.expiresAt - Date.now() > 120_000) return (this.oauth = stored).token;
    this.oauth = await mintAccessToken(sa);
    await this.ctx.storage.put("oauth", this.oauth);
    return this.oauth.token;
  }

  /** Diagnostic: mint an OAuth token and send to an arbitrary FCM token (used by tests with a fake token). */
  async fcmSelfTest(fakeToken: string): Promise<Record<string, unknown>> {
    const sa = this.serviceAccount();
    if (!sa) return { ok: false, error: "FCM_SERVICE_ACCOUNT not configured" };
    const out: Record<string, unknown> = {};
    try { const t = await this.accessToken(sa); out.oauth = `ok (${t.length} chars, expires ${new Date(this.oauth!.expiresAt).toISOString()})`; }
    catch (e: any) { return { ok: false, oauth: String(e.message) }; }
    try { out.send = await sendData(sa, this.oauth!.token, fakeToken, { type: "selftest" }, 10); }
    catch (e: any) { out.send_error = String(e.message); out.code = e.code; }
    return out;
  }

  private serviceAccount(): ServiceAccount | undefined {
    if (!this.env.FCM_SERVICE_ACCOUNT) return undefined;
    try { return JSON.parse(this.env.FCM_SERVICE_ACCOUNT); } catch { return undefined; }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    await this.ctx.storage.put("lastSeen", Date.now());
    try { ws.close(code, reason); } catch {}
  }
  async webSocketError(ws: WebSocket): Promise<void> {
    await this.ctx.storage.put("lastSeen", Date.now());
  }

  private liveSocket(): WebSocket | undefined {
    return this.ctx.getWebSockets("phone").find((w) => w.readyState === WebSocket.OPEN);
  }

  private async lastSeenMs(): Promise<number | undefined> {
    let t = (await this.ctx.storage.get<number>("lastSeen")) ?? undefined;
    for (const w of this.ctx.getWebSockets("phone")) {
      const a = this.ctx.getWebSocketAutoResponseTimestamp(w)?.getTime();
      if (a && (!t || a > t)) t = a;
    }
    return t;
  }

  async status(): Promise<Record<string, unknown>> {
    const ls = await this.lastSeenMs();
    const fcm = await this.ctx.storage.get<{ token: string; registered_at: number }>("fcm");
    const live = !!this.liveSocket();
    return {
      mode: live ? "websocket" : fcm ? "push" : "none",
      online: live,
      push_registered: !!fcm,
      push_registered_at: fcm ? new Date(fcm.registered_at).toISOString() : null,
      last_push: ((p) => (p ? { latency_ms: p.latency_ms, at: new Date(p.at).toISOString() } : null))(await this.ctx.storage.get<{ latency_ms: number; at: number }>("lastPush")),
      last_seen: ls ? new Date(ls).toISOString() : null,
      connected_at: ((c) => (c ? new Date(c).toISOString() : null))(await this.ctx.storage.get<number>("connectedAt")),
      device: (await this.ctx.storage.get("device")) ?? null,
    };
  }

  /** Forward a request to the phone and await its answer. */
  async call(method: string, params: unknown): Promise<unknown> {
    const ws = this.liveSocket();
    const id = crypto.randomUUID();
    const lastSeen = async () => { const ls = await this.lastSeenMs(); return ls ? new Date(ls).toISOString() : "never"; };
    if (ws) {
      const timeoutMs = Number(this.env.PHONE_TIMEOUT_MS ?? 10000);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { this.pending.delete(id); reject(new PhoneError(`phone did not answer within ${timeoutMs / 1000}s (method ${method})`)); }, timeoutMs);
        this.pending.set(id, { resolve, reject, timer, via: "ws", sentAt: Date.now() });
        try { ws.send(JSON.stringify({ id, method, params })); }
        catch (e) { clearTimeout(timer); this.pending.delete(id); reject(new PhoneError(`send to phone failed: ${e}`)); }
      });
    }
    // Push path: wake the phone via FCM; it POSTs the answer to /phone/result/<id>.
    const fcm = await this.ctx.storage.get<{ token: string }>("fcm");
    const sa = this.serviceAccount();
    if (!fcm || !sa) throw new PhoneError(`phone offline (last seen ${await lastSeen()}; ${!fcm ? "no push registration" : "push not configured on server"})`);
    const timeoutMs = Number(this.env.PUSH_TIMEOUT_MS ?? 20000);
    const result = new Promise((resolve, reject) => {
      const timer = setTimeout(async () => {
        this.pending.delete(id);
        reject(new PhoneError(`phone did not answer push within ${timeoutMs / 1000}s (method ${method}; last seen ${await lastSeen()})`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, via: "push", sentAt: Date.now() });
    });
    try {
      // Data only: request id, method, params. Never health data.
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

/** Offset (ms) of `tz` at instant `ms`: local wall time minus UTC. */
function tzOffsetMs(ms: number, tz: string): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).formatToParts(new Date(ms));
  const g = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return Date.UTC(g("year"), g("month") - 1, g("day"), g("hour"), g("minute"), g("second")) - Math.floor(ms / 1000) * 1000;
}
const pad = (n: number) => String(Math.abs(n)).padStart(2, "0");

/**
 * Accepts ISO with offset/Z (returned unchanged), a plain date (YYYY-MM-DD = local midnight) or a local
 * date-time without offset, interpreted in `tz`. Returns ISO-8601 with an explicit offset.
 */
export function normalizeTime(s: string, tz: string): string {
  s = s.trim();
  if (HAS_OFFSET.test(s)) { if (Number.isNaN(Date.parse(s))) throw new Error(`invalid date-time: ${s}`); return s; }
  const m = LOCAL_RE.exec(s);
  if (!m) throw new Error(`invalid date-time: ${s} (use YYYY-MM-DD, YYYY-MM-DDTHH:mm[:ss] or ISO-8601 with offset)`);
  const [y, mo, d, h = "0", mi = "0", se = "0", ms = "0"] = m.slice(1).map((v) => v ?? undefined) as string[];
  const wall = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se, +ms.padEnd(3, "0"));
  let off = tzOffsetMs(wall, tz);
  off = tzOffsetMs(wall - off, tz); // second pass handles DST boundaries
  const oMin = Math.round(off / 60000);
  const sign = oMin < 0 ? "-" : "+";
  return `${y}-${mo}-${d}T${pad(+h)}:${pad(+mi)}:${pad(+se)}${ms !== "0" ? "." + ms.padEnd(3, "0") : ""}${sign}${pad(Math.trunc(Math.abs(oMin) / 60))}:${pad(Math.abs(oMin) % 60)}`;
}
function validTz(tz: string) { try { new Intl.DateTimeFormat("en-US", { timeZone: tz }); return true; } catch { return false; } }

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

function buildServer(env: Env): McpServer {
  const hub = env.PHONE.get(env.PHONE.idFromName("phone"));
  const server = new McpServer({ name: "health-connect", version: "0.1.0" });
  const forward = async (method: string, params: any) => {
    try {
      if (params && typeof params.start === "string") {
        const tz = params.timezone ?? DEFAULT_TZ;
        if (!validTz(tz)) throw new Error(`invalid timezone: ${tz}`);
        params = { ...params, timezone: tz, start: normalizeTime(params.start, tz), end: normalizeTime(params.end, tz) };
        if (Date.parse(params.end) <= Date.parse(params.start)) throw new Error("end must be after start");
      }
      const result = await hub.call(method, params);
      return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
    } catch (e: any) {
      return { isError: true, content: [{ type: "text" as const, text: String(e?.message ?? e) }] };
    }
  };
  const tool = (name: string, description: string, shape: z.ZodRawShape) =>
    server.registerTool(name, { description, inputSchema: shape }, async (args: any) => forward(name, args));

  tool("get_steps", "Step counts from Health Connect, aggregated per bucket (live from the phone).", { ...range, bucket });
  tool("get_sleep", "Sleep sessions with stages and durations overlapping the range.", range);
  tool("get_heart_rate", "Heart rate: min/avg/max per bucket, plus resting HR when available.", { ...range, bucket });
  tool("get_exercise_sessions", "Exercise/workout sessions (type, duration, calories, distance, avg HR).", range);
  tool("get_weight", "Weight measurements (kg) in the range.", range);
  tool("get_nutrition", "Nutrition entries and per-day totals (energy, macros).", range);
  tool("read_records", "Generic raw read of any Health Connect record type in a time range.", {
    recordType: z.enum(RECORD_TYPES).describe("Health Connect record type name"),
    ...range,
    limit: z.number().int().min(1).max(1000).default(200),
  });
  server.registerTool("get_phone_status", { description: "Whether the phone is connected, last-seen time, app/permission info.", inputSchema: {} }, async () => {
    const s: Record<string, unknown> = await hub.status();
    if (s.online || s.push_registered) {
      try { s.phone = await hub.call("get_phone_status", {}); } catch (e: any) { s.phone_error = String(e?.message ?? e); }
    }
    return { content: [{ type: "text" as const, text: JSON.stringify(s, null, 2) }] };
  });
  return server;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/phone/register" || url.pathname.startsWith("/phone/result/")) {
      if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
      const t = bearer(req);
      if (!t || !env.DEVICE_TOKEN_HASH || !safeEqual(await sha256Hex(t), env.DEVICE_TOKEN_HASH.toLowerCase())) return unauthorized();
      const body: any = await req.json().catch(() => null);
      if (!body || typeof body !== "object") return json({ error: "invalid json" }, 400);
      const hub = env.PHONE.get(env.PHONE.idFromName("phone"));
      if (url.pathname === "/phone/register") {
        if (typeof body.fcm_token !== "string" || body.fcm_token.length < 20) return json({ error: "fcm_token required" }, 400);
        await hub.register({ fcm_token: body.fcm_token, app_version: body.app_version, device: body.device, mode: body.mode });
        return json({ ok: true, push_configured: !!env.FCM_SERVICE_ACCOUNT });
      }
      const id = decodeURIComponent(url.pathname.slice("/phone/result/".length));
      const ok = await hub.settle(id, body);
      return ok ? json({ ok: true }) : json({ error: "unknown or expired request id" }, 410);
    }
    if (url.pathname === "/admin/fcm-selftest") {
      const t = bearer(req);
      if (!t || !env.MCP_TOKEN || !safeEqual(t, env.MCP_TOKEN)) return unauthorized();
      return json(await env.PHONE.get(env.PHONE.idFromName("phone")).fcmSelfTest(url.searchParams.get("token") ?? "fake-token-for-selftest"));
    }
    if (url.pathname === "/phone/connect") {
      const t = bearer(req);
      if (!t || !env.DEVICE_TOKEN_HASH || !safeEqual(await sha256Hex(t), env.DEVICE_TOKEN_HASH.toLowerCase())) return unauthorized();
      return env.PHONE.get(env.PHONE.idFromName("phone")).fetch(req);
    }
    if (url.pathname === "/mcp") {
      const t = bearer(req);
      if (!t || !env.MCP_TOKEN || !safeEqual(t, env.MCP_TOKEN)) return unauthorized();
      const server = buildServer(env);
      const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      await server.connect(transport);
      return transport.handleRequest(req);
    }
    if (url.pathname === "/") return new Response("health-connect-mcp ok\n");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
