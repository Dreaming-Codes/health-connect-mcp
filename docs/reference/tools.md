# MCP tool reference

The Worker exposes 20 tools on `/mcp`. Every tool returns one text content item that holds JSON. A failed call returns `isError: true` with the error message as text.

This file is generated from the live tool list. To regenerate it, run `npm run docs:tools`.

## `dismiss_notification`

Dismiss one notification, as if the user swiped it away. Ongoing or non-clearable notifications are refused.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string | yes |  | Notification key from list_notifications |

## `get_exercise_sessions`

Exercise/workout sessions (type, duration, calories, distance, avg HR).

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `get_heart_rate`

Heart rate: min/avg/max per bucket, plus resting HR when available.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |
| `bucket` | `none`, `hour`, `day` | no | `"day"` | Aggregation bucket |

## `get_location`

The phone's current location (GPS/network fix), or the last known fix if a fresh one is not available in time.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `max_age_s` | integer, ≥ 0, ≤ 86400 | no | `120` | Accept a cached fix up to this old instead of waiting for a new one |
| `timeout_s` | integer, ≥ 1, ≤ 12 | no | `10` |  |

## `get_notification_responses`

Taps, button presses and replies on notifications sent with send_notification, oldest first. Kept for 7 days. Set wait_seconds to wait for the first response.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `notification_id` | string, ≤ 64 chars | no |  | The tag returned by send_notification |
| `since` | string | no |  |  |
| `wait_seconds` | integer, ≥ 0, ≤ 20 | no | `0` |  |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `get_nutrition`

Nutrition entries and per-day totals (energy, macros).

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `get_phone_status`

Whether the phone is connected, last-seen time, app/permission info.

No parameters.

## `get_sleep`

Sleep sessions with stages and durations overlapping the range.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `get_steps`

Step counts from Health Connect, aggregated per bucket (live from the phone).

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |
| `bucket` | `none`, `hour`, `day` | no | `"day"` | Aggregation bucket |

## `get_weight`

Weight measurements (kg) in the range.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `list_messages`

SMS and MMS messages, newest first. Contact names are added when contacts permission is granted. MMS returns text parts only. Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `thread_id` | integer, ≥ 1, ≤ 9007199254740991 | no |  |  |
| `address` | string, ≤ 40 chars | no |  | Only messages with this phone number (matched on trailing digits) |
| `since` | string | no |  |  |
| `until` | string | no |  |  |
| `box` | `all`, `inbox`, `sent` | no | `"all"` |  |
| `limit` | integer, ≥ 1, ≤ 500 | no | `50` |  |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `list_notifications`

Active notifications on the phone, newest first: app, title, text, and the action buttons each one offers (reply actions included).

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `package` | string | no |  | Only notifications from this Android package, e.g. com.whatsapp |
| `since` | string | no |  | Only notifications posted at or after this time, same formats as start |
| `limit` | integer, ≥ 1, ≤ 200 | no | `50` |  |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `list_threads`

SMS/MMS conversations, newest first, with the other party's address and the last message snippet. Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `limit` | integer, ≥ 1, ≤ 100 | no | `20` |  |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |

## `perform_notification_action`

Tap a notification's action button by index, or its body with action "content". Reply actions need replyText, which is sent as the typed reply.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string | yes |  | Notification key from list_notifications |
| `action` | integer, ≥ 0, ≤ 9007199254740991 or `"content"` | yes |  | Action index from list_notifications, or "content" to tap the notification itself |
| `replyText` | string, ≤ 4000 chars | no |  | Reply text, required for and only allowed with a reply action |

## `read_records`

