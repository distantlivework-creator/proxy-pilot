# Proxy Pilot for Android

The Android app is a companion to the official Telegram app. It does not try to
modify another application's private settings.

## Target flow

1. Authorize a Telegram user locally through TDLib. Proxy Pilot has no separate
   user account and never sends the Telegram session to the Proxy Pilot server.
2. When monitoring is enabled for a subscribed channel, persist its current last
   message ID and the activation time as the baseline.
3. Process only messages newer than that baseline. Extract `https://t.me/proxy`
   and `tg://proxy` links, validate them, and deduplicate by host, port, and secret.
4. Call TDLib `pingProxy` on the Android device three times with bounded
   concurrency. Keep every proxy with at least one successful response:
   two or more responses are `AVAILABLE`, one is `UNSTABLE`, and none is
   `UNAVAILABLE`.
5. Open a selected proxy with a standard Telegram deep link. The user confirms
   adding it in the official Telegram app.

## Privacy and security boundaries

- Reading the user's subscribed channels requires Telegram authorization.
- TDLib database and channel cursors stay in Android app-private storage.
- Existing history is ignored; the baseline is created before update processing.
- Server latency is only a preliminary catalogue signal. It must never be shown
  as device latency.
- An official TDLib build pinned to an audited commit is required. Do not ship an
  unreviewed third-party native binary in an app that holds a Telegram session.

## Build prerequisites

- JDK 17
- Android SDK/NDK compatible with the project
- a pinned official TDLib build with JNI
- Telegram `api_id` and `api_hash` supplied through local/CI secrets, never
  committed to Git

