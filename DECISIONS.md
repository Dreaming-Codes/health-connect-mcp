# Decisions

Forks decided without waiting for review, each with its reason. Change any of them and the code follows.

## Phone tools (PR #2)
- **The location spec was designed here** (no spec was given). `get_location {max_age_s, timeout_s}` returns a cached fix if it is fresh enough, otherwise a current one. When woken by push, it needs "Allow all the time" location.
- **Alarm instead of play_sound and stop_sound.** It is folded into `send_notification(alarm)`, per Lorenzo. Since targetSdk 35 an app cannot change global DND, so alarms use a `setBypassDnd` channel when DND access is granted.
- **Alarm volume 0 is reported, not changed.** A warning is returned and the volume is never raised. That would be device control, which is out of scope.
- **`start_call` waits in the Worker** for the call outcome (up to `ring_seconds + 5` s). The phone answers right away and posts answered or declined as an event.
- **Notification dismiss reports `removed: false`** when Android has not confirmed removal within the wait window. It does not fail.
- **Every reply input is filled with the same `replyText`.** No `reply_key` is exposed.

## Deploy and test
- **Production runs `enrollment`** (version `89974460-f05b-48ab-9a84-0cda3d62c8b0`, 2026-10-17 02:50 PDT, migration v1 to v2). Lorenzo approved the Worker deploy. The existing connector and the 0.3.2 phone still answer `get_phone_status`.
- **The previous production versions are `0d28f247-a42b-4ab0-9982-a585b0682c7d`** (`notifications`, 01:52 PDT) and `c17bf7ea-d20b-4608-b3ef-0f747aa6cf69` (before PR #2).
- **The emulator uses a separate Worker** (`health-connect-mcp-e2e`) with its own tokens. Registering the emulator against production would replace the real phone's FCM registration.
- **A rollback is a new deploy, not a version rollback.** Migration v2 adds Durable Object classes, and Cloudflare refuses to roll a version back past a migration. The rollback deploys the `notifications` code with empty `OwnerHub`, `CodeTicket`, and `Gate` classes. It was rehearsed on the e2e Worker.
- **No KVM on the box.** The API 35 emulator was too slow under software emulation (system_server ANRs), so the live run uses a lighter API 33 Google APIs image.

## Enrollment (PR #3)
- **Self-routing tokens with per-owner and per-device Durable Objects**, rather than a central registry. Superseded in 0.7.0: owners are gone. See `docs/enrollment.md`.
- **Anonymous enroll is allowed and rate-limited.** It always creates a new, empty owner, so the user only types the URL.
- **MCP tokens are minted only by claim.** `/enroll` never returns an MCP token. Each claim creates a new connector token with a label, so no plaintext MCP token is stored anywhere.
- **Grok Bot handoff is a prefilled share** (`ACTION_SEND text/plain` to `ai.x.grok.bot`). The decompiled app has `/v1/plugin/add?id=<marketplace plugin id>`, `/v1/dictate?id=<agent>`, `/v1/open` and `/v1/agent`. Each takes only a single `id`, and none accepts a URL, a header or text. The manifest has an `ACTION_SEND text/plain` filter on `MainActivity`, handled by `SharedTextIntent` and then `startText`.
- **The chat sees a single-use claim code, never the token.** `GET /claim?code=` is accepted alongside POST for agents that can only fetch.
- **Device names live only in `PhoneHub`.** `list_devices` reads each device, so no name is copied into the owner.
- **The default device is the most recently seen one.** It changes whenever another device answers.
- **`get_phone_status` adds `name` only for enrolled devices**, so the legacy contract output stays byte-identical.

## Refactor and release prep (PR #4, PR #5)
- **Two PRs, stacked on `enrollment`.** PR #4 holds the behavior-preserving refactor, the comment removal, and the rename. PR #5 holds the docs, the license, the release workflow, and 0.6.0. The commits did not overlap, so the split costs nothing.
- **Comment Sicko rules applied by hand.** This environment has no subagent tool. Six comments were deleted. Two stay, because each one states a platform rule: Android 15 screen-recording sensitivity in `MainActivity.kt` and the FCM handler time budget in `Push.kt`.
- **`@Suppress("MissingPermission")` in `Location.kt` stayed open in PR #4.** It is fixed in the cleanup PR below.
- **The app keeps the maintainer Worker as its default URL.** The released APK carries the maintainer's Firebase config, so it only works with a Worker that holds that project's service account. Self-hosters build their own APK, as `docs/tutorial.md` shows.
- **The release workflow reads five secrets and none are set.** `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `GOOGLE_SERVICES_JSON_BASE64`.
- **The history scan found nothing to rewrite.** gitleaks over every branch reported no leaks. The real tokens, the service-account key ID, `.secrets.local`, `.firebase-sa.json`, `google-services.json`, and the keystore never appear in any commit.
- **The e2e Worker runs the top of this stack.** Production stays on `enrollment`, because the refactor changes no wire behavior except the root text.
- **The live emulator run stopped after about 55 minutes.** Under software emulation (no KVM), the API 33 image's `system_server` restarted twice during the 0.6.0 install and the contacts provider never came back. The sim-phone e2e, the contract diff, and the production `get_phone_status` check are the evidence for this stack. No screenshots were captured.

## Cleanup (poteto-cleanup)
- **One cast at the MCP SDK boundary.** `registerTool` types its callback with a conditional type that TypeScript cannot resolve through a generic shape. The SDK has already validated the arguments, so `register` casts once and every tool callback gets the type inferred from its zod shape.
- **The method registry stays per handler.** Each handler maps its own method names, and `dispatch` merges them. One central map would put HealthHandler's range parsing into the registry. `test/methods.mjs` checks that the Worker and the app name the same 19 methods.
- **No generated registry across TypeScript and Kotlin.** A code generator adds a build step for 19 names. The check script catches drift without one.
- **`RECORD_TYPES` stays at 23 names.** The app reads 11 of them, and the other 12 fail with `bad_params`. Shrinking the enum changes the published tool schema, so it is an open item and not part of a behavior-preserving PR.
- **The debug receiver reports the same error codes as a push.** It now uses `errorJson`, so a `SecurityException` reads `permission_denied` instead of `SecurityException`. Only debug builds have the receiver, and no test reads its codes.
- **`BridgeScreen` was not split further.** Its connect form and connection panel share most of their state. Hoisting them into parameters adds a layer without removing any, and no emulator was available to check the screen after the move. Only the claim-code dialog moved out.
- **Location permission is checked per provider.** Before Android 12, the GPS provider needs fine location, so an approximate-only phone now uses the network provider. A `SecurityException` from `LocationManager` becomes `location_permission_missing`.

## Enrollment only (0.7.0)
- **One phone per connector.** `OwnerHub`, pairing codes, `list_devices` and the `device` argument are removed. Each phone's **Register with Grok Bot** claims its own connector, named `Android Connect (<device name>)`.
- **Connector tokens live in the phone's `PhoneHub`.** A connector token is `hco_<device_id>_<secret>`, so the phone that issued the claim code also checks the token. No fact has two writers.
- **The legacy secrets are gone.** The Worker no longer reads `MCP_TOKEN` or `DEVICE_TOKEN_HASH`. Deleting the secrets after deploy is a documented step, not something this PR does.
- **`OwnerHub` is deleted by migration `v3`.** `deleted_classes` is one-way, and `docs/enrollment.md` has the rollback.
- **`/admin/fcm-selftest` is removed.** It needed the `MCP_TOKEN` secret, and `get_phone_status` reports the last push latency.
- **Claim tickets carry no kind.** A pairing code issued before the deploy also works on `/claim`, and it yields a connector for the phone that issued it. Only that phone could show the code, which is the same trust as a claim code, and it expires within 10 minutes.
- **`/enroll` ignores `Authorization`.** It used to link a phone to the bearer's owner. Every enrollment now creates a new phone.
- **An install without a device id shows the Connect form.** A 0.6.0 phone set up with a pasted device token has no device id, so it re-enrolls on first open of 0.7.0 instead of failing every push.
- **Version 0.7.0.** Old phones and old connectors stop working, so the version moves up a minor step.

## Live-test fixes (0.7.0)
- **The app re-registers after an update** through a `MY_PACKAGE_REPLACED` receiver. Registering on every push would add a request to each tool call.
- **`dismiss_notification` takes exactly one of `key` or `notification_id`.** One tool keeps one verb. With `notification_id` the app cancels its own notification, which needs no notification access.
- **`battery` is replaced, not kept beside `battery_percent`.** Two fields for one fact invite the same misreading.
- **Both event times stay, renamed.** `phone_time` is when the user tapped, by the phone's clock. `received_at` is the Worker's clock, which `since` filters on. They differ by delivery time and clock skew, so dropping either loses a fact.
- **One time format on both sides**, `YYYY-MM-DDTHH:mm:ss.SSS±HH:MM`. Kotlin's `OffsetDateTime.toString()` drops zero seconds and varies the fraction, so the app now uses a fixed pattern. Error messages such as `last seen …` stay in UTC: they are prose, not fields.
- **`start_call` keeps `timezone` on the Worker.** The phone has no time to format, so the push carries the same params as before.
