# Phone ⇄ Worker protocol (v2, push only)

Live pull, push only. The Worker stores **no health data and no notification content**. The Durable Object `PhoneHub` (one per enrolled phone) keeps only
the phone's FCM token, `lastSeen`, the last push latency, device metadata, a cached Google OAuth token and in-flight
request ids (in memory). There is no persistent connection.

All phone → Worker calls use `Authorization: Bearer <device token>` from `POST /enroll`. The Worker compares the hash of
the token's secret with the one stored in that phone's `PhoneHub` in constant time, and a bad or missing token gets `401`.

## 1. Register: `POST /phone/register`
Body `{"fcm_token":"…","app_version":"0.3.1","device":"Google Pixel","mode":"push"}` → `200 {"ok":true,"push_configured":true}`.
Sent when the user taps **Register device** and on every FCM token refresh (`onNewToken`).

## 2. Request (Worker → phone, FCM)
For each MCP tool call the DO creates a UUID and sends a high-priority FCM **data** message (TTL 20 s) via FCM HTTP v1:
`data = {"id":"<uuid>","device_id":"<16 hex>","method":"get_steps","params":"<JSON string>"}`. The message never contains health data.
The app runs a push only when `device_id` equals its current enrollment, so a `PhoneHub` left behind by an earlier enrollment cannot drive the phone even if it still holds the FCM token.

## 3. Answer: `POST /phone/result/<id>`
Body `{"result":{…}}` or `{"error":{"code":"…","message":"…"}}` → `200 {"ok":true}`, or `410` when the id is unknown or has expired.
Error codes are `permission_denied`, `bad_params`, `unknown_method`, `timeout` and `internal`, plus the notification
codes listed below. The app answers within about 15 s, inside `onMessageReceived`.

The Worker waits up to `PUSH_TIMEOUT_MS` (default 20 s). If FCM reports the token `UNREGISTERED`, the token is
dropped and the tool says to re-open the app. Reads happen while the app is in the background, so the app requests
Health Connect's `READ_HEALTH_DATA_IN_BACKGROUND` where the feature exists.

### Common params
`start`, `end`: always sent to the phone as ISO-8601 with an explicit offset (`2026-10-12T00:00:00+02:00`) or `Z`. The Worker accepts plain dates (`2026-10-12`) and local date-times without offset (`2026-10-12T08:00`) from MCP clients and normalizes them using `timezone`. Half-open `[start, end)`.
`timezone`: IANA zone, default `Europe/Amsterdam`; used for day buckets and for formatting output times. Every tool that
returns a time takes `timezone`, and every returned time is `YYYY-MM-DDTHH:mm:ss.SSS±HH:MM` in that zone, whether the
phone or the Worker formats it.