Generic raw read of any Health Connect record type in a time range.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `recordType` | `Steps`, `Distance`, `ActiveCaloriesBurned`, `TotalCaloriesBurned`, `FloorsClimbed`, `SleepSession`, `HeartRate`, `RestingHeartRate`, `HeartRateVariabilityRmssd`, `ExerciseSession`, `Weight`, `Height`, `BodyFat`, `Nutrition`, `Hydration`, `OxygenSaturation`, `RespiratoryRate`, `BloodPressure`, `BloodGlucose`, `BodyTemperature`, `Vo2Max`, `Speed`, `Power` | yes |  | Health Connect record type name |
| `start` | string | yes |  | Inclusive start: 2026-10-12, 2026-10-12T08:00 (local, in `timezone`) or 2026-10-12T00:00:00+02:00 |
| `end` | string | yes |  | Exclusive end, same formats as start (a plain date means local midnight starting that day) |
| `timezone` | string | no | `"Europe/Amsterdam"` | IANA timezone used for day buckets (default Europe/Amsterdam) |
| `limit` | integer, ≥ 1, ≤ 1000 | no | `200` |  |

## `reply_to_thread`

Send an SMS to the other party of a one-to-one thread from list_threads. Group threads are refused. Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `thread_id` | integer, ≥ 1, ≤ 9007199254740991 | yes |  |  |
| `text` | string, ≤ 5000 chars | yes |  | Message text; long text is sent as a multipart SMS |

## `search_contacts`

Find contacts by name or phone number. Returns names, phone numbers with their type, and email addresses.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string, ≤ 100 chars | yes |  |  |
| `limit` | integer, ≥ 1, ≤ 50 | no | `10` |  |

## `send_message`

Send an SMS from the phone's default SIM and report whether it was sent and delivered. Plain SMS/MMS only: RCS chats in Google Messages are not stored in Android's SMS/MMS provider and are not visible here.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `to` | string | yes |  |  |
| `text` | string, ≤ 5000 chars | yes |  | Message text; long text is sent as a multipart SMS |

## `send_notification`

Show a notification on the phone from the assistant, optionally with buttons and reply fields. Taps and replies are collected by get_notification_responses. Reusing a tag replaces that notification. With alarm, it rings as an alarm (audible in silent and vibrate mode, and through Do Not Disturb when alarms are allowed or the app has Do Not Disturb access) until dismiss_notification or the user dismisses it.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `title` | string, ≤ 200 chars | yes |  |  |
| `text` | string, ≤ 2000 chars | no |  |  |
| `channel` | string, ≤ 40 chars | no | `"Assistant"` | Channel name shown in Android settings. Android fixes a channel's sound and importance when it is first created, so each importance/silent combination gets its own channel |
| `importance` | `min`, `low`, `default`, `high` | no | `"default"` |  |
| `silent` | boolean | no | `false` | No sound or vibration |
| `alarm` | boolean | no | `false` | Ring with the default alarm sound on the alarm stream, like a find-my-phone ring. Overrides channel, importance and silent |
| `insistent` | boolean | no | `true` | With alarm, loop the sound until the notification is dismissed |
| `vibrate` | boolean | no | `true` | With alarm, vibrate as well |
| `tag` | string | no |  | Stable id; sending again with the same tag replaces the notification |
| `actions` | array of objects | no | `[]` |  |
| `actions[].id` | string | yes |  |  |
| `actions[].label` | string, ≤ 40 chars | yes |  |  |
| `actions[].reply` | object | no |  | Make this a text reply action |
| `actions[].reply.placeholder` | string, ≤ 60 chars | no |  |  |

## `start_call`

Ring the phone like an incoming call from the assistant, with a full-screen call screen and Answer/Decline. Answer unlocks the phone and opens a Grok Bot voice call with agent_id. Waits until the call is answered, declined or missed (ring_seconds plus 5 s at most).

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `agent_id` | string | yes |  | Grok Bot agent id the voice call opens |
| `caller_name` | string, ≤ 60 chars | no | `"Grok Bot"` |  |
| `reason` | string, ≤ 140 chars | yes |  | Why you are calling, shown under the caller name |
| `ring_seconds` | integer, ≥ 5, ≤ 60 | no | `30` |  |
