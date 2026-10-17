import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { FcmError, mintAccessToken, sendData, type ServiceAccount } from "./fcm";
import { CODE_TTL_MS, CodeTicket, Gate, LEGACY_DEVICE, LEGACY_OWNER, OwnerHub, mintToken, newCode, newId, normalizeCode, parseToken, safeEqual, sha256Hex } from "./access";

export { CodeTicket, Gate, OwnerHub };

export interface Env {
  PHONE: DurableObjectNamespace<PhoneHub>;
  OWNER: DurableObjectNamespace<OwnerHub>;
  TICKET: DurableObjectNamespace<CodeTicket>;
  GATE: DurableObjectNamespace<Gate>;
  DEVICE_TOKEN_HASH: string;
  MCP_TOKEN: string;
  PUSH_TIMEOUT_MS?: string;
  FCM_SERVICE_ACCOUNT?: string;
  FCM_ENDPOINT?: string;
}

const DEFAULT_TZ = "Europe/Amsterdam";
const OAUTH_REFRESH_MARGIN_MS = 120_000;

const json = (o: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...headers } });
const unauthorized = () => json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });
const isoOrNull = (ms: number | undefined) => (ms ? new Date(ms).toISOString() : null);

function bearer(req: Request): string | null {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "");
  return m ? m[1].trim() : null;
}

const phoneHub = (env: Env, deviceId: string) => env.PHONE.get(env.PHONE.idFromName(deviceId));
const ownerHub = (env: Env, ownerId: string) => env.OWNER.get(env.OWNER.idFromName(ownerId));
const ticket = (env: Env, code: string) => env.TICKET.get(env.TICKET.idFromName(code));

async function deviceOf(req: Request, env: Env): Promise<string | null> {
  const t = bearer(req);
  if (!t) return null;
  const p = parseToken(t);
  if (p?.kind === "device") return (await phoneHub(env, p.id).verify(await sha256Hex(p.secret))) ? p.id : null;
  return env.DEVICE_TOKEN_HASH && safeEqual(await sha256Hex(t), env.DEVICE_TOKEN_HASH.toLowerCase()) ? LEGACY_DEVICE : null;
}

async function ownerOf(req: Request, env: Env): Promise<string | null> {
  const t = bearer(req);
  if (!t) return null;
  const p = parseToken(t);
  if (p?.kind === "owner") return (await ownerHub(env, p.id).verify(await sha256Hex(p.secret))) ? p.id : null;
  return env.MCP_TOKEN && safeEqual(t, env.MCP_TOKEN) ? LEGACY_OWNER : null;
}

const ownerOfDevice = async (env: Env, deviceId: string) => deviceId === LEGACY_DEVICE ? LEGACY_OWNER : (await phoneHub(env, deviceId).ownerId()) ?? LEGACY_OWNER;

async function devicesOf(env: Env, ownerId: string): Promise<string[]> {
  const linked = await ownerHub(env, ownerId).devices();
  return ownerId === LEGACY_OWNER ? [LEGACY_DEVICE, ...linked] : linked;
}

export class PhoneError extends Error {}

const Registration = z.object({
  fcm_token: z.string().min(20),
  app_version: z.unknown(),
  device: z.unknown(),
  mode: z.unknown(),
});
type Registration = z.infer<typeof Registration>;

const NotificationEvent = z.object({
  notification_id: z.string().min(1).max(64),
  action_id: z.string().min(1).max(32),
  reply_text: z.string().max(4000).optional(),
  at: z.string().refine((v) => !Number.isNaN(Date.parse(v)), "ISO-8601 time"),
});
type NotificationEvent = z.infer<typeof NotificationEvent>;
type StoredEvent = NotificationEvent & { received_at: string };
type ResponseFilter = { notification_id?: string; since?: string };

const EVENT_PREFIX = "event:";
const EVENT_TTL_MS = 7 * 24 * 3600_000;
const CALL_GRACE_S = 5;
const eventBound = (ms: number) => `${EVENT_PREFIX}${String(ms).padStart(15, "0")}`;

type PhoneReply = { ok: true; result: unknown } | { ok: false; code?: unknown; message?: unknown };

