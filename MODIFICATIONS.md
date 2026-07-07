# Modifications in TeleGlatt (vs. upstream Telegram for Android)

TeleGlatt is a fork of [Telegram for Android](https://github.com/DrKLO/Telegram).
Per GPLv2 §2(a), this file records the significant changes made to the upstream
source. Modified source files also carry inline `Askan:` notices at the changed
sections.

## Added — content-filtering client layer
New package `org.telegram.messenger.askan`:

- `AskanFilter.java` — fetches per-user allow/block permissions from the Askan
  backend and enforces them in the client (hides non-allowed chats/channels).
- `AskanAdsManager.java`, `AdsBannerView.java` — server-driven in-app banner.
- `AskanUiHelper.java` — filtered-client UI adjustments.
- `ApkUpdater.java`, `ForceUpdateActivity.java`, `UpdateBannerView.java` —
  update flow (direct sideload downloads an APK; **Google Play installs use the
  Play In-App Update API and never self-install**).

## Changed — upstream files
- `AndroidManifest.xml` / `config/**` — app identity (`com.teleglatt`), name and
  logo (unofficial, non-Telegram), permission set.
  - `ACCESS_BACKGROUND_LOCATION` removed.
  - Play flavors (`afat`, `bundleAfat*`) strip `REQUEST_INSTALL_PACKAGES` and
    `READ_CALL_LOG`; the direct `arm64` flavor keeps them.
- `ApplicationLoader.java` — keep-alive background connection defaults on
  (push delivery for a non-Telegram Firebase sender).
- `MessagesController.java` — Telegram Premium / Stars purchase surfaces are
  hidden (filtered client; no in-app digital-goods purchase).
- `BuildVars.java` — own `api_id`/`api_hash`.

## Not included in this repository
The Askan **backend** (filtering policy, admin dashboard, database) is a separate
service that communicates with this client over the network. It is not derived
from Telegram and is not covered by this client's GPL obligation.
