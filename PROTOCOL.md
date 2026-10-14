# Phone ⇄ Worker protocol (v2, push only)

Live pull, push only. The Worker stores **no health data**. The Durable Object `PhoneHub` (name `phone`) keeps only
the phone's FCM token, `lastSeen`, the last push latency, device metadata, a cached Google OAuth token and in-flight
request ids (in memory). There is no persistent connection.

All phone → Worker calls use `Authorization: Bearer <DEVICE_TOKEN>`. The Worker compares `sha256_hex(token)` with the
secret `DEVICE_TOKEN_HASH` in constant time, and a bad or missing token gets `401`.

## 1. Register: `POST /phone/register`
Body `{"fcm_token":"…","app_version":"0.3.1","device":"Google Pixel","mode":"push"}` → `200 {"ok":true,"push_configured":true}`.
Sent when the user taps **Register device** and on every FCM token refresh (`onNewToken`).

## 2. Request (Worker → phone, FCM)
For each MCP tool call the DO creates a UUID and sends a high-priority FCM **data** message (TTL 20 s) via FCM HTTP v1:
`data = {"id":"<uuid>","method":"get_steps","params":"<JSON string>"}`. The message never contains health data.

## 3. Answer: `POST /phone/result/<id>`
Body `{"result":{…}}` or `{"error":{"code":"…","message":"…"}}` → `200 {"ok":true}`, or `410` when the id is unknown or has expired.
Error codes are `permission_denied`, `bad_params`, `unknown_method`, `timeout` and `internal`. The app answers within about 15 s, inside `onMessageReceived`.

The Worker waits up to `PUSH_TIMEOUT_MS` (default 20 s). If FCM reports the token `UNREGISTERED`, the token is
dropped and the tool says to re-open the app. Reads happen while the app is in the background, so the app requests
Health Connect's `READ_HEALTH_DATA_IN_BACKGROUND` where the feature exists.

### Common params
`start`, `end`: always sent to the phone as ISO-8601 with an explicit offset (`2026-10-12T00:00:00+02:00`) or `Z`. The Worker accepts plain dates (`2026-10-12`) and local date-times without offset (`2026-10-12T08:00`) from MCP clients and normalizes them using `timezone`. Half-open `[start, end)`.
`timezone`: IANA zone, default `Europe/Amsterdam`; used for day buckets and for formatting output times.

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
| `get_phone_status` | – | `{app_version, device, android_sdk, battery, health_connect, granted[], missing[]}` |

Values Health Connect lacks are omitted or `null`. The phone app must never log values, only method names.


`get_phone_status` (MCP) returns `{mode:"push"|"none", push_registered, push_registered_at, last_push:{latency_ms,at},
last_seen, device}` plus `phone` (the phone's own answer, which takes one push round trip) when registered.

## Secrets (Worker)
`MCP_TOKEN`, `DEVICE_TOKEN_HASH`, `FCM_SERVICE_ACCOUNT` (Firebase service-account JSON; RS256 JWT is signed with WebCrypto
and the OAuth token is cached in DO storage). Diagnostic: `GET /admin/fcm-selftest?token=<fcm token>` with the MCP token.
