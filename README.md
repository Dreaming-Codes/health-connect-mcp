# health-connect-mcp

Remote MCP server (Cloudflare Worker + Durable Object) that answers questions about Android Health Connect data and
reads and acts on the phone's notifications by **live-pulling from the phone through FCM push**. Nothing health- or
notification-related is stored server-side, and nothing runs on the
phone between requests (no foreground service, no persistent notification).

```
MCP client ─HTTPS Bearer MCP_TOKEN─▶ Worker /mcp ─▶ PhoneHub DO ─FCM data msg─▶ phone (FirebaseMessagingService)
                                                     ▲                               │ queries Health Connect
                                                     └── POST /phone/result/<id> ◀───┘ (Bearer DEVICE_TOKEN)
```

- `src/index.ts`: Worker (MCP Streamable HTTP, stateless) and the `PhoneHub` DO (FCM token, pending requests).
- `src/fcm.ts`: FCM HTTP v1 sender (service-account JWT → OAuth, cached).
- `PROTOCOL.md`: the phone ⇄ Worker protocol.
- `android/`: Kotlin + Compose companion app (`dev.dreamingcodes.hcmcp`).
- `test/`: MCP client scripts (`e2e.mjs`, `verify.mjs`, `time-formats.mjs`). `run-contract.sh` runs the wire contract
  snapshot (`contract.mjs`) against a fresh `wrangler dev`. `sim-phone.mjs` runs every tool end to end against
  `wrangler dev` with a simulated FCM and phone (`node test/sim-phone.mjs`).

## Tools
get_steps, get_sleep, get_heart_rate, get_exercise_sessions, get_weight, get_nutrition, read_records, get_phone_status,
list_notifications, dismiss_notification, perform_notification_action, send_notification, get_notification_responses,
start_call, get_location, list_threads, list_messages, send_message, reply_to_thread, search_contacts, list_devices.
Phone tools take an optional `device`.
`start`/`end` accept `YYYY-MM-DD`, local `YYYY-MM-DDTHH:mm[:ss]` (read in `timezone`, default Europe/Amsterdam) or ISO with offset.

## Deploy
```
npx wrangler deploy        # Node >= 22
npx wrangler secret put MCP_TOKEN
npx wrangler secret put DEVICE_TOKEN_HASH     # sha256 hex of the device token
npx wrangler secret put FCM_SERVICE_ACCOUNT < service-account.json
```
SQLite-backed Durable Objects run on the Workers Free plan.

## Android
Put `google-services.json` in `android/app/` (gitignored) and `keystore.properties` + keystore in `android/` (gitignored),
then run `./gradlew assembleRelease`. In the app: enter the Worker URL and tap **Connect**. Then tap **Register with
Grok Bot**, which hands Grok Bot a one-time claim code for this phone's connector. To add a second phone to the same
connector, tap **Pair another phone** and enter the code on the new phone under **Link to an existing connector**.
An existing device token still works under **I have a device token**. Grant Health Connect access, including background
reads. For the notification tools, tap **Grant notification access** and enable
HC Bridge. The **Phone access** card grants the rest: notifications, full-screen calls, Do Not Disturb access for
alarms, location (then "Allow all the time"), SMS and contacts. On Android 13+ a sideloaded app may first need
**App info > ⋮ > Allow restricted settings** for notification access and SMS.
