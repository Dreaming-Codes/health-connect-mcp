# Enrollment

## Problem

The user types only the Worker URL. A stranger who knows that URL must not reach anyone else's phone. Grok Bot must get a connector without a long-lived key passing through the chat.

## Flow

1. In the app, the user enters `https://…workers.dev` and taps **Connect**.
   - `POST /enroll {name}` returns `{device_id, device_token}`.
   - The app stores the token and registers its FCM token.
2. The user taps **Register with Grok Bot**.
   - `POST /phone/connector-code` returns `{code, expires_at, claim_url}`.
   - The app shares a prefilled message with the code into Grok Bot.
3. Grok Bot calls `POST /claim {code}` and gets `{name, mcp_url, transport, headers: {Authorization: "Bearer hco_…"}}`.
   - The code works once and expires after 10 minutes.
   - `name` is `Android Connect (<device name>)`, so two phones give two connectors with different names.

A connector reaches exactly one phone. To add a second phone, enroll it the same way and register it with Grok Bot as its own connector. No tool takes a device argument.

## Shape

- Tokens name the phone they belong to:
  - device token `hcd_<device_id>_<secret>`
  - connector token `hco_<device_id>_<secret>`
  The id routes the request to that phone's `PhoneHub`, so the Worker has no token table and no singleton. The secret is 32 random bytes, and only its SHA-256 hash is stored. `parseToken` is the only code that reads the format.
- `PhoneHub` (one per phone, `idFromName(device_id)`) owns the device token hash, the connector token hash with its label, the name, the FCM registration, pending calls and notification events.
- `CodeTicket` (one per claim code, `idFromName(code)`) holds `{device_id, expires}` and deletes itself on the first read or after 10 minutes. Codes are 8 Crockford base32 characters (40 bits).
- `Gate` (one per client IP and route) is a fixed-window counter: 10 enrollments and 20 claims per hour.
- A device token works only on `/phone/*`, and a connector token works only on `/mcp`.

## Tradeoffs accepted

- Anyone who knows the URL can enroll a phone. That phone is the only one their connector reaches. Rate limits cap the cost in Durable Object storage and FCM quota.
- Tokens expose the device id. It is random and carries no data, and in exchange authentication needs no lookup.
- The claim code is visible in the chat transcript. A reader could claim it within 10 minutes if Grok Bot has not already. The token itself never appears in the chat.

## Alternatives considered

- Pairing codes that put several phones behind one connector. Removed in 0.7.0: an owner object between the connector and the phones added a Durable Object class, a `device` argument on every tool and a default-device rule, and one connector per phone needs none of them.
- A registry Durable Object that maps token hashes to phones. Every request would cross one global object.
- Signed tokens without storage. They cannot be revoked without a denylist, which is the storage they were meant to avoid.
- An enrollment secret typed on each phone. It brings back the copy-a-secret step that enrollment removed.

## Upgrade from 0.6.0 or earlier

0.7.0 removes the `MCP_TOKEN` and `DEVICE_TOKEN_HASH` secrets, pairing and the `OwnerHub` Durable Object. Phones and connectors set up with them stop working when the Worker is deployed.

1. Deploy the Worker. Migration `v3` deletes the `OwnerHub` class and its storage.
2. Install app 0.7.0 on each phone and open it. A phone that has no device id shows the **Connect** form. Tap **Connect**.
3. Tap **Register with Grok Bot**, and remove the old connector from Grok Bot.
4. Remove the old secrets: `npx wrangler secret delete MCP_TOKEN` and `npx wrangler secret delete DEVICE_TOKEN_HASH`.

## Rollback

Migration `v3` is one-way: Cloudflare deletes the `OwnerHub` storage and refuses to roll a version back past a migration. To return to 0.6.0, deploy the 0.6.0 code with a new migration `v4` that adds an empty `OwnerHub` class (`new_sqlite_classes`), and put the old secrets back. Connectors claimed under 0.6.0 enrollment are lost and must be claimed again. Phones enrolled under 0.7.0 keep their device tokens.

## Open questions and risks

- Grok Bot has no deep link that adds an MCP server, so the handoff is a prefilled share (`ACTION_SEND text/plain` to `ai.x.grok.bot`). Whether Grok Bot adds the connector by itself from that message is untested.
- Revoking a connector is not built. **Disconnect** in the app forgets the device token, but the old connectors stay valid. They reach a phone record that the app no longer answers, so their calls time out.
