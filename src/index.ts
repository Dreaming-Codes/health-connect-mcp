import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { CODE_TTL_MS, CodeTicket, Gate, mintToken, newCode, newId, normalizeCode, parseToken, sha256Hex, type TokenKind } from "./access";
import { type Env, phoneHub, ticket } from "./env";
import { NotificationEvent, parsePhoneReply, PhoneHub, Registration } from "./phone-hub";
import { buildServer } from "./tools";

export { CodeTicket, Gate, PhoneHub };

const json = (o: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...headers } });
const unauthorized = () => json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });

async function deviceFor(req: Request, env: Env, kind: TokenKind): Promise<string | null> {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "");
  const p = m ? parseToken(m[1].trim()) : null;
  if (p?.kind !== kind) return null;
  return (await phoneHub(env, p.id).authorize(kind, await sha256Hex(p.secret))) ? p.id : null;
}

const RESULT_PREFIX = "/phone/result/";
const ENROLL_PATH = "/enroll";
const CLAIM_PATH = "/claim";
const RATE = { [ENROLL_PATH]: { limit: 10, windowMs: 3600_000 }, [CLAIM_PATH]: { limit: 20, windowMs: 3600_000 } };
const DeviceName = z.string().trim().min(1).max(60);
const EnrollRequest = z.object({ name: DeviceName });

type DeviceRequest = { req: Request; env: Env; deviceId: string; hub: DurableObjectStub<PhoneHub>; body: object };

const PHONE_ROUTES = new Map<string, (r: DeviceRequest) => Promise<Response>>([
  ["/phone/register", async ({ env, deviceId, hub, body }) => {
    const reg = Registration.safeParse(body);
    if (!reg.success) return json({ error: "fcm_token required" }, 400);
    await hub.register(deviceId, reg.data);
    return json({ ok: true, push_configured: !!env.FCM_SERVICE_ACCOUNT, device_id: deviceId });
  }],
  ["/phone/event", async ({ hub, body }) => {
    const ev = NotificationEvent.safeParse(body);
    if (!ev.success) return json({ error: "notification_id, action_id and at required" }, 400);
    await hub.recordEvent(ev.data);
    return json({ ok: true });
  }],
  ["/phone/name", async ({ hub, body }) => {
    const n = DeviceName.safeParse((body as { name?: unknown }).name);
    if (!n.success) return json({ error: "name must be 1-60 characters" }, 400);
    await hub.setName(n.data);
    return json({ ok: true });
  }],
  ["/phone/disconnect", async ({ hub }) => {
    await hub.revoke();
    return json({ ok: true });
  }],
  ["/phone/connector-code", async ({ req, env, deviceId }) => {
    const code = newCode();
    const expires = Date.now() + CODE_TTL_MS;
    await ticket(env, code).put({ device_id: deviceId, expires });
    return json({ code, expires_at: new Date(expires).toISOString(), claim_url: `${new URL(req.url).origin}${CLAIM_PATH}` });
  }],
]);

async function settleResult({ req, hub, body }: DeviceRequest): Promise<Response> {
  const id = decodeURIComponent(new URL(req.url).pathname.slice(RESULT_PREFIX.length));
  const ok = await hub.settle(id, parsePhoneReply(body));
  return ok ? json({ ok: true }) : json({ error: "unknown or expired request id" }, 410);
}

async function handlePhone(req: Request, env: Env, route: (r: DeviceRequest) => Promise<Response>): Promise<Response> {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  const deviceId = await deviceFor(req, env, "device");
  if (!deviceId) return unauthorized();
  const body: unknown = await req.json().catch(() => null);
  if (!body || typeof body !== "object") return json({ error: "invalid json" }, 400);
  return route({ req, env, deviceId, hub: phoneHub(env, deviceId), body });
}

async function allowed(req: Request, env: Env, path: keyof typeof RATE): Promise<boolean> {
  const ip = req.headers.get("CF-Connecting-IP") ?? "local";
  return env.GATE.get(env.GATE.idFromName(`${path}:${ip}`)).hit(RATE[path].limit, RATE[path].windowMs);
}

async function handleEnroll(req: Request, env: Env): Promise<Response> {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  if (!(await allowed(req, env, ENROLL_PATH))) return json({ error: "too many enrollments; try again later" }, 429);
  const body = EnrollRequest.safeParse(await req.json().catch(() => null));
  if (!body.success) return json({ error: "name (1-60 characters) required" }, 400);
  const deviceId = newId();
  const { token, hash } = await mintToken("device", deviceId);
  await phoneHub(env, deviceId).enroll(hash, body.data.name);
  return json({ device_id: deviceId, device_token: token });
}

async function handleClaim(req: Request, env: Env): Promise<Response> {
  if (req.method !== "POST" && req.method !== "GET") return new Response("method not allowed", { status: 405 });
  if (!(await allowed(req, env, CLAIM_PATH))) return json({ error: "too many attempts; try again later" }, 429);
  const raw = req.method === "GET" ? new URL(req.url).searchParams.get("code") : ((await req.json().catch(() => null)) as { code?: unknown } | null)?.code;
  const code = typeof raw === "string" ? normalizeCode(raw) : null;
  const t = code ? await ticket(env, code).take() : null;
  if (!t) return json({ error: "invalid or expired code" }, 400);
  const hub = phoneHub(env, t.device_id);
  const name = `Android Connect (${(await hub.displayName()) ?? "phone"})`;
  const { token, hash } = await mintToken("connector", t.device_id);
  if (!(await hub.setConnector(hash, name))) return json({ error: "invalid or expired code" }, 400);
  return json({ name, mcp_url: `${new URL(req.url).origin}/mcp`, transport: "streamable-http", headers: { Authorization: `Bearer ${token}` } }, 200, { "Cache-Control": "no-store" });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(req.url);
    const phoneRoute = PHONE_ROUTES.get(pathname) ?? (pathname.startsWith(RESULT_PREFIX) ? settleResult : undefined);
    if (phoneRoute) return handlePhone(req, env, phoneRoute);
    if (pathname === ENROLL_PATH) return handleEnroll(req, env);
    if (pathname === CLAIM_PATH) return handleClaim(req, env);
    if (pathname === "/mcp") {
      const deviceId = await deviceFor(req, env, "connector");
      if (!deviceId) return unauthorized();
      const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      await buildServer(phoneHub(env, deviceId)).connect(transport);
      return transport.handleRequest(req);
    }
    if (pathname === "/") return new Response("android-connect ok\n");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
