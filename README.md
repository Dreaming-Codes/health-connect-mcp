# health-connect-mcp

Remote MCP server (Cloudflare Worker + Durable Object) that answers questions about Android Health Connect data by
**live-pulling from the phone through FCM push**. Nothing health-related is stored server-side, and nothing runs on the
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
- `test/`: MCP client scripts (`e2e.mjs`, `verify.mjs`, `time-formats.mjs`).

## Tools
get_steps, get_sleep, get_heart_rate, get_exercise_sessions, get_weight, get_nutrition, read_records, get_phone_status.
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
then run `./gradlew assembleRelease`. In the app: enter the Worker URL and device token, tap **Register device** and grant Health
Connect access, including background reads.
