import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { LEGACY_DEVICE, LEGACY_OWNER } from "./access";
import { type Env, ownerHub, phoneHub } from "./env";
import { PhoneError, type PhoneHub } from "./phone-hub";
import { bucket, iso, normalizeRange, normalizeTime, range, zoneOf } from "./time";

const CALL_GRACE_S = 5;

export const RECORD_TYPES = [
  "Steps", "Distance", "ActiveCaloriesBurned", "TotalCaloriesBurned", "FloorsClimbed", "SleepSession", "HeartRate",
  "RestingHeartRate", "HeartRateVariabilityRmssd", "ExerciseSession", "Weight", "Height", "BodyFat", "Nutrition",
  "Hydration", "OxygenSaturation", "RespiratoryRate", "BloodPressure", "BloodGlucose", "BodyTemperature", "Vo2Max", "Speed", "Power",
] as const;

async function devicesOf(env: Env, ownerId: string): Promise<string[]> {
  const linked = await ownerHub(env, ownerId).devices();
  return ownerId === LEGACY_OWNER ? [LEGACY_DEVICE, ...linked] : linked;
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

export async function pickDevice(env: Env, ownerId: string, wanted?: string): Promise<Hub> {
  const all = (await listDevices(env, ownerId)).sort(byRecency);
  const hit = wanted === undefined ? all[0] : all.find((d) => d.device_id === wanted || d.name?.toLowerCase() === wanted.toLowerCase());
  if (!hit) throw new PhoneError(wanted === undefined ? "no devices enrolled" : `unknown device "${wanted}"; known: ${all.map((d) => d.name ?? d.device_id).join(", ")}`);
  return phoneHub(env, hit.device_id);
}

const device = z.string().min(1).max(64).optional().describe("Device id or name from list_devices; default is the most recently seen device");
const failure = (e: any) => ({ isError: true, content: [{ type: "text" as const, text: String(e?.message ?? e) }] });

export function buildServer(env: Env, ownerId: string): McpServer {
  const server = new McpServer({ name: "android-connect", version: "0.6.0" });
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