function parsePhoneReply(body: Record<string, any>): PhoneReply {
  return body.error ? { ok: false, code: body.error.code, message: body.error.message } : { ok: true, result: body.result };
}

type Pending = { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout>; sentAt: number };
type OAuthToken = { token: string; expiresAt: number };
type DeviceAuth = { token_hash: string; owner_id: string };
type DeviceInfo = { app_version?: string; device?: string; mode?: string; ts: number };
type PhoneStatus = {
  name?: string;
  mode: "push" | "none";
  push_registered: boolean;
  push_registered_at: string | null;
  last_push: { latency_ms: number; at: string } | null;
  last_seen: string | null;
  device: DeviceInfo | null;
};

export class PhoneHub extends DurableObject<Env> {
  private pending = new Map<string, Pending>();
  private eventWaiters = new Set<() => void>();
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

  async recordEvent(e: NotificationEvent): Promise<void> {
    const now = Date.now();
    await this.ctx.storage.put(`${eventBound(now)}:${crypto.randomUUID()}`, { ...e, received_at: new Date(now).toISOString() } satisfies StoredEvent);
    await this.pruneEvents(now);
    for (const wake of this.eventWaiters) wake();
  }

  private async pruneEvents(now: number): Promise<void> {
    const expired = await this.ctx.storage.list({ prefix: EVENT_PREFIX, end: eventBound(now - EVENT_TTL_MS) });
    if (expired.size) await this.ctx.storage.delete([...expired.keys()]);
  }

  private async matchingEvents(f: ResponseFilter): Promise<StoredEvent[]> {
    const since = f.since ? Date.parse(f.since) : 0;
    const all = await this.ctx.storage.list<StoredEvent>({ prefix: EVENT_PREFIX, start: eventBound(Math.max(since, Date.now() - EVENT_TTL_MS)) });
    return [...all.values()].filter((e) => (!f.notification_id || e.notification_id === f.notification_id) && Date.parse(e.received_at) >= since);
  }

  async responses(f: ResponseFilter, waitMs: number): Promise<StoredEvent[]> {
    const found = await this.matchingEvents(f);
    if (found.length || waitMs <= 0) return found;
    await new Promise<void>((resolve) => {
      const done = () => { clearTimeout(timer); this.eventWaiters.delete(done); resolve(); };
      const timer = setTimeout(done, waitMs);
      this.eventWaiters.add(done);
    });
    return this.matchingEvents(f);
  }

  async updateDevice(info: { app_version?: unknown; device?: unknown }): Promise<DeviceInfo> {
    const prev = (await this.ctx.storage.get<DeviceInfo>("device")) ?? {};
    const fresh = Object.fromEntries(Object.entries(info).filter(([, v]) => typeof v === "string" && v));
    const next = { ...prev, ...fresh, ts: Date.now() };
    await this.ctx.storage.put("device", next);
    return next;
  }

  async enroll(a: DeviceAuth, name: string): Promise<void> {
    await this.ctx.storage.put("auth", a);
    await this.ctx.storage.put("name", name);
  }

  async verify(hash: string): Promise<boolean> {
    const a = await this.ctx.storage.get<DeviceAuth>("auth");
    return !!a && safeEqual(a.token_hash, hash);
  }

  async ownerId(): Promise<string | undefined> {
    return (await this.ctx.storage.get<DeviceAuth>("auth"))?.owner_id;
  }

  async setName(name: string): Promise<void> {
    await this.ctx.storage.put("name", name);
  }

