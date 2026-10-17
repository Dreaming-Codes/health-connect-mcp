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
- **Self-routing tokens with per-owner and per-device Durable Objects**, rather than a central registry. See `docs/enrollment.md`.
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
- **`@Suppress("MissingPermission")` in `Location.kt` stays open.** The check lives in `handle()` behind `Capabilities.location`, where lint cannot follow it. A fix needs lint to see the check in each caller, which is a reshape of the handler and not part of a no-behavior-change PR.
- **The app keeps the maintainer Worker as its default URL.** The released APK carries the maintainer's Firebase config, so it only works with a Worker that holds that project's service account. Self-hosters build their own APK, as `docs/tutorial.md` shows.
- **The release workflow reads five secrets and none are set.** `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `GOOGLE_SERVICES_JSON_BASE64`.
- **The history scan found nothing to rewrite.** gitleaks over every branch reported no leaks. The real tokens, the service-account key ID, `.secrets.local`, `.firebase-sa.json`, `google-services.json`, and the keystore never appear in any commit.
- **The e2e Worker runs the top of this stack.** Production stays on `enrollment`, because the refactor changes no wire behavior except the root text.
