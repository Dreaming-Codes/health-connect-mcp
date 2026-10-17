# How-to guides

Each guide solves one task. The guides assume a connected phone. To set one up from scratch, follow the [tutorial](tutorial.md). The [tool reference](reference/tools.md) lists every parameter.

## Install the released app with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. On the phone, open the **Get it on Obtainium** link in the [README](../README.md) and tap **Add**.
3. Install **Android Connect** from Obtainium.

The released app uses the maintainer's Firebase project. It connects only to the maintainer's Worker, which is the default **Worker URL**. To use your own Worker, build the app with your own Firebase project as the [tutorial](tutorial.md) shows.

## Read health data

1. On the **Health Connect** card, tap **Grant permissions**.
2. Allow every data type, and allow background reads.
3. Ask your assistant, for example "How did I sleep last night?"

The assistant calls `get_sleep`, `get_steps`, `get_heart_rate`, `get_exercise_sessions`, `get_weight`, `get_nutrition`, or `read_records`. Times without an offset are read in `timezone`, which defaults to `Europe/Amsterdam`.

## Read and act on notifications

1. On the **Notifications** card, tap **Grant notification access**.
2. Turn on **Android Connect**.

The assistant lists notifications with `list_notifications`. It taps a button or sends a reply with `perform_notification_action`, and dismisses with `dismiss_notification`. Android 15 hides one-time codes from listeners, so those notifications come back with `redacted: true`.

If a button opens an app screen, Android can block it while the phone is asleep. The `opens_activity` field marks those buttons. Reply and mark-as-read buttons are not affected.

## Send a notification with buttons

1. On the **Phone access** card, allow **Show assistant notifications and calls**.
2. Ask the assistant to send a notification with choices.

The assistant calls `send_notification` with up to three `actions`. An action with `reply` shows a text field. To collect the answer, the assistant calls `get_notification_responses` with the returned `notification_id`. Set `wait_seconds` to wait up to 20 seconds for the tap. Responses are kept for 7 days.

## Ring the phone like an alarm

1. On the **Phone access** card, allow **Alarms ring through Do Not Disturb**.
2. Ask the assistant to ring your phone.

The assistant calls `send_notification` with `alarm: true`. The alarm uses the alarm volume, so it rings in silent and vibrate mode. It rings through Do Not Disturb when Android Connect has Do Not Disturb access. By default it loops until you dismiss it, or until the assistant calls `dismiss_notification`.

If the alarm volume is 0, the result has a `warning` and the phone stays silent. Android Connect never changes the volume.

## Get a call from Grok Bot

1. On the **Phone access** card, allow **Show assistant notifications and calls**.
2. On Android 14 or later, also allow **Full-screen incoming assistant calls**.
3. Ask Grok Bot to call you when something happens.

Grok Bot calls `start_call` with its `agent_id` and a `reason`. The phone shows a full-screen call screen with the caller name and the reason. **Answer** opens a Grok Bot voice call with that agent. **Decline** or no answer within `ring_seconds` ends the ring. The tool returns `answered`, `declined`, or `missed`.

If the phone has a PIN, a pattern, or biometrics, unlock it after you tap **Answer**. Android does not let another app open over a secure lock screen.

## Get the phone's location

1. On the **Phone access** card, allow **Location**.
2. Allow **Location while the app is closed** and choose **Allow all the time**.

The assistant calls `get_location`. A fix newer than `max_age_s` comes back at once. Otherwise the phone waits up to `timeout_s` for a new fix. A push wakes the app in the background, so Android gives it a location only with **Allow all the time**.

## Read and send text messages

1. On the **Phone access** card, allow **Read and send SMS**.
2. If Android refuses, open **App info**, tap **⋮ > Allow restricted settings**, and allow again.

The assistant calls `list_threads`, `list_messages`, `send_message`, and `reply_to_thread`. Sent messages report `sent` and `delivered`. Only SMS and MMS are visible. Google Messages keeps RCS chats in its own storage, which other apps cannot read.

## Search contacts

On the **Phone access** card, allow **Contacts**.

The assistant calls `search_contacts` with a name or a number. With contacts access, `list_messages` and `list_threads` also show contact names.

## Add another phone

1. On the new phone, install the app, enter the same **Worker URL**, and tap **Connect**.
2. Tap **Register with Grok Bot** and send the message.

Grok Bot adds a second connector named after the new phone, for example `Android Connect (Pixel 9)`. Each connector reaches only its own phone.

## Upgrade a phone from 0.6.0 or earlier

Version 0.7.0 removes the `MCP_TOKEN` and `DEVICE_TOKEN_HASH` secrets and pairing. After the Worker is deployed, a phone set up with them must enroll again.

1. Install app 0.7.0 and open it. The app shows the **Connect** form.
2. Tap **Connect**, then tap **Register with Grok Bot**.
3. In Grok Bot, remove the old Android Connect connector.

## Build a signed release with GitHub Actions

The workflow `.github/workflows/release.yml` builds and signs the APK when you push a tag that starts with `v`. It then attaches `android-connect-<version>.apk` to a GitHub release, where Obtainium finds it.

1. Add these repository secrets:
   - `ANDROID_KEYSTORE_BASE64`: the keystore file, base64-encoded (`base64 -w0 release.jks`).
   - `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
   - `ANDROID_KEY_ALIAS`: the key alias.
   - `ANDROID_KEY_PASSWORD`: the key password.
   - `GOOGLE_SERVICES_JSON_BASE64`: `android/app/google-services.json`, base64-encoded.
2. Set `versionName` and `versionCode` in `android/app/build.gradle.kts`, and commit.
3. Push a tag that matches `versionName`, for example `git tag v0.7.0 && git push origin v0.7.0`.

The workflow fails if the tag and `versionName` differ. Use the same keystore for every release, or Android refuses the update.
