import { DurableObject } from "cloudflare:workers";
import { z } from "zod";
import { safeEqual } from "./access";
import type { Env } from "./env";
import { FcmError, mintAccessToken, sendData, type ServiceAccount } from "./fcm";

const OAUTH_REFRESH_MARGIN_MS = 120_000;
const isoOrNull = (ms: number | undefined) => (ms ? new Date(ms).toISOString() : null);

export class PhoneError extends Error {}

export const Registration = z.object({
  fcm_token: z.string().min(20),
  app_version: z.unknown(),
  device: z.unknown(),
  mode: z.unknown(),
});
export type Registration = z.infer<typeof Registration>;

export const NotificationEvent = z.object({
  notification_id: z.string().min(1).max(64),
  action_id: z.string().min(1).max(32),
  reply_text: z.string().max(4000).optional(),
  at: z.string().refine((v) => !Number.isNaN(Date.parse(v)), "ISO-8601 time"),
});
export type NotificationEvent = z.infer<typeof NotificationEvent>;
type StoredEvent = NotificationEvent & { received_at: string };
type ResponseFilter = { notification_id?: string; since?: string };

const EVENT_PREFIX = "event:";
const EVENT_TTL_MS = 7 * 24 * 3600_000;
const eventBound = (ms: number) => `${EVENT_PREFIX}${String(ms).padStart(15, "0")}`;

export type PhoneReply = { ok: true; result: unknown } | { ok: false; code?: unknown; message?: unknown };

export function parsePhoneReply(body: Record<string, any>): PhoneReply {
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
