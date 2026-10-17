import { z } from "zod";

export const DEFAULT_TZ = "Europe/Amsterdam";

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

export const iso = z.string().refine((s) => HAS_OFFSET.test(s.trim()) ? !Number.isNaN(Date.parse(s)) : LOCAL_RE.test(s.trim()), "must be YYYY-MM-DD, a local date-time (YYYY-MM-DDTHH:mm[:ss], read in `timezone`) or ISO-8601 with offset");
export const range = {
  start: iso.describe("Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00"),
  end: iso.describe("Exclusive end, same formats as start (a plain date means local midnight starting that day)"),
  timezone: z.string().default(DEFAULT_TZ).describe(`IANA timezone used for day buckets (default ${DEFAULT_TZ})`),
};
export const bucket = z.enum(["none", "hour", "day"]).default("day").describe("Aggregation bucket");

export type RangeArgs = { start: string; end: string; timezone?: string; [k: string]: unknown };

export function zoneOf(args: { timezone?: string }): string {
  const tz = args.timezone ?? DEFAULT_TZ;
  if (!validTz(tz)) throw new Error(`invalid timezone: ${tz}`);
  return tz;
}

export function normalizeRange(args: RangeArgs): RangeArgs {
  const tz = zoneOf(args);
  const out = { ...args, timezone: tz, start: normalizeTime(args.start, tz), end: normalizeTime(args.end, tz) };
  if (Date.parse(out.end) <= Date.parse(out.start)) throw new Error("end must be after start");
  return out;
}
