# Android Connect

Android Connect lets Grok Bot, or any MCP client, read and act on your Android phone. A Cloudflare Worker serves the MCP tools. When a tool needs the phone, the Worker wakes the companion app with a Firebase Cloud Messaging (FCM) push, and the app answers over HTTPS. Nothing runs on the phone between requests.

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Dreaming-Codes/health-connect-mcp)

## What your assistant can do

- Read Health Connect data: steps, sleep, heart rate, workouts, weight, nutrition, and any other record type.
- Read, dismiss, and act on notifications, including inline replies.
- Send its own notifications with buttons and reply fields, or ring like an alarm through silent mode.
- Call you with a full-screen incoming-call screen that opens a Grok Bot voice call when you answer.
- Get the phone's location.
- Read SMS and MMS threads, send texts, and reply to threads.
- Search your contacts.
- Connect several phones, each as its own connector.

Each capability needs its own Android permission, and the app asks for each one on its own card.

## Documentation

- [Tutorial: run your own Android Connect](docs/tutorial.md) sets up a Worker, a Firebase project, the app, and the Grok Bot connector from scratch.
- [How-to guides](docs/how-to.md) cover each feature, extra phones, upgrades, and release builds.
- [MCP tool reference](docs/reference/tools.md) lists every tool and parameter. [Phone protocol reference](PROTOCOL.md) describes the Worker-to-phone messages.
- [About the architecture and privacy](docs/architecture.md) explains how a request flows and what is stored where.
- [Enrollment design](docs/enrollment.md) explains device tokens, connector tokens, and claim codes.

## Repository layout

- `src/` holds the Worker: `index.ts` routes HTTP, `tools.ts` defines the MCP tools, `phone-hub.ts` is the per-phone Durable Object, and `access.ts` holds tokens, claim codes, and the claim-code and rate-limit Durable Objects.
- `android/` holds the Kotlin and Jetpack Compose app. Its application id is `dev.dreamingcodes.hcmcp`.
- `test/` holds the simulated-phone suite (`npm test`), the wire-contract snapshot (`npm run test:contract`), and the live emulator run (`test/live-emulator.mjs`).

## License

Android Connect is licensed under the [Apache License 2.0](LICENSE).
