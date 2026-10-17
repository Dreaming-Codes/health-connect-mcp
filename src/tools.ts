import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { ShapeOutput } from "@modelcontextprotocol/sdk/server/zod-compat.js";
import { z } from "zod";
import type { PhoneHub, StoredEvent } from "./phone-hub";
import { bucket, formatIn, inZone, iso, range, timezone } from "./time";

const CALL_GRACE_S = 5;

const RECORD_TYPES = [
  "Steps", "Distance", "ActiveCaloriesBurned", "TotalCaloriesBurned", "FloorsClimbed", "SleepSession", "HeartRate",
  "RestingHeartRate", "HeartRateVariabilityRmssd", "ExerciseSession", "Weight", "Height", "BodyFat", "Nutrition",
  "Hydration", "OxygenSaturation", "RespiratoryRate", "BloodPressure", "BloodGlucose", "BodyTemperature", "Vo2Max", "Speed", "Power",
] as const;

type Hub = DurableObjectStub<PhoneHub>;

const smsText = z.string().min(1).max(5000).describe("Message text; long text is sent as a multipart SMS");
const SMS_ONLY = "Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.";
const notificationKey = z.string().min(1).describe("Notification key from list_notifications");
const notificationTag = z.string().regex(/^[A-Za-z0-9_.-]{1,64}$/);

const text = (o: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(o, null, 2) }] });
const response = ({ at, received_at, ...e }: StoredEvent, tz: string) =>
  ({ ...e, phone_time: formatIn(Date.parse(at), tz), received_at: formatIn(Date.parse(received_at), tz) });
const failure = (e: unknown) => ({ isError: true, content: [{ type: "text" as const, text: e instanceof Error ? e.message : String(e) }] });

