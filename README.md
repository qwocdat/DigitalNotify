# DigitalNotify (prototype 0.1)

An Android-native notification listener and floating Liquid Glass-style banner prototype.

## What is implemented

- Kotlin `NotificationListenerService` receives Android notification events.
- A per-app allowlist stored locally in `SharedPreferences`.
- Permission shortcuts for Notification Access and display-over-other-apps.
- Floating overlay banner with app name, title, body, close action and tap-to-open source app.
- Spring-like entrance motion and a short AGSL procedural highlight sweep on Android 13+.
- Fallback gradient glass styling on Android 8–12.
- GitHub Actions workflow to build a debug APK.

## Important limitations

- Android only exposes information present in a posted notification. This app does not read private databases or hidden chats inside other apps.
- Banner content is processed locally; this prototype has no network or cloud upload code.
- The shader creates a translucent procedural glass surface, rim lighting and a sweep. It does **not** sample pixels from another app's window for true optical refraction. Android does not provide arbitrary access to other apps' framebuffers. Real background distortion would require a separately permitted and carefully designed screen-capture flow, which is not included here.
- Android/OEM battery management can interrupt background components. Notification listener reconnection is managed by Android, but no app can promise uninterrupted delivery on every device.
- `QUERY_ALL_PACKAGES` is included for an installable prototype app selector. Google Play distribution requires a valid policy justification for broad package visibility.

## Build on GitHub (phone-friendly)

1. Create a new repository named `DigitalNotify` and upload the contents of this folder to the repository root.
2. Open **Actions** and run **Build DigitalNotify APK** (or push to `main`/`master`).
3. Open the completed workflow run and download the `DigitalNotify-debug-apk` artifact.
4. Install `app-debug.apk` on your Android phone.
5. Open DigitalNotify, enable Notification Access, allow display-over-other-apps, then select the apps to track.

The project uses Gradle 8.9, JDK 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile SDK 35 and min SDK 26.
