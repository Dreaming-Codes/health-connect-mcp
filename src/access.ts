import { DurableObject } from "cloudflare:workers";
import { b64url } from "./fcm";

export const CODE_TTL_MS = 10 * 60_000;

export type TokenKind = "device" | "connector";
type ParsedToken = { kind: TokenKind; id: string; secret: string };
export type Ticket = { device_id: string; expires: number };

const PREFIX: Record<TokenKind, string> = { device: "hcd", connector: "hco" };
const TOKEN_RE = /^hc([do])_([0-9a-f]{16})_([A-Za-z0-9_-]{43})$/;
const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const hex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
const random = (n: number) => crypto.getRandomValues(new Uint8Array(n));

export async function sha256Hex(s: string): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))));
}

export function safeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a), eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let r = 0;
  for (let i = 0; i < ea.length; i++) r |= ea[i] ^ eb[i];
  return r === 0;
}

export const newId = () => hex(random(8));

export function parseToken(t: string): ParsedToken | null {
  const m = TOKEN_RE.exec(t);
  return m ? { kind: m[1] === "d" ? "device" : "connector", id: m[2], secret: m[3] } : null;
}

export async function mintToken(kind: TokenKind, id: string): Promise<{ token: string; hash: string }> {
  const secret = b64url(random(32));
  return { token: `${PREFIX[kind]}_${id}_${secret}`, hash: await sha256Hex(secret) };
}

export function newCode(): string {
  const s = [...random(8)].map((b) => CROCKFORD[b & 31]).join("");
  return `${s.slice(0, 4)}-${s.slice(4)}`;
}

export function normalizeCode(raw: string): string | null {
  const s = raw.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  return /^[0-9A-HJKMNP-TV-Z]{8}$/.test(s) ? `${s.slice(0, 4)}-${s.slice(4)}` : null;
}

export class CodeTicket extends DurableObject {
  async put(t: Ticket): Promise<void> {
    await this.ctx.storage.put("ticket", t);
    await this.ctx.storage.setAlarm(t.expires);
  }

  async take(): Promise<Ticket | null> {
    const t = await this.ctx.storage.get<Ticket>("ticket");
    if (!t) return null;
    await this.ctx.storage.deleteAll();
    return t.expires > Date.now() ? t : null;
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}

export class Gate extends DurableObject {
  async hit(limit: number, windowMs: number): Promise<boolean> {
    const now = Date.now();
    const w = await this.ctx.storage.get<{ start: number; count: number }>("w");
    const next = w && now - w.start < windowMs ? { start: w.start, count: w.count + 1 } : { start: now, count: 1 };
    await this.ctx.storage.put("w", next);
    return next.count <= limit;
  }
}
