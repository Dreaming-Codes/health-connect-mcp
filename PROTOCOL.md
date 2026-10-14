# Phone ⇄ Worker protocol (v1)

Live pull only: the Worker stores **no health data**. The Durable Object `PhoneHub` (name `phone`) keeps only
`lastSeen`, `connectedAt` and the last `hello` metadata.

## Connection
- `GET wss://<worker>/phone/connect` with header `Authorization: Bearer <DEVICE_TOKEN>`.
- Worker compares `sha256_hex(DEVICE_TOKEN)` with secret `DEVICE_TOKEN_HASH` (constant time). Bad/missing → HTTP 401, no upgrade.
- Only one phone socket is live. A new connection closes the old one with code `4000 "replaced by new connection"`.
- All frames are UTF-8 JSON text.

## Phone → Worker
| Frame | Meaning |
|---|---|
| `{"type":"hello","app_version":"0.1.0","device":"Google Pixel 8"}` | Sent right after open. Stored for `get_phone_status`. |
| `{"type":"ping"}` | Keep-alive, every ~30 s. Answered with `{"type":"pong"}` by the runtime **without waking the DO** (hibernation auto-response). Byte-exact match required. |
| `{"id":"…","result":{…}}` | Successful answer to a request. |
| `{"id":"…","error":{"code":"…","message":"…"}}` | Failed answer. Codes: `permission_denied`, `bad_params`, `unknown_method`, `internal`. |

## Worker → Phone
`{"id":"<uuid>","method":"<name>","params":{…}}`. The phone must answer with the same `id` within 10 s
(else the MCP tool returns "phone did not answer within 10s"). Requests may arrive concurrently; answer in any order.
Frames with a `type` field from the Worker (e.g. `pong`) are not requests.

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

Values Health Connect lacks are `null`. The phone app must never log values, only method names.

## Push mode (default since app 0.3.0)
No persistent connection. When no WebSocket is live, the Worker wakes the phone with FCM.

1. **Register**: `POST /phone/register` with `Authorization: Bearer <DEVICE_TOKEN>`,
   body `{"fcm_token":"…","app_version":"0.3.0","device":"Google Pixel","mode":"push"}` → `{"ok":true,"push_configured":true}`.
   The app re-sends this on FCM token refresh (`onNewToken`) and when the user taps Register.
2. **Request**: the DO creates a request id and sends a high-priority FCM **data** message (TTL 20 s) containing only
   `id`, `method`, `params` (JSON string). It never contains health data.
3. **Answer**: the phone runs the query (≤15 s inside `onMessageReceived`) and calls `POST /phone/result/<id>` with the
   device token, body `{"result":{…}}` or `{"error":{"code","message"}}` (same shapes as above). Responses: `200 {"ok":true}`,
   `410` when the id is unknown or expired.
4. The Worker waits up to 20 s (`PUSH_TIMEOUT_MS`). If FCM says the token is `UNREGISTERED` the stored token is dropped.

Routing: a live WebSocket (the "Always-on connection" toggle in the app) is preferred, and push is the fallback.
`get_phone_status` reports `mode` (`websocket`/`push`/`none`), `push_registered_at`, `last_push.latency_ms`.
Background reads require Health Connect's `READ_HEALTH_DATA_IN_BACKGROUND` where the feature exists.
Worker secret `FCM_SERVICE_ACCOUNT` holds the Firebase service-account JSON. OAuth tokens are cached in DO storage.
