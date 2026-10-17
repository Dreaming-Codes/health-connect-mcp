# About the architecture and privacy

Android Connect answers one question: how can an assistant in the cloud use a phone that sleeps most of the time, without keeping a copy of the phone's data? The answer is a live pull. The Worker holds no health data, no notifications, no messages, no contacts, and no locations. It asks the phone when a tool needs something, and it forwards the answer to the MCP client.

## How a request flows

```
MCP client ──HTTPS, connector token──▶ Worker /mcp ──▶ PhoneHub (Durable Object, one per phone)
                                                          │ FCM data message: id, method, params
                                                          ▼
                                       phone: PushService.onMessageReceived
                                                          │ reads Health Connect, notifications, SMS, ...
                                                          ▼
                       Worker /phone/result/<id> ◀──HTTPS, device token── phone
```

1. The MCP client calls a tool on `/mcp` with its connector token.
2. The token names its phone, and that phone's `PhoneHub` checks the token.
3. The `PhoneHub` `PhoneHub` sends a high-priority FCM data message with a request id, the method name, and the parameters.
4. Android wakes the app. The app runs the method within about 15 seconds and posts the result to `/phone/result/<id>` with its device token.
5. `PhoneHub` matches the id to the waiting request and returns the result to the MCP client. If no answer arrives within `PUSH_TIMEOUT_MS` (20 seconds by default), the tool fails with a timeout.

Push is the only path. An earlier version kept a WebSocket open from a foreground service, and it cost battery and showed a permanent notification. A data message costs nothing while the phone is idle, and Android lets the app run briefly for each one.

## What is stored where

The Worker stores only what it needs to route and authenticate:

- `PhoneHub`, per phone: the device token hash, the connector token hashes, the device name, the FCM token, the app version, the last-seen time, the last push latency, and a cached Google OAuth token. It also keeps taps and replies on assistant notifications and call outcomes for 7 days, because `get_notification_responses` and `start_call` return them later.
- `CodeTicket`, per claim code: the device id, until the code is used or 10 minutes pass.
- `Gate`, per IP address: a counter for the enrollment and claim rate limits.

The phone stores the Worker URL and the device token in `EncryptedSharedPreferences`. The notification index lives in memory and is gone when the process ends.

Neither side logs values. The app's status card shows only method names and times. FCM carries the method and parameters through Google, so a parameter such as an SMS body to send does pass through FCM. Results never do, because they go straight to the Worker over HTTPS.

## Why tokens carry their own scope

A device token looks like `hcd_<device id>_<secret>`, and a connector token looks like `hco_<device id>_<secret>`. The id part tells the Worker which Durable Object holds the hash, so the Worker needs no central table of tokens and no single object that every request touches. Only SHA-256 hashes of the secrets are stored.

The kinds are separate on purpose. A device token cannot call `/mcp`, and a connector token cannot post as a phone. A stolen phone token therefore cannot read another phone, and a leaked connector cannot impersonate a phone.

## Why anyone can enroll

Anyone who knows the Worker URL can tap **Connect** and enroll a phone. A connector reaches exactly one phone, and only that phone can issue the code that claims it, so a stranger reaches only the phone they enrolled. Rate limits cap the cost at 10 enrollments and 20 claims per IP address per hour.

The alternative was a shared enrollment secret typed on every phone. That brings back the copy-a-secret step that enrollment removed, so the open design wins for a personal Worker. If you run a Worker for other people, watch its Durable Object usage.

## Why Grok Bot gets a claim code and not a token

Grok Bot has no deep link that adds an MCP connector, so **Register with Grok Bot** shares a text message into Grok Bot. A chat transcript is a poor place for a long-lived key. The message carries a single-use code instead, and Grok Bot trades it at `/claim` for the connector URL and key. Whoever redeems the code first within 10 minutes gets the connector, which is the main risk of this design.

## Why the released app needs the maintainer's Worker

FCM delivers a message only when the sender and the app belong to the same Firebase project. The released APK carries the maintainer's `google-services.json`, so only a Worker that holds the maintainer's service account can wake it. To use your own Worker, build the app with your own Firebase project. The [tutorial](tutorial.md) does exactly that.

## One connector per phone

Each phone gets its own connector, named after the phone. Version 0.6.0 could put several phones behind one connector with pairing codes, and it kept the single-phone `MCP_TOKEN` and `DEVICE_TOKEN_HASH` secrets. Version 0.7.0 removes both, so the Worker has one way to authenticate and no tool needs a device argument. [Enrollment](enrollment.md) describes the upgrade.
