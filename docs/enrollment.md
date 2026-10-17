# Enrollment and multi-device

## Problem
Setup needs two hand-copied secrets: the device token and the MCP token. Both are Worker secrets, so there is exactly one phone and one connector. The goal is that the user only types the Worker URL. A stranger who knows that URL must not be able to read Lorenzo's phones. One connector must be able to see several phones. Installed 0.3.2 and the existing Grok Bot connector must keep working without changes.

## Usage (caller's view)
New phone:
1. App: enter `https://…workers.dev`, tap **Connect**.
   - `POST /enroll {name}` returns `{device_id, device_token, owner_id}`.
   - The app stores the token and registers FCM as before.
2. App: tap **Register with Grok Bot**.
   - `POST /device/connector-code` returns `{code, expires_at}`.
   - Grok Bot is opened with a prefilled message: "Add the MCP connector for my phone: claim code K7QM-2XRA at https://…/claim".
3. Grok Bot: `POST /claim {code}` returns `{name, mcp_url, headers: {Authorization: "Bearer hco_…"}}`.
   - The code works once and expires after 10 minutes.

Second phone for the same connector:
- Phone A: **Pair another phone** calls `POST /device/pairing-code` and shows `{code}`.
- Phone B: enter the URL and that code. `POST /enroll {name, pairing_code}` joins A's owner.
- Or, with the owner's MCP token: `POST /enroll` with `Authorization: Bearer <mcp token>`.

MCP:
- `list_devices` returns `{devices:[{device_id, name, app_version, last_seen, default}]}`.
- Every phone tool takes an optional `device` (id or name). The default is the most recently seen device.

## Shape
- Tokens name their own scope:
  - device token `hcd_<device_id>_<secret>`
  - connector token `hco_<owner_id>_<secret>`
  The id part routes to the Durable Object that owns the hash, so there is no global registry DO and no hot singleton. The secret is 32 random bytes. Only SHA-256 hashes are stored. Per boundary-discipline, `parseToken` is the only place that reads the format.
- `PhoneHub` (one per device, `idFromName(device_id)`) owns:
  - device auth (token hash)
  - owner_id
  - name and app info
  - FCM, pending calls, events
  It is unchanged except for `enroll` and `verify`.
- `OwnerHub` (one per owner, `idFromName(owner_id)`) owns:
  - the device id list
  - connector token hashes, each with a label and created time
  It does not copy device state; `list_devices` asks each `PhoneHub`. Per split-ownership, each fact has one writer.
- `Ticket` (one per code, `idFromName(code)`) is a single-use `{kind:"pair"|"claim", owner_id, device_id, expires}`.
  - Codes are 8 Crockford base32 characters (40 bits).
  - `take()` deletes the ticket on first read.
- `Gate` (one per client IP and route) is a fixed-window counter.
  - `/enroll` allows 10 per hour.
  - `/claim` allows 20 per hour.
- Legacy access is a special owner. The env `MCP_TOKEN` resolves to owner `legacy`, and env `DEVICE_TOKEN_HASH` resolves to device `phone`. That is the existing `idFromName("phone")` DO, so its storage is untouched. `OwnerHub("legacy")` always includes `phone`. New devices can be linked into `legacy` with the env MCP token or with a pairing code from the legacy phone.
- `/mcp` resolves the bearer to an owner and builds the server for that owner's devices. A device token never works on `/mcp`, and a connector token never works on `/phone/*`.

## Synthesis decision
No arena runners were available, so two shapes were drafted here:
- (A) a registry DO that maps token hashes to owners and devices;
- (B) self-routing tokens with per-owner and per-device DOs.

B is the base. A puts every request through one singleton, and it keeps device names in two places. From A, B adopts treating legacy as a virtual owner.

## Tradeoffs accepted
- Anyone who knows the URL can enroll a new, empty owner. They see only their own phone. In exchange, setup is URL-only. Rate limits cap the abuse, which costs DO storage and our FCM quota.
- The token format exposes owner and device ids. They are random and carry no data. In exchange, auth needs no lookup table.
- The claim code is visible in the chat transcript, so a transcript reader could claim within 10 minutes if Grok Bot has not already. The token itself never appears there; it reaches Grok Bot only in the claim tool result.
- `list_devices` costs one DO call per device. Owners have a handful of devices.

## Alternatives considered
- Registry DO (A): every request crosses one global object, and device names would have two writers.
- Signed JWT tokens with no storage: they cannot be revoked without a denylist, which is the storage we were avoiding.
- Requiring an enroll secret: this brings back a second thing to type, which is exactly what the redesign removes.

## Open questions and risks
- Grok Bot has no deep link that adds an MCP server. `/v1/plugin/add` only takes a marketplace plugin id. The handoff is therefore a prefilled share (`ACTION_SEND text/plain` to `ai.x.grok.bot`). Whether Grok Bot can add the connector by itself from that message is untested.
- Revoking a device or a connector is not built yet: there is no `unlink_device` and no token list in the UI.

## Next implementation step
Add `parseToken`, `OwnerHub`, `Ticket` and `Gate`. Route `/enroll`, `/claim`, `/device/*` and `/mcp` through token resolution, then make `buildServer(owner)` select the device per call.