  async register(info: Registration): Promise<void> {
    await this.ctx.storage.put("fcm", { token: info.fcm_token, registered_at: Date.now() });
    const str = (v: unknown) => (typeof v === "string" && v ? v.slice(0, 100) : undefined);
    await this.ctx.storage.put("device", { app_version: str(info.app_version), device: str(info.device), mode: str(info.mode), ts: Date.now() } satisfies DeviceInfo);
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
      out.send = await sendData(sa, this.oauth!.token, fakeToken, { type: "selftest" }, 10, this.env.FCM_ENDPOINT);
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

  async status(): Promise<PhoneStatus> {
    const fcm = await this.ctx.storage.get<{ token: string; registered_at: number }>("fcm");
    const lastPush = await this.ctx.storage.get<{ latency_ms: number; at: number }>("lastPush");
    const name = await this.ctx.storage.get<string>("name");
    return {
      ...(name !== undefined && { name }),
      mode: fcm ? "push" : "none",
      push_registered: !!fcm,
      push_registered_at: isoOrNull(fcm?.registered_at),
      last_push: lastPush ? { latency_ms: lastPush.latency_ms, at: new Date(lastPush.at).toISOString() } : null,
      last_seen: isoOrNull(await this.lastSeen()),
      device: (await this.ctx.storage.get<DeviceInfo>("device")) ?? null,
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
      await sendData(sa, await this.accessToken(sa), fcm.token, { id, method, params: JSON.stringify(params ?? {}) }, Math.ceil(timeoutMs / 1000), this.env.FCM_ENDPOINT);
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

type RangeArgs = { start: string; end: string; timezone?: string; [k: string]: unknown };

function zoneOf(args: { timezone?: string }): string {
  const tz = args.timezone ?? DEFAULT_TZ;
  if (!validTz(tz)) throw new Error(`invalid timezone: ${tz}`);
  return tz;
}

function normalizeRange(args: RangeArgs): RangeArgs {
  const tz = zoneOf(args);
  const out = { ...args, timezone: tz, start: normalizeTime(args.start, tz), end: normalizeTime(args.end, tz) };
  if (Date.parse(out.end) <= Date.parse(out.start)) throw new Error("end must be after start");
  return out;
}

type ListNotificationsArgs = { package?: string; since?: string; limit: number; timezone?: string };

function normalizeListNotifications(args: ListNotificationsArgs): ListNotificationsArgs {
  const tz = zoneOf(args);
  return { ...args, timezone: tz, ...(args.since !== undefined && { since: normalizeTime(args.since, tz) }) };
}

type ActionArgs = { key: string; action: number | "content"; replyText?: string };

function checkAction(args: ActionArgs): ActionArgs {
  if (args.action === "content" && args.replyText !== undefined) throw new Error("replyText only applies to a reply action index");
  return args;
}

type MessageQueryArgs = { since?: string; until?: string; timezone?: string; [k: string]: unknown };

function normalizeMessageQuery(args: MessageQueryArgs): MessageQueryArgs {
  const tz = zoneOf(args);
  const out = { ...args, timezone: tz, ...(args.since !== undefined && { since: normalizeTime(args.since, tz) }), ...(args.until !== undefined && { until: normalizeTime(args.until, tz) }) };
  if (out.since && out.until && Date.parse(out.until) <= Date.parse(out.since)) throw new Error("until must be after since");
  return out;
}

type PhoneTool = { name: string; description: string; shape: z.ZodRawShape; params: (args: any) => unknown };

const healthTool = (name: string, description: string, shape: z.ZodRawShape): PhoneTool => ({ name, description, shape, params: normalizeRange });
const passThrough = (args: unknown) => args;
const smsText = z.string().min(1).max(5000).describe("Message text; long text is sent as a multipart SMS");
const SMS_ONLY = "Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.";
const notificationKey = z.string().min(1).describe("Notification key from list_notifications");

const PHONE_TOOLS: PhoneTool[] = [
  healthTool("get_steps", "Step counts from Health Connect, aggregated per bucket (live from the phone).", { ...range, bucket }),
  healthTool("get_sleep", "Sleep sessions with stages and durations overlapping the range.", range),
  healthTool("get_heart_rate", "Heart rate: min/avg/max per bucket, plus resting HR when available.", { ...range, bucket }),
  healthTool("get_exercise_sessions", "Exercise/workout sessions (type, duration, calories, distance, avg HR).", range),
  healthTool("get_weight", "Weight measurements (kg) in the range.", range),
  healthTool("get_nutrition", "Nutrition entries and per-day totals (energy, macros).", range),
  healthTool("read_records", "Generic raw read of any Health Connect record type in a time range.", {
    recordType: z.enum(RECORD_TYPES).describe("Health Connect record type name"),
    ...range,
    limit: z.number().int().min(1).max(1000).default(200),
  }),
  {
    name: "list_notifications",
    description: "Active notifications on the phone, newest first: app, title, text, and the action buttons each one offers (reply actions included).",
    shape: {
      package: z.string().min(1).optional().describe("Only notifications from this Android package, e.g. com.whatsapp"),
      since: iso.optional().describe("Only notifications posted at or after this time, same formats as start"),
      limit: z.number().int().min(1).max(200).default(50),
      timezone: range.timezone,
    },
    params: normalizeListNotifications,
  },
  {
    name: "dismiss_notification",
    description: "Dismiss one notification, as if the user swiped it away. Ongoing or non-clearable notifications are refused.",
    shape: { key: notificationKey },
    params: passThrough,
  },
  {
    name: "perform_notification_action",
    description: "Tap a notification's action button by index, or its body with action \"content\". Reply actions need replyText, which is sent as the typed reply.",
    shape: {
      key: notificationKey,
      action: z.union([z.number().int().min(0), z.literal("content")]).describe("Action index from list_notifications, or \"content\" to tap the notification itself"),
      replyText: z.string().min(1).max(4000).optional().describe("Reply text, required for and only allowed with a reply action"),
    },
    params: checkAction,
  },
  {
    name: "send_notification",
    description: "Show a notification on the phone from the assistant, optionally with buttons and reply fields. Taps and replies are collected by get_notification_responses. Reusing a tag replaces that notification. With alarm, it rings as an alarm (audible in silent and vibrate mode, and through Do Not Disturb when alarms are allowed or the app has Do Not Disturb access) until dismiss_notification or the user dismisses it.",
    shape: {
      title: z.string().min(1).max(200),
      text: z.string().max(2000).optional(),
      channel: z.string().min(1).max(40).default("Assistant").describe("Channel name shown in Android settings. Android fixes a channel's sound and importance when it is first created, so each importance/silent combination gets its own channel"),
      importance: z.enum(["min", "low", "default", "high"]).default("default"),
      silent: z.boolean().default(false).describe("No sound or vibration"),
      alarm: z.boolean().default(false).describe("Ring with the default alarm sound on the alarm stream, like a find-my-phone ring. Overrides channel, importance and silent"),
      insistent: z.boolean().default(true).describe("With alarm, loop the sound until the notification is dismissed"),
      vibrate: z.boolean().default(true).describe("With alarm, vibrate as well"),
      tag: z.string().regex(/^[A-Za-z0-9_.-]{1,64}$/).optional().describe("Stable id; sending again with the same tag replaces the notification"),
      actions: z.array(z.object({
        id: z.string().regex(/^[a-z0-9_-]{1,32}$/).refine((v) => v !== "content", "content is reserved for taps on the notification body"),
        label: z.string().min(1).max(40),
        reply: z.object({ placeholder: z.string().max(60).optional() }).optional().describe("Make this a text reply action"),
      })).max(3).default([]).refine((a) => new Set(a.map((x) => x.id)).size === a.length, "action ids must be unique"),
    },
    params: passThrough,
  },
  {
    name: "get_location",
    description: "The phone's current location (GPS/network fix), or the last known fix if a fresh one is not available in time.",
    shape: {
      max_age_s: z.number().int().min(0).max(86400).default(120).describe("Accept a cached fix up to this old instead of waiting for a new one"),
      timeout_s: z.number().int().min(1).max(12).default(10),
    },
    params: passThrough,
  },
  {
    name: "list_threads",
    description: `SMS/MMS conversations, newest first, with the other party's address and the last message snippet. ${SMS_ONLY}`,
    shape: { limit: z.number().int().min(1).max(100).default(20), timezone: range.timezone },
    params: normalizeMessageQuery,
  },
  {
    name: "list_messages",
    description: `SMS and MMS messages, newest first. Contact names are added when contacts permission is granted. MMS returns text parts only. ${SMS_ONLY}`,
    shape: {
      thread_id: z.number().int().min(1).optional(),
      address: z.string().min(3).max(40).optional().describe("Only messages with this phone number (matched on trailing digits)"),
      since: iso.optional(),
      until: iso.optional(),
      box: z.enum(["all", "inbox", "sent"]).default("all"),
      limit: z.number().int().min(1).max(500).default(50),
      timezone: range.timezone,
    },
    params: normalizeMessageQuery,
  },
  {
    name: "send_message",
    description: `Send an SMS from the phone's default SIM and report whether it was sent and delivered. ${SMS_ONLY}`,
    shape: { to: z.string().regex(/^\+?[0-9][0-9 ()-]{2,24}$/, "phone number"), text: smsText },
    params: passThrough,
  },
  {
    name: "reply_to_thread",
    description: `Send an SMS to the other party of a one-to-one thread from list_threads. Group threads are refused. ${SMS_ONLY}`,
    shape: { thread_id: z.number().int().min(1), text: smsText },
    params: passThrough,
  },
  {
    name: "search_contacts",
    description: "Find contacts by name or phone number. Returns names, phone numbers with their type, and email addresses.",
    shape: { query: z.string().min(1).max(100), limit: z.number().int().min(1).max(50).default(10) },
    params: passThrough,
  },
];

const text = (o: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(o, null, 2) }] });

type DeviceSummary = { device_id: string; name: string | null; app_version: string | null; last_seen: string | null; push_registered: boolean };
type Hub = DurableObjectStub<PhoneHub>;

async function listDevices(env: Env, ownerId: string): Promise<DeviceSummary[]> {
  return Promise.all((await devicesOf(env, ownerId)).map(async (id) => {
    const s = await phoneHub(env, id).status();
    return { device_id: id, name: s.name ?? s.device?.device ?? null, app_version: s.device?.app_version ?? null, last_seen: s.last_seen, push_registered: s.push_registered };
  }));
}

const byRecency = (a: DeviceSummary, b: DeviceSummary) => (b.last_seen ?? "").localeCompare(a.last_seen ?? "");

async function pickDevice(env: Env, ownerId: string, wanted?: string): Promise<Hub> {
  const all = (await listDevices(env, ownerId)).sort(byRecency);
  const hit = wanted === undefined ? all[0] : all.find((d) => d.device_id === wanted || d.name?.toLowerCase() === wanted.toLowerCase());
  if (!hit) throw new PhoneError(wanted === undefined ? "no devices enrolled" : `unknown device "${wanted}"; known: ${all.map((d) => d.name ?? d.device_id).join(", ")}`);
  return phoneHub(env, hit.device_id);
}

const device = z.string().min(1).max(64).optional().describe("Device id or name from list_devices; default is the most recently seen device");
const failure = (e: any) => ({ isError: true, content: [{ type: "text" as const, text: String(e?.message ?? e) }] });

function buildServer(env: Env, ownerId: string): McpServer {
  const server = new McpServer({ name: "health-connect", version: "0.2.0" });
  const tool = <A,>(name: string, description: string, shape: z.ZodRawShape, run: (hub: Hub, args: A) => Promise<unknown>) =>
    server.registerTool(name, { description, inputSchema: { ...shape, device } }, async ({ device: wanted, ...args }: any) => {
      try {
        return text(await run(await pickDevice(env, ownerId, wanted), args));
      } catch (e) {
        return failure(e);
      }
    });
  for (const { name, description, shape, params } of PHONE_TOOLS) tool(name, description, shape, (hub, args) => hub.call(name, params(args)));
  tool<{ notification_id?: string; since?: string; wait_seconds: number; timezone?: string }>("get_notification_responses",
    "Taps, button presses and replies on notifications sent with send_notification, oldest first. Kept for 7 days. Set wait_seconds to wait for the first response.", {
      notification_id: z.string().min(1).max(64).optional().describe("The tag returned by send_notification"),
      since: iso.optional(),
      wait_seconds: z.number().int().min(0).max(20).default(0),
      timezone: range.timezone,
    }, async (hub, args) => {
      const since = args.since === undefined ? undefined : normalizeTime(args.since, zoneOf(args));
      return { responses: await hub.responses({ notification_id: args.notification_id, since }, args.wait_seconds * 1000) };
    });
  tool<{ agent_id: string; caller_name: string; reason: string; ring_seconds: number }>("start_call",
    "Ring the phone like an incoming call from the assistant, with a full-screen call screen and Answer/Decline. Answer unlocks the phone and opens a Grok Bot voice call with agent_id. Waits until the call is answered, declined or missed (ring_seconds plus 5 s at most).", {
      agent_id: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/).describe("Grok Bot agent id the voice call opens"),
      caller_name: z.string().min(1).max(60).default("Grok Bot"),
      reason: z.string().min(1).max(140).describe("Why you are calling, shown under the caller name"),
      ring_seconds: z.number().int().min(5).max(60).default(30),
    }, async (hub, args) => {
      const call_id = `call-${crypto.randomUUID().slice(0, 12)}`;
      const ring = (await hub.call("start_call", { ...args, call_id })) as Record<string, unknown>;
      const [first] = await hub.responses({ notification_id: call_id }, (args.ring_seconds + CALL_GRACE_S) * 1000);
      if (first) return { ...ring, call_id, outcome: first.action_id, at: first.at };
      await hub.recordEvent({ notification_id: call_id, action_id: "missed", at: new Date().toISOString() });
      return { ...ring, call_id, outcome: "missed" };
    });
  tool("get_phone_status", "Whether the phone is connected, last-seen time, app/permission info.", {}, async (hub) => {
    const s: Record<string, unknown> = { ...(await hub.status()) };
    if (s.push_registered) {
      try {
        const phone: any = await hub.call("get_phone_status", {});
        s.phone = phone;
        // Registration-time device info goes stale after app updates; the live answer wins.
        if (phone && typeof phone === "object") s.device = await hub.updateDevice({ app_version: phone.app_version, device: phone.device });
      } catch (e: any) { s.phone_error = String(e?.message ?? e); }
    }
    return s;
  });
  server.registerTool("list_devices", { description: "Phones this connector can reach. Pass a device_id or name as `device` to any tool; the default is the most recently seen device.", inputSchema: {} }, async () => {
    try {
      const all = (await listDevices(env, ownerId)).sort(byRecency);
      return text({ devices: all.map((d, i) => ({ ...d, default: i === 0 })) });
    } catch (e) {
      return failure(e);
    }
  });
  return server;
}

