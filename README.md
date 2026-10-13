# health-connect-mcp

Remote MCP server (Cloudflare Worker + Durable Object) that answers questions about Android Health Connect data by
**live-pulling from the phone**. Nothing health-related is stored server-side.

```
MCP client ──HTTPS Bearer MCP_TOKEN──▶ Worker /mcp ──RPC──▶ PhoneHub DO ◀══WSS Bearer DEVICE_TOKEN══ Android app (foreground service) ──▶ Health Connect
```

- `src/index.ts` – Worker (MCP Streamable HTTP, stateless JSON responses) + `PhoneHub` DO (hibernatable WebSocket).
- `PROTOCOL.md` – phone ⇄ Worker protocol.
- `android/` – Kotlin companion app (`dev.dreamingcodes.hcmcp`).
- `test/sim-phone.mjs`, `test/e2e.mjs` – simulated phone + MCP client tests.

## Tools
get_steps, get_sleep, get_heart_rate, get_exercise_sessions, get_weight, get_nutrition, read_records, get_phone_status.
Inputs: `start`, `end` (ISO-8601), `timezone` (default Europe/Amsterdam).

## Local dev
```
npm i
printf 'DEVICE_TOKEN_HASH=%s\nMCP_TOKEN=dev-mcp-token\n' $(printf %s dev-device-token | sha256sum | cut -d' ' -f1) > .dev.vars
npx wrangler dev            # Node >= 22
node test/sim-phone.mjs http://127.0.0.1:8787 dev-device-token
node test/e2e.mjs http://127.0.0.1:8787 dev-mcp-token list
npx tsc -p .                # typecheck
```

## Deploy
```
npx wrangler deploy
openssl rand -hex 32 | tee /dev/stderr | npx wrangler secret put MCP_TOKEN
DT=$(openssl rand -hex 32); echo $DT; printf %s $DT | sha256sum | cut -d' ' -f1 | npx wrangler secret put DEVICE_TOKEN_HASH
```
Durable Objects use the SQLite backend (`new_sqlite_classes`), which is available on the Workers Free plan.

## Android
`cd android && ./gradlew assembleRelease` (needs `keystore.properties` + keystore, not committed). Debug builds add a
"Insert test data" button with WRITE permissions; release builds are read-only.