### Methods and result shapes
| method | extra params | result |
|---|---|---|
| `get_steps` | `bucket`: `none`/`hour`/`day` (default day) | `{unit, timezone, bucket, total, buckets:[{date\|start, steps}]}` |
| `get_heart_rate` | `bucket` | `{unit:"bpm", overall:{min_bpm,avg_bpm,max_bpm,samples}, buckets:[{date\|start,…}], resting:[{time,bpm}]}` |
| `get_sleep` | – | `{sessions:[{start,end,night_of,duration_min,title,stage_minutes:{deep,light,rem,awake,…},source}]}` |
| `get_exercise_sessions` | – | `{sessions:[{start,end,duration_min,type,title,distance_m,active_kcal,avg_bpm,max_bpm,steps,source}]}` |
| `get_weight` | – | `{unit:"kg", records:[{time,kg}]}` |
| `get_nutrition` | – | `{days:[{date,kcal,protein_g,carbs_g,fat_g,entries}], entries:[…]}` |
| `read_records` | `recordType` (Steps, HeartRate, RestingHeartRate, SleepSession, ExerciseSession, Distance, ActiveCaloriesBurned, TotalCaloriesBurned, Weight, Nutrition, Hydration), `limit` ≤1000 | `{recordType, count, records:[{source, time\|start,end, …type fields}]}` |
| `get_phone_status` | – | `{app_version, device, android_sdk, battery_percent, notification_access, notification_listener_connected, health_connect, granted[], missing[]}` |
| `list_notifications` | `package`, `since` (offset ISO), `limit` 1–200 (default 50), `timezone`; no `start`/`end` | `{timezone, total_active, count, notifications:[…]}`, newest first |
| `dismiss_notification` | `key`, or `notification_id` from `send_notification` (0.7.0) | `{key, removed}` or `{notification_id, removed}` |
| `perform_notification_action` | `key`, `action` (index ≥ 0 or `"content"`), `replyText` | `{key, sent, opens_activity}` |
| `send_notification` | `title`, `text`, `channel`, `importance` (min/low/default/high), `silent`, `alarm`, `insistent`, `vibrate`, `tag`, `actions[{id,label,reply?{placeholder}}]` ≤3 | `{notification_id, channel_id}`; alarms add `{alarm_volume, alarm_volume_max, bypass_dnd, dnd_filter, warning?}` |
| `start_call` | `call_id`, `agent_id`, `caller_name`, `reason` ≤140, `ring_seconds` 5–60 | `{call_id, ringing, full_screen, grok_bot_installed}` |
| `get_location` | `max_age_s`, `timeout_s` ≤12, `timezone` | `{latitude, longitude, accuracy_m, altitude_m, speed_mps, bearing_deg, time, age_s, provider, source:"last_known"\|"current"}` |
| `list_threads` | `limit`, `timezone` | `{threads:[{thread_id, addresses, names, group, snippet, date, message_count, read}]}` |
| `list_messages` | `thread_id`, `address`, `since`, `until`, `box` (all/inbox/sent), `limit` ≤500, `timezone` | `{count, messages:[{id, kind:"sms"\|"mms", thread_id, address, contact_name, box, date, text, read}]}` |
| `send_message` | `to`, `text` | `{to, parts, sent, delivered, error_code}` |
| `reply_to_thread` | `thread_id`, `text` | same as `send_message` |
| `search_contacts` | `query`, `limit` | `{contacts:[{id, name, phones:[{number, type}], emails:[{address, type}]}]}` |

Values Health Connect lacks are omitted or `null`. The phone app must never log values, only method names.

### Notifications
The app's `NotificationListener` (a `NotificationListenerService`) keeps an in-memory index of active notifications,
keyed by `StatusBarNotification.getKey()`. It is seeded from `getActiveNotifications()` in `onListenerConnected` and
updated on every post and removal. Nothing is persisted.

A notification in `list_notifications`:
`{key, package, app_label, post_time, title, text, sub_text, category, ongoing, clearable, redacted, has_content_intent,
actions:[{index, title, reply, opens_activity, auth_required}]}`.

- `reply`: the action has a text `RemoteInput`. `perform_notification_action` then requires `replyText`, fills every
  text input with it through `RemoteInput.addResultsToIntent`, and marks the source as free-form input.
- `redacted`: Android 15+ hides OTP notification content from untrusted listeners. The flag is true when the title or
  text equals one of the system's redaction strings.
- `opens_activity`: the intent starts an activity. The sender opts in to background activity starts, but the phone
  is woken by push and is not visible, so Android may still block the launch silently. Broadcast and service intents,
  which most reply and mark-read actions use, are not affected.
- `action: "content"` sends the notification's content intent and cancels the notification if it has `FLAG_AUTO_CANCEL`.

Notification error codes:

| code | meaning |
|---|---|
| `notification_access_missing` | the user has not granted notification access |
| `notification_listener_unavailable` | access is granted but the listener did not connect within 3 s of `requestRebind` |
| `not_found` | no active notification with that key, or no such action |
| `not_clearable` | `dismiss_notification` on an ongoing or no-clear notification |
| `action_canceled` | the target app cancelled the `PendingIntent` |

Notification titles, text and replies are never logged by the Worker or the app.