const RESULT_PREFIX = "/phone/result/";
const EVENT_PATH = "/phone/event";

async function handlePhone(req: Request, env: Env, path: string): Promise<Response> {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  const deviceId = await deviceOf(req, env);
  if (!deviceId) return unauthorized();
  const hub = phoneHub(env, deviceId);
  const body: unknown = await req.json().catch(() => null);
  if (!body || typeof body !== "object") return json({ error: "invalid json" }, 400);
  if (path === "/phone/register") {
    const reg = Registration.safeParse(body);
    if (!reg.success) return json({ error: "fcm_token required" }, 400);
    await hub.register(reg.data);
    return json({ ok: true, push_configured: !!env.FCM_SERVICE_ACCOUNT, device_id: deviceId });
  }
  if (path === EVENT_PATH) {
    const ev = NotificationEvent.safeParse(body);
    if (!ev.success) return json({ error: "notification_id, action_id and at required" }, 400);
    await hub.recordEvent(ev.data);
    return json({ ok: true });
  }
  if (path === "/phone/name") {
    const n = DeviceName.safeParse((body as { name?: unknown }).name);
    if (!n.success) return json({ error: "name must be 1-60 characters" }, 400);
    await hub.setName(n.data);
    return json({ ok: true });
  }
  if (path === "/phone/connector-code" || path === "/phone/pairing-code") {
    const kind = path === "/phone/pairing-code" ? "pair" : "claim";
    const code = newCode();
    const expires = Date.now() + CODE_TTL_MS;
    await ticket(env, code).put({ kind, owner_id: await ownerOfDevice(env, deviceId), device_id: deviceId, expires });
    return json({ code, expires_at: new Date(expires).toISOString(), ...(kind === "claim" && { claim_url: `${new URL(req.url).origin}${CLAIM_PATH}` }) });
  }
  const id = decodeURIComponent(path.slice(RESULT_PREFIX.length));
  const ok = await hub.settle(id, parsePhoneReply(body));
  return ok ? json({ ok: true }) : json({ error: "unknown or expired request id" }, 410);
}