export function buildServer(hub: Hub): McpServer {
  const server = new McpServer({ name: "android-connect", version: "0.7.0" });
  const tool = <S extends z.ZodRawShape>(name: string, description: string, shape: S, run: (args: ShapeOutput<S>) => Promise<unknown>) =>
    server.registerTool(name, { description, inputSchema: shape as z.ZodRawShape }, async (args) => {
      try {
        // The SDK validated args against shape; TypeScript cannot follow its conditional callback type through a generic shape.
        return text(await run(args as ShapeOutput<S>));
      } catch (e) {
        return failure(e);
      }
    });
  const phone = <S extends z.ZodRawShape>(name: string, description: string, shape: S, params: (args: ShapeOutput<S>) => unknown = (args) => args) =>
    tool(name, description, shape, (args) => hub.call(name, params(args)));
  const health = <S extends z.ZodRawShape>(name: string, description: string, shape: S) =>
    phone(name, description, shape, (args) => inZone(args, "start", "end"));

  health("get_steps", "Step counts from Health Connect, aggregated per bucket (live from the phone).", { ...range, bucket });
  health("get_sleep", "Sleep sessions with stages and durations overlapping the range.", range);
  health("get_heart_rate", "Heart rate: min/avg/max per bucket, plus resting HR when available.", { ...range, bucket });
  health("get_exercise_sessions", "Exercise/workout sessions (type, duration, calories, distance, avg HR).", range);
  health("get_weight", "Weight measurements (kg) in the range.", range);
  health("get_nutrition", "Nutrition entries and per-day totals (energy, macros).", range);
  health("read_records", "Generic raw read of any Health Connect record type in a time range.", {
    recordType: z.enum(RECORD_TYPES).describe("Health Connect record type name"),
    ...range,
    limit: z.number().int().min(1).max(1000).default(200),
  });
  phone("list_notifications", "Active notifications on the phone, newest first: app, title, text, and the action buttons each one offers (reply actions included).", {
    package: z.string().min(1).optional().describe("Only notifications from this Android package, e.g. com.whatsapp"),
    since: iso.optional().describe("Only notifications posted at or after this time, same formats as start"),
    limit: z.number().int().min(1).max(200).default(50),
    timezone: range.timezone,
  }, (args) => inZone(args, "since"));
  phone("dismiss_notification", "Dismiss one notification. Pass key for any notification from list_notifications, as if the user swiped it away; ongoing or non-clearable ones are refused. Pass notification_id for one sent with send_notification, which also stops its alarm and needs no notification access.", {
    key: notificationKey.optional(),
    notification_id: notificationTag.optional().describe("The notification_id returned by send_notification"),
  }, (args) => {
    if ((args.key === undefined) === (args.notification_id === undefined)) throw new Error("pass exactly one of key or notification_id");
    return args;
  });
  phone("perform_notification_action", "Tap a notification's action button by index, or its body with action \"content\". Reply actions need replyText, which is sent as the typed reply.", {
    key: notificationKey,
    action: z.union([z.number().int().min(0), z.literal("content")]).describe("Action index from list_notifications, or \"content\" to tap the notification itself"),
    replyText: z.string().min(1).max(4000).optional().describe("Reply text, required for and only allowed with a reply action"),
  }, (args) => {
    if (args.action === "content" && args.replyText !== undefined) throw new Error("replyText only applies to a reply action index");
    return args;
  });
  phone("send_notification", "Show a notification on the phone from the assistant, optionally with buttons and reply fields. Taps and replies are collected by get_notification_responses. Reusing a tag replaces that notification. With alarm, it rings as an alarm (audible in silent and vibrate mode, and through Do Not Disturb when alarms are allowed or the app has Do Not Disturb access) until dismiss_notification with its notification_id, or the user, dismisses it.", {
    title: z.string().min(1).max(200),
    text: z.string().max(2000).optional(),
    channel: z.string().min(1).max(40).default("Assistant").describe("Channel name shown in Android settings. Android fixes a channel's sound and importance when it is first created, so each importance/silent combination gets its own channel"),
    importance: z.enum(["min", "low", "default", "high"]).default("default"),
    silent: z.boolean().default(false).describe("No sound or vibration"),
    alarm: z.boolean().default(false).describe("Ring with the default alarm sound on the alarm stream, like a find-my-phone ring. Overrides channel, importance and silent"),
    insistent: z.boolean().default(true).describe("With alarm, loop the sound until the notification is dismissed"),
    vibrate: z.boolean().default(true).describe("With alarm, vibrate as well"),
    tag: notificationTag.optional().describe("Stable id, returned as notification_id; sending again with the same tag replaces the notification"),
    actions: z.array(z.object({
      id: z.string().regex(/^[a-z0-9_-]{1,32}$/).refine((v) => v !== "content", "content is reserved for taps on the notification body"),
      label: z.string().min(1).max(40),
      reply: z.object({ placeholder: z.string().max(60).optional() }).optional().describe("Make this a text reply action"),
    })).max(3).default([]).refine((a) => new Set(a.map((x) => x.id)).size === a.length, "action ids must be unique"),
  });
  phone("get_location", "The phone's current location (GPS/network fix), or the last known fix if a fresh one is not available in time.", {
    max_age_s: z.number().int().min(0).max(86400).default(120).describe("Accept a cached fix up to this old instead of waiting for a new one"),
    timeout_s: z.number().int().min(1).max(12).default(10),
    timezone,
  }, (args) => inZone(args));
  phone("list_threads", `SMS/MMS conversations, newest first, with the other party's address and the last message snippet. ${SMS_ONLY}`,
    { limit: z.number().int().min(1).max(100).default(20), timezone: range.timezone }, (args) => inZone(args));
  phone("list_messages", `SMS and MMS messages, newest first. Contact names are added when contacts permission is granted. MMS returns text parts only. ${SMS_ONLY}`, {
    thread_id: z.number().int().min(1).optional(),
    address: z.string().min(3).max(40).optional().describe("Only messages with this phone number (matched on trailing digits)"),
    since: iso.optional(),
    until: iso.optional(),
    box: z.enum(["all", "inbox", "sent"]).default("all"),
    limit: z.number().int().min(1).max(500).default(50),
    timezone: range.timezone,
  }, (args) => inZone(args, "since", "until"));
  phone("send_message", `Send an SMS from the phone's default SIM and report whether it was sent and delivered. ${SMS_ONLY}`,
    { to: z.string().regex(/^\+?[0-9][0-9 ()-]{2,24}$/, "phone number"), text: smsText });
  phone("reply_to_thread", `Send an SMS to the other party of a one-to-one thread from list_threads. Group threads are refused. ${SMS_ONLY}`,
    { thread_id: z.number().int().min(1), text: smsText });
  phone("search_contacts", "Find contacts by name or phone number. Returns names, phone numbers with their type, and email addresses.",
    { query: z.string().min(1).max(100), limit: z.number().int().min(1).max(50).default(10) });

  tool("get_notification_responses", "Taps, button presses and replies on notifications sent with send_notification, oldest first. Kept for 7 days. Set wait_seconds to wait for the first response. phone_time is the phone's clock at the tap, and received_at is when the Worker got it.", {
    notification_id: z.string().min(1).max(64).optional().describe("The tag returned by send_notification"),
    since: iso.optional(),
    wait_seconds: z.number().int().min(0).max(20).default(0),
    timezone,
  }, async (args) => {
    const { since, timezone: tz } = inZone(args, "since");
    const found = await hub.responses({ notification_id: args.notification_id, since }, args.wait_seconds * 1000);
    return { timezone: tz, responses: found.map((e) => response(e, tz)) };
  });
  tool("start_call", "Ring the phone like an incoming call from the assistant, with a full-screen call screen and Answer/Decline. Answer unlocks the phone and opens a Grok Bot voice call with agent_id. Waits until the call is answered, declined or missed (ring_seconds plus 5 s at most).", {
    agent_id: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/).describe("Grok Bot agent id the voice call opens"),
    caller_name: z.string().min(1).max(60).default("Grok Bot"),
    reason: z.string().min(1).max(140).describe("Why you are calling, shown under the caller name"),
    ring_seconds: z.number().int().min(5).max(60).default(30),
    timezone,
  }, async ({ timezone: zone, ...args }) => {
    const tz = inZone({ timezone: zone }).timezone;
    const call_id = `call-${crypto.randomUUID().slice(0, 12)}`;
    const ring = (await hub.call("start_call", { ...args, call_id })) as Record<string, unknown>;
    const [first] = await hub.responses({ notification_id: call_id }, (args.ring_seconds + CALL_GRACE_S) * 1000);
    if (first) return { ...ring, call_id, outcome: first.action_id, phone_time: formatIn(Date.parse(first.at), tz) };
    await hub.recordEvent({ notification_id: call_id, action_id: "missed", at: new Date().toISOString() });
    return { ...ring, call_id, outcome: "missed" };
  });
  tool("get_phone_status", "Whether the phone is connected, its last-seen time, app version, battery_percent (0-100) and permissions.", { timezone }, async (args) => {
    const tz = inZone(args).timezone;
    const s: Record<string, unknown> = { timezone: tz, ...(await hub.status(tz)) };
    if (s.push_registered) {
      try {
        const phone: any = await hub.call("get_phone_status", {});
        s.phone = phone;
        if (phone && typeof phone === "object") s.device = await hub.updateDevice({ app_version: phone.app_version, device: phone.device }, tz);
      } catch (e) { s.phone_error = e instanceof Error ? e.message : String(e); }
    }
    return s;
  });
  return server;
}
