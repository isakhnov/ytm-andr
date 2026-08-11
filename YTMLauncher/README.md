# YTM Launcher

A launcher/resume app for YouTube Music — a favorites picker on your phone and on
Android Auto, plus a lock-screen shortcut and automatic resume, all driven entirely
through YTM's own `MediaSession`. This app never plays audio itself; it only tells YTM
what to play.

This is the production rewrite of `../ytmprobe`, the diagnostic project that answered
the open questions this app's design depends on (does YTM expose track identity, does
`playFromUri` work on a live session, can a sideloaded app reach Android Auto at all).
See `../ytmprobe/FINDINGS.md` for that investigation's full history, and this project's
`CLAUDE.md` for the architecture and the design decisions carried over from that work.

## What it does

- **Passively captures favorites.** Thumbs-up a track in YTM and it shows up here —
  no manual add step.
- **One-tap play from your phone.** `LaunchActivity` shows one shuffled favorite per
  genre, reshuffled every time you open it. Tap to play, swipe to remove or tag genre.
- **Lock-screen picker.** A notification with a few random favorites, tappable without
  unlocking — works whether YTM is already running or fully closed.
- **Resume where you left off.** The last track you played from here is remembered and
  auto-resumed the next time YTM's session reappears (e.g. after a drive).
- **Android Auto.** The same favorites list, live on your head unit — a legacy
  `MediaBrowserServiceCompat` surface, sideloaded via Developer Mode's "Unknown sources"
  toggle. No Car App Library, no Google review process.

## Build

```bash
brew install --cask temurin      # JDK 17+, if you don't have it
brew install gradle              # only needed once, to generate the wrapper

./build.sh
```

`build.sh` writes `local.properties`, generates the Gradle wrapper, accepts SDK licenses,
builds, installs, and launches. Defaults `ANDROID_HOME` to
`/opt/homebrew/share/android-commandlinetools` — override with an env var if yours lives
elsewhere.

Run the unit tests any time with `./gradlew testDebugUnitTest --no-daemon` — no device
needed, they run on the JVM via Robolectric.

## First run

1. Install and open the app. It'll ask for the `POST_NOTIFICATIONS` permission
   (Android 13+) — allow it, or the lock-screen picker and the tracking notification
   are silently suppressed.
2. Open Settings (the 3-dot menu) → **Grant Notification Access**, and enable
   "YTM Launcher" in the system list that opens. This is required —
   `MediaSessionManager.getActiveSessions()` throws without it, and every feature that
   needs to see or command YTM's session silently does nothing.
3. **Migrating from `ytmprobe`?** See "Coming from ytmprobe" below before you go any
   further — importing your existing favorites is much easier before you've built up a
   second, separate list here.
4. Play something in YTM and thumbs it up. It should show up here within ~10 seconds
   (Settings' tracking toggle needs to be on — it is by default).
5. Settings → **Resolve favorites now** turns each favorite's title/artist into a
   playable YouTube videoId (YTM exposes no track ID directly — this searches YTM's own
   catalog and picks the best match). Do this once after adding a batch of new
   favorites; it also runs quietly in the background on every `LaunchActivity` open.
6. Settings → **Tag genres automatically** assigns each resolved favorite one of a fixed
   set of genres (manual trigger only, by design — it does not run automatically).

## Coming from `ytmprobe`

`ytmprobe`'s hand-curated favorites (hand-corrected videoIds, hand-assigned genres) don't
carry over automatically — the two apps have different package names and separate
storage. To bring them over:

```bash
# 1. Stop ytmprobe from writing mid-migration
#    (revoke its Notification Access in system settings first, then:)
adb shell am force-stop com.ytmprobe

# 2. Pull its data (exec-out, not shell — shell can corrupt the XML via CRLF translation)
adb exec-out run-as com.ytmprobe cat shared_prefs/ytmprobe.xml > ytmprobe.xml
```

Reshape the `favorites`/`cache`/`last`/`mixSeed`/`autoContinueEligible` JSON values out of
that XML into the flat JSON object `Backup.export()`/`Backup.import()` use (one
SharedPreferences key per JSON key), push it to the device, then — **before opening
YTM Launcher for the first time** — install the app and use Settings → Backup →
**Import favorites** to load it. Opening the app first caches an empty in-memory
favorites map that would silently win over the import.

Once you've confirmed the import worked (Settings → **Show favorites**), you can leave
`ytmprobe` installed or run `pm disable-user com.ytmprobe` to avoid two apps racing to
control the same YTM session. Full detail on why each of these steps matters is in
`CLAUDE.md`.

## Android Auto

Connect your phone, then in Android Auto's own settings: **Customize launcher** →
enable "YTM Launcher". You'll see the same favorites list as the phone screen. Tap one
to play it on the head unit through YTM.

**One caveat, documented in full in `CLAUDE.md`:** playing a favorite while YTM already
has a live session is proven to work on real hardware. Playing one while YTM is fully
closed (a true cold start, mid-drive) is not yet verified — it may hit the same
"Could not load your selection" error this feature's prototype (`../AA-Test`) hit before
switching to commanding a live session directly. If that happens, you'll see a clear
error on the head unit rather than a silent failure; open YTM once from the phone and
try again.

## Retrieving the log

```bash
./pull-log.sh [outfile]      # defaults to probe.log, then cats it
```

Or live: `adb logcat -s YTMProbe`. Settings also has an in-app collapsible log viewer.

## Files

See `CLAUDE.md` for the full file-by-file breakdown and the reasoning behind each
non-obvious design choice (targetSdk pinned to 34, why `AutoMediaService` drops
`FLAG_HANDLES_MEDIA_BUTTONS`, why genre tagging is manual-only, etc).