const DeviceName = z.string().trim().min(1).max(60);
const EnrollRequest = z.object({ name: DeviceName, pairing_code: z.string().max(20).optional() });
const ENROLL_PATH = "/enroll";
const CLAIM_PATH = "/claim";
const RATE = { [ENROLL_PATH]: { limit: 10, windowMs: 3600_000 }, [CLAIM_PATH]: { limit: 20, windowMs: 3600_000 } };
const PHONE_PATHS = new Set(["/phone/register", EVENT_PATH, "/phone/name", "/phone/connector-code", "/phone/pairing-code"]);

async function allowed(req: Request, env: Env, path: keyof typeof RATE): Promise<boolean> {
  const ip = req.headers.get("CF-Connecting-IP") ?? "local";
  return env.GATE.get(env.GATE.idFromName(`${path}:${ip}`)).hit(RATE[path].limit, RATE[path].windowMs);
}

async function handleEnroll(req: Request, env: Env): Promise<Response> {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  if (!(await allowed(req, env, ENROLL_PATH))) return json({ error: "too many enrollments; try again later" }, 429);
  const body = EnrollRequest.safeParse(await req.json().catch(() => null));
  if (!body.success) return json({ error: "name (1-60 characters) required" }, 400);
  let ownerId: string | null = null;
  if (req.headers.has("Authorization")) {
    ownerId = await ownerOf(req, env);
    if (!ownerId) return unauthorized();
  } else if (body.data.pairing_code !== undefined) {
    const code = normalizeCode(body.data.pairing_code);
    ownerId = code ? (await ticket(env, code).take("pair"))?.owner_id ?? null : null;
    if (!ownerId) return json({ error: "invalid or expired pairing code" }, 400);
  }
  const linked = ownerId !== null;
  const owner = ownerId ?? newId();
  const deviceId = newId();
  const { token, hash } = await mintToken("device", deviceId);
  await phoneHub(env, deviceId).enroll({ token_hash: hash, owner_id: owner }, body.data.name);
  await ownerHub(env, owner).addDevice(deviceId);
  return json({ device_id: deviceId, device_token: token, owner_id: owner, linked });
}