### Events: `POST /phone/event`
Taps, button presses, replies and call outcomes on notifications the bot sent. Body
`{notification_id, action_id, reply_text?, at}` with the device token. `action_id` is a button id, `"content"`,
`"dismissed"`, or for calls `"answered"`, `"declined"` (the Worker records `"missed"` itself). The Durable Object keeps
events for 7 days; `get_notification_responses` reads them and can wait up to 20 s for the first one. It returns the
event's `at` as `phone_time` (the phone's clock at the tap) and `received_at` (the Worker's clock), both in `timezone`.
`start_call` waits `ring_seconds + 5` s for its outcome and returns its `phone_time`.

### send_notification
Channels are created on demand as `bot_<slug>_<importance>[_silent]`. Android freezes a channel's importance and sound
after creation, so each combination gets its own channel. Bot notifications are ordinary notifications, so
`list_notifications`, `dismiss_notification` and `perform_notification_action` work on them too.

`alarm: true` posts on `bot_alarm[_dnd][_novib]`: `IMPORTANCE_HIGH`, `CATEGORY_ALARM`, default alarm sound with
`USAGE_ALARM`, `FLAG_INSISTENT` unless `insistent: false`, and `setBypassDnd(true)` when the app has Do Not Disturb
access. Dismissing the notification stops the sound. From AOSP: `NotificationAttentionHelper` plays only when the
alarm stream volume is non-zero, so the result reports `alarm_volume` and warns when it is 0; ringer silent/vibrate
mode does not mute the alarm stream. `ZenModeFiltering` lets alarms through in "Alarms only" and in priority mode
when the policy allows alarms or the channel bypasses DND; "Total silence" blocks everything.

### start_call
Posts a `CallStyle` incoming-call notification (Answer/Decline actions below API 31) on `bot_call` with a full-screen
intent to the app's own `IncomingCallActivity`, which shows over the lock screen. Without full-screen permission
(Android 14+, `canUseFullScreenIntent`) it is a heads-up notification only. Answer dismisses the keyguard and opens
Grok Bot at `grokbot://app/v1/voice-call?id=<agent_id>`, falling back to `/v1/agent?id=` and then the launcher.
`reason` is shown under the caller name and as the notification text; it is never logged.

### SMS and contacts
Read from the Telephony provider. RCS chats are not in the provider and are not returned. MMS returns text parts
only. `send_message` splits long text with `SmsManager.divideMessage` and waits up to 8 s for sent and 4 s for
delivered reports. Contact names are added to messages when contacts permission is granted; otherwise only the raw
address. Message bodies, numbers and contact data are never logged.

Other error codes: `notifications_permission_missing`, `channel_blocked`, `location_permission_missing`,
`background_location_missing`, `location_disabled`, `location_unavailable`, `sms_permission_missing`, `group_thread`,
`contacts_permission_missing`. An app older than 0.4.0 answers new methods with `unknown_method`.


`get_phone_status` (MCP) returns `{timezone, name, mode:"push"|"none", push_registered, push_registered_at,
last_push:{latency_ms,at}, last_seen, device:{app_version, device, mode, updated_at}}` plus `phone` (the phone's own answer, which takes one push round trip) when registered.

## Enrollment
Design and tradeoffs: `docs/enrollment.md`.

| endpoint | auth | body | answer |
|---|---|---|---|
| `POST /enroll` | none | `{name}` | `{device_id, device_token}` |
| `POST /phone/connector-code` | device token | `{}` | `{code, expires_at, claim_url}` |
| `POST /phone/name` | device token | `{name}` | `{ok}` |
| `POST /phone/disconnect` | device token | `{}` | `{ok}`; wipes the phone's `PhoneHub`, so its device token, connector token and FCM registration stop working |
| `POST /claim` or `GET /claim?code=` | none | `{code}` | `{name, mcp_url, transport, headers:{Authorization}}` |

- Tokens are `hcd_<device_id>_<secret>` (device) and `hco_<device_id>_<secret>` (connector). Only SHA-256 hashes of
  the secret are stored, in the phone's `PhoneHub`.
- Codes are 8 Crockford base32 characters shown as `XXXX-XXXX`, single use, valid for 10 minutes. Input is
  case-insensitive and `O`/`I`/`L` read as `0`/`1`.
- `/enroll` allows 10 requests per client IP per hour, and `/claim` allows 20.
- A connector token reaches only its own phone and works only on `/mcp`. A device token works only on `/phone/*`.
- A phone has at most one connector token. Each claim replaces the previous one.

## Secrets (Worker)
`FCM_SERVICE_ACCOUNT` (Firebase service-account JSON; RS256 JWT is signed with WebCrypto and the OAuth token is cached
in DO storage).
`FCM_ENDPOINT` (optional) overrides `https://fcm.googleapis.com`; the simulated-phone test uses it.
