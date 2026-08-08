# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A diagnostic Android app (package `com.ytmprobe`), not a production app. It exists to
answer specific unknowns about controlling YouTube Music's `MediaSession` from another
app, in order to decide the architecture of a separate "resume where I left off" app
for Android Auto. Read `README.md` (the probes and how to run them) and `FINDINGS.md`
(what's been proven, dead ends ruled out, and the resulting design) before making
non-trivial changes — most "why is this here" questions are answered there, especially
the "Traps and gotchas" section at the end of `FINDINGS.md`.

## Build / install / run

No Android Studio needed or used.

```bash
./build.sh
```

This writes `local.properties` (defaults `ANDROID_HOME` to
`/opt/homebrew/share/android-commandlinetools`), pins `JAVA_HOME` to JDK 17 or 21
(AGP 8.5/Gradle 8.7 do not support JDK 23+, and fail with a bare version number as the
entire error message), generates the Gradle wrapper via `gradle wrapper` if missing,
accepts SDK licenses, runs `./gradlew assembleDebug --no-daemon`, and installs +
launches on an attached device if one is present.

Manual equivalents:
```bash
./gradlew assembleDebug --no-daemon      # build only
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ytmprobe/.MainActivity
```

Retrieve the on-device log (see "Logging" below):
```bash
./pull-log.sh [outfile]      # defaults to probe.log, then cats it
```

There are no automated tests in this project — verification is done by running probes
on a real device (an emulator cannot complete the Android Auto connection tests) and
reading the resulting log.

First run on a device: tap "Grant Notification Access" in the app and enable YTM Probe
in the system list. `MediaSessionManager.getActiveSessions()` throws `SecurityException`
without it, and every probe fails silently into that branch.

Samsung devices: battery optimization kills the background tracking service overnight.
Set Settings → Apps → YTM Probe → Battery → **Unrestricted** or the session logger
won't survive to record anything.

## Architecture

Single Gradle module (`app/`), all Kotlin, no fragments/compose/DI framework — one
`Activity` with views built programmatically in `MainActivity.kt`. Everything talks to
YouTube Music (`com.google.android.apps.youtube.music`) through the system
`MediaSessionManager`/`MediaController` APIs; this app never plays audio itself.

Core insight driving the whole design: **YTM's `MediaSession` exposes no track
identity** — `METADATA_KEY_MEDIA_ID` is absent and every queue item's `mediaId` is
null (verified by probes A and E). So "which song is this" has to be reconstructed by
title/artist/duration search against YTM's own (unofficial, unauthenticated) search
backend. That reconstruction is the reason `Resolver.kt` exists at all.

| File | Role |
|---|---|
| `Probes.kt` | The probes (A, B1, C, D, E, A+) plus shared session helpers (`ytmController`, `hasNotificationAccess`). Each probe is self-contained and logs its own verdict — start here to understand what's been validated and why. |
| `Resolver.kt` | title+artist(+album+duration) → YouTube videoId, via YTM's InnerTube search endpoint (the same one `ytmusicapi` wraps), called directly with no auth/API key. Includes the scoring function (title/artist/album/duration weighted, `ATV`/`OMV`/`UGC` release-type bonus/penalty) used to pick the best candidate. |
| `Store.kt` | `SharedPreferences`-backed persistence: last-seen track + a `title|artist -> videoId` resolution cache so a song is only ever resolved once. |
| `Favorites.kt` | Passive favorites list built from `USER_RATING` as tracks play. Capture rules matter: `liked == false` (explicit thumbs-down) removes an entry, `liked == null` (never rated) does nothing — a never-rated track and a cleared rating are indistinguishable, so neither may delete a favorite. |
| `SessionLogger.kt` | Foreground `Service`; the only poller in the app. Logs YTM session appear/disappear (for the car-connect timing question) and, on the same 10s poll, applies the favorites capture rules. |
| `ResumeCarAppService.kt` | Android Auto surface (`androidx.car.app`) showing 5 random resolved favorites; tapping one sends `playFromUri` to YTM's live session (proven by probe C). Category `IOT` because Car App Library has no general "utility" category. |
| `NotifListener.kt` | Deliberately empty `NotificationListenerService`. Its mere declaration is what makes the app eligible for Notification Access, which is required to call `getActiveSessions()` — no notification is ever read. |
| `ProbeLog.kt` | Append-only logger shared by the activity and the background service; writes to both logcat (`adb logcat -s YTMProbe`) and a file the UI polls via a listener callback. |
| `MainActivity.kt` | Day-to-day screen: a passive status strip (YTM session + notification-access warning, replacing an old "Status" button), an inline favorites list shaped exactly like `ResumeCarAppService.FavoritesScreen` (same `ROWS` count, tap to play directly via probe C; Refresh does a quiet resolve pass before reshuffling so tracks liked mid-session show up immediately, not just what was resolved at launch) as a rehearsal of the AA surface, tracking toggle, and a collapsible log (collapsed by default) capped to the last `MAX_LOG_LINES` for render cost, appended incrementally per line rather than re-reading the whole file on every write. |
| `DiagnosticsActivity.kt` | Setup (Grant Notification Access), everything that already answered its question for good — probes A, E, B1, D (see FINDINGS.md; B1/D are proven dead ends for cold start) — kept only to re-verify after a YTM update, plus manual videoId entry/paste/copy and forced-candidate override for edge cases the main screen's automatic pickers can't cover. |

Log path on device: `/sdcard/Android/data/com.ytmprobe/files/probe.log`.

### Key traps to know before touching `Resolver.kt` or the metadata-reading code

- **`org.json`'s `optString("text")` silently coerces objects.** If a `text` key's
  value is `{"runs":[...]}` rather than a string, `optString` returns the whole JSON
  blob as text instead of failing. Always check `node.opt("text") is String` before
  using it (see `Resolver.texts()`). This produced no error and quietly capped every
  score — the single hardest bug in the project's history.
- **`dumpsys` under-reports session state; `MediaController` does not.** Queue
  contents and `METADATA_KEY_DURATION` are invisible via `dumpsys` but present via a
  real `MediaController`. Don't conclude a field is absent from `dumpsys` output alone.
- **An advertised playback action isn't necessarily usable.** `PLAY_FROM_MEDIA_ID` is
  in YTM's actions bitmask, but there is no `mediaId` anywhere to pass it — it's dead.
  Verify a capability by using it, not by checking the bitmask.
- **`playbackState.position` is a snapshot**, paired with `lastPositionUpdateTime`.
  Extrapolate with `elapsedRealtime()` while playing or the stored position goes stale
  (see the position calculation in `Probes.probeAPlus`).
- **Clipboard reads require window focus** (Android 10+) — `MainActivity`'s paste
  button checks `hasWindowFocus()` first because a keyboard/dialog stealing focus makes
  `primaryClip` come back null with no error.
