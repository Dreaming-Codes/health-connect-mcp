# Tutorial: run your own Android Connect

In this tutorial, we deploy our own Android Connect Worker, build the app against our own Firebase project, connect a phone, and add the connector to Grok Bot. At the end, Grok Bot answers "What is my phone's status?" with live data from the phone.

We need about 30 minutes and these things:

- A Cloudflare account. The Workers Free plan is enough.
- A Google account for Firebase.
- Node.js 22 or later.
- JDK 17 and the Android SDK with platform 36, or Android Studio.
- An Android 9 or later phone with Grok Bot installed, and a USB cable or wireless debugging.

## Get the code

First, clone the repository and install the Worker dependencies:

```sh
git clone https://github.com/Dreaming-Codes/health-connect-mcp.git
cd health-connect-mcp
npm install
```

`npm install` ends with a summary line such as `added 120 packages`.

## Create the Firebase project

The Worker sends FCM messages, and the app receives them. Both sides must belong to the same Firebase project.

1. Open the [Firebase console](https://console.firebase.google.com/) and create a project. Google Analytics is not needed.
2. Add an Android app with the package name `dev.dreamingcodes.hcmcp`.
3. Download `google-services.json` and save it as `android/app/google-services.json`.
4. Open **Project settings > Service accounts** and click **Generate new private key**. Save the file as `.firebase-sa.json` in the repository root.

Both files are in `.gitignore`. Keep them out of commits, because the service account key can send pushes to every phone that runs your build.

## Deploy the Worker

Now we log in to Cloudflare, deploy, and give the Worker the service account:

```sh
npx wrangler login
npx wrangler deploy
npx wrangler secret put FCM_SERVICE_ACCOUNT < .firebase-sa.json
```

`wrangler deploy` prints the Worker URL, for example `https://health-connect-mcp.<your-subdomain>.workers.dev`. Open that URL in a browser. The page shows `android-connect ok`.

## Build and install the app

The release build needs a signing key. We create one, then point Gradle at it:

```sh
cd android
keytool -genkeypair -keystore release.jks -alias android-connect -keyalg RSA -keysize 4096 -validity 10000
cat > keystore.properties <<'EOF'
storeFile=release.jks
storePassword=<the store password you chose>
keyAlias=android-connect
keyPassword=<the key password you chose>
EOF
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Gradle ends with `BUILD SUCCESSFUL`, and `adb install` prints `Success`. Keep `release.jks` safe. Android installs an update only when it is signed with the same key.

## Connect the phone

Open **Android Connect** on the phone.

1. Replace the text in **Worker URL** with your Worker URL.
2. Change **Device name** if you want a friendlier name than the model number.
3. Tap **Connect**.

The status card turns green and shows **Ready**. The phone now has its own device token, and the Worker can wake it with a push.

## Add the connector to Grok Bot

Tap **Register with Grok Bot**. Grok Bot opens with a prefilled message that contains a claim code such as `K7QM-2XRA`. Send the message.

Grok Bot trades the code for the connector address and its key, then adds the connector. The code works once and expires after 10 minutes. The key never appears in the chat. If Grok Bot is not installed, the app shows the code and a **Copy message** button instead.

## Grant access

Scroll down to the permission cards and allow what you want your assistant to use:

- **Health Connect**: tap **Grant permissions** and allow every data type, including background reads.
- **Notifications**: tap **Grant notification access** and turn on **Android Connect**.
- **Phone access**: tap **Allow** next to each feature you want.

If Android refuses a permission or a switch is greyed out, open **App info**, tap **⋮ > Allow restricted settings**, and try again. Android applies this rule to sideloaded apps for SMS and notification access.

## Ask Grok Bot

Ask Grok Bot: "What is my phone's status?"

Grok Bot calls `get_phone_status`. The answer shows the app version, the Android version, the battery percentage, and the permissions you granted. The phone answers in one or two seconds.

We now have our own Worker, our own Firebase project, and a phone that Grok Bot can reach. Next, try the [how-to guides](how-to.md) for each feature, or read [about the architecture and privacy](architecture.md).
