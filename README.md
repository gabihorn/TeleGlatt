# TeleGlatt

**TeleGlatt is an unofficial, filtered Telegram client for Android**, published by
Askan for communities that need a content-filtered messaging experience. It is a
fork of the open-source [Telegram for Android](https://github.com/DrKLO/Telegram)
and is **not affiliated with, endorsed by, or operated by Telegram FZ-LLC**.

Package: `com.teleglatt`

## License

TeleGlatt is distributed under the **GNU General Public License v2** (see
[`LICENSE`](LICENSE)), the same license as upstream Telegram. In accordance with
the GPL, the complete corresponding source code for the client — including our
modifications — is published in this repository. A pointer to this source is also
provided to end users in our
[privacy policy](https://pub-580b9c5580174ab08270b6b773af1134.r2.dev/privacy.html).

## Relationship to Telegram

This fork follows Telegram's requirements for third-party clients:

1. It uses **its own `api_id`/`api_hash`** (not Telegram's).
2. It does **not** use the name "Telegram" — the app is named **TeleGlatt** — and
   makes clear to users that it is unofficial.
3. It does **not** use Telegram's standard logo.
4. Its source is published to comply with the GPL (this repository).

The Telegram **name, logo, and trademarks** remain the property of Telegram FZ-LLC.

## What this fork changes

TeleGlatt adds a server-driven content-filtering layer on top of the standard
Telegram client. A high-level summary of the modifications is in
[`MODIFICATIONS.md`](MODIFICATIONS.md). The filtering **policy** (which content is
allowed) is decided by a separate backend service and is **not** part of this
client; this repository contains only the GPL-covered Android client.

## Build

This is a standard Telegram-fork Gradle build.

- Android Studio (recent), Android NDK r27, Android SDK 35.
- Before building your own signed artifacts, replace the dummy `release.keystore`,
  `google-services.json`, and the `api_id`/`api_hash` in `BuildVars.java` with
  your own.

Build variants:

- **Play Store (AAB):** `./gradlew :TMessagesProj_App:bundleAfatRelease`
  (no in-app APK self-update; uses Google Play In-App Update).
- **Direct APK (sideload):** `./gradlew :TMessagesProj_App:assembleArm64Release`.

### API / protocol documentation

- Telegram API: https://core.telegram.org/api
- MTProto protocol: https://core.telegram.org/mtproto
- Security guidelines: https://core.telegram.org/mtproto/security_guidelines