async function handleClaim(req: Request, env: Env): Promise<Response> {
  if (req.method !== "POST" && req.method !== "GET") return new Response("method not allowed", { status: 405 });
  if (!(await allowed(req, env, CLAIM_PATH))) return json({ error: "too many attempts; try again later" }, 429);
  const raw = req.method === "GET" ? new URL(req.url).searchParams.get("code") : ((await req.json().catch(() => null)) as { code?: unknown } | null)?.code;
  const code = typeof raw === "string" ? normalizeCode(raw) : null;
  const t = code ? await ticket(env, code).take("claim") : null;
  if (!t) return json({ error: "invalid or expired code" }, 400);
  const s = await phoneHub(env, t.device_id).status();
  const name = `Android Connect (${s.name ?? s.device?.device ?? "phone"})`;
  const { token, hash } = await mintToken("owner", t.owner_id);
  await ownerHub(env, t.owner_id).addToken(hash, name);
  return json({ name, mcp_url: `${new URL(req.url).origin}/mcp`, transport: "streamable-http", headers: { Authorization: `Bearer ${token}` } }, 200, { "Cache-Control": "no-store" });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname, searchParams } = new URL(req.url);
    if (PHONE_PATHS.has(pathname) || pathname.startsWith(RESULT_PREFIX)) return handlePhone(req, env, pathname);
    if (pathname === ENROLL_PATH) return handleEnroll(req, env);
    if (pathname === CLAIM_PATH) return handleClaim(req, env);
    if (pathname === "/admin/fcm-selftest") {
      const ownerId = await ownerOf(req, env);
      if (!ownerId) return unauthorized();
      return json(await (await pickDevice(env, ownerId)).fcmSelfTest(searchParams.get("token") ?? "fake-token-for-selftest"));
    }
    if (pathname === "/mcp") {
      const ownerId = await ownerOf(req, env);
      if (!ownerId) return unauthorized();
      const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      await buildServer(env, ownerId).connect(transport);
      return transport.handleRequest(req);
    }
    if (pathname === "/") return new Response("health-connect-mcp ok\n");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
