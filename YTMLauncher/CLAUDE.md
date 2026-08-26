# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`YTMLauncher` (package `com.ytmlauncher`) is the daily-driver app: a launcher/resume
surface for YouTube Music, controlled entirely through YTM's own `MediaSession` — this
app never plays audio itself. It's a trimmed, hardened rewrite of the sibling probe
project `../ytmprobe`, which answered the open questions (does YTM expose track identity,
does `playFromUri` work on a live session, can a sideloaded app reach Android Auto) that
this app's architecture depends on. Read `../ytmprobe/FINDINGS.md` before making
non-trivial changes here — especially its "Traps and gotchas" section — since most of
this app's design choices (why `Resolver.kt` exists at all, why `AutoMediaService` checks
for a live session before doing anything else, why cold start is a deep link and not a
`MediaSession` call) are answers to questions already investigated there, not decisions
made fresh in this codebase. `../AA-Test` is the throwaway sibling that first proved the
Android Auto mechanism `AutoMediaService.kt` here expands into something real. Both
`ytmprobe` and `AA-Test` are archival/reference only — do not modify them from here.

## Build / install / run

No Android Studio needed or used.

```bash
./build.sh
```

Same mechanics as `ytmprobe`'s `build.sh`: writes `local.properties` (defaults
`ANDROID_HOME` to `/opt/homebrew/share/android-commandlinetools`), pins `JAVA_HOME` to
JDK 17 or 21, generates the Gradle wrapper if missing, accepts SDK licenses, runs
`./gradlew assembleDebug --no-daemon`, and installs + launches on an attached device.

Manual equivalents:
```bash
./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ytmlauncher/.LaunchActivity
```

Retrieve the on-device log:
```bash
./pull-log.sh [outfile]      # defaults to probe.log, then cats it
```

Unit tests (Robolectric, JVM-only, no device needed):
```bash
./gradlew testDebugUnitTest --no-daemon
```

First run on a device: tap "Grant Notification Access" in Settings and enable YTM
Launcher in the system list — `MediaSessionManager.getActiveSessions()` throws
`SecurityException` without it, and every YTM-session-dependent feature (favorites
capture, playback control, `AutoMediaService`) silently no-ops into that branch. Android
13+ also prompts for `POST_NOTIFICATIONS` on first launch — without it, the lock-screen
favorites picker and the tracking foreground notification are silently suppressed.

Samsung devices: battery optimization kills the background tracking service overnight.
Set Settings → Apps → YTM Launcher → Battery → **Unrestricted**, or `SessionLogger` won't
survive to record anything. `BootReceiver` restarts tracking after a reboot without
needing the app opened manually, but does not exempt the app from this setting.

## First-time setup on a new device: migrate data from `ytmprobe`

This app has no data of its own until you import it. `ytmprobe`'s and `YTMLauncher`'s
favorites are two independent SharedPreferences files under two different package names
— nothing here reads `ytmprobe`'s storage automatically.

1. Revoke `ytmprobe`'s Notification Access (Settings → apps with notification access),
   then `adb shell am force-stop com.ytmprobe` — it polls every 10s and could otherwise
   write mid-read.
2. `adb exec-out run-as com.ytmprobe cat shared_prefs/ytmprobe.xml > ytmprobe.xml`
   (`exec-out`, not `shell` — some transports do LF→CRLF translation on `adb shell` that
   corrupts the XML).
3. Reshape `favorites`/`cache`/`last`/`mixSeed`/`autoContinueEligible`'s JSON string
   values out of that XML into the flat JSON object `Backup.import()` expects (see
   `Backup.kt` — it round-trips whatever `Backup.export()` produces, one key per
   SharedPreferences entry).
4. `adb push` the result to the device, install `YTMLauncher` **without opening it**,
   then use Settings → Backup → "Import favorites" on first launch.

**Order matters**: SharedPreferences are memory-cached per process. If `YTMLauncher` has
already been opened once, its in-memory map wins over a file written underneath it, and
the import silently no-ops. Install → import → then normal use.

Running both apps installed and active at once afterward is not harmless: two lock-screen
pickers, two `SessionLogger.maybeAutoContinue()` handlers racing to command the same YTM
session, double play-count capture. `pm disable-user com.ytmprobe` after migrating is the
clean way to avoid that, if you want a single-app state going forward.

## Architecture

Single Gradle module (`app/`), all Kotlin, no fragments/compose/DI framework. Everything
talks to YouTube Music (`com.google.android.apps.youtube.music`) through the system
`MediaSessionManager`/`MediaController` APIs.

Same core insight as `ytmprobe` (proven there, inherited here as a given): YTM's
`MediaSession` exposes no track identity — `METADATA_KEY_MEDIA_ID` is absent, every queue
item's `mediaId` is null. "Which song is this" is reconstructed by title/artist/duration
search against YTM's own unofficial InnerTube search backend. `Resolver.kt` exists
entirely because of this.

| File | Role |
|---|---|
| `LaunchActivity.kt` | The app's default entry point. One row per genre (a random resolved favorite tagged with it) plus a pinned "Resume" row when a mix seed is stored, tap to play, swipe left to remove / swipe right or long-press to tag genre, reshuffled on every refresh. `RecyclerView` + `ItemTouchHelper` for swipe (not `GestureDetector` — see the class doc comment for why that approach doesn't work reliably inside a scrolling list). |
| `AutoMediaService.kt` | The real Android Auto surface — a legacy `MediaBrowserServiceCompat` (category `MEDIA`), the mechanism `AA-Test` proved reaches Android Auto's Customize Launcher for sideloaded apps, unlike Car App Library (category `TEMPLATE`, confirmed blocked — see `ytmprobe/FINDINGS.md`). Browse tree mirrors `LaunchActivity.reload()`'s selection (one shuffled favorite per genre, pinned Resume row), with a Refresh tile always first. `onGetRoot` returns `MediaConstants` grid content-style hints (`CONTENT_STYLE_BROWSABLE`/`_PLAYABLE` = `GRID_ITEM`) so gearhead renders image-forward tiles rather than a plain list — a hint only, gearhead still owns actual column count. Refresh is `FLAG_BROWSABLE`, not `FLAG_PLAYABLE`: an earlier playable version "failed" on real hardware because Android Auto routes *any* playable tap to the now-playing template before the app's callback even runs, regardless of what that callback does — a browsable node instead reshuffles by navigating within the browse UI itself (`onLoadChildren` answers both `ROOT_ID` and `REFRESH_ID` identically). The Resume track is excluded from every genre's shuffle pool *before* picking (not after), so a genre only loses its tile if Resume's track was its sole resolved favorite, rather than ever showing the same track twice. Refresh and Resume both carry a gold ring marking them as controls rather than tracks — Refresh's is just a redesigned `ic_refresh.xml` (an owned bitmap, trivial), Resume's requires actually fetching its art and compositing the ring with `Canvas` before calling `setIconBitmap` (`ringedArt()`), since there is no API to ask gearhead for a per-tile colored border; genre tiles use plain remote art (`setIconUri`) same as the now-playing screen. `onGetRoot` allowlists known callers (gearhead's package, this app's own) but still returns a root and just logs anyone else, favoring visibility over strictness — a too-strict check fails silently, mid-drive, with no error surfaced anywhere. On tap, sets real track metadata (title/artist/art) so gearhead's own now-playing template has something to render, then polls the live session every second (up to 10s) for it to actually reach `STATE_PLAYING` before reporting `STATE_STOPPED` and setting `mediaSession.isActive = false` — deactivating is an unverified experiment to hand focus back to whichever session Android Auto's media stack considers current (should be YTM's, now that it's confirmed playing) instead of leaving gearhead parked on this app's own dead "stopped" screen; reactivated at the top of the next tap. Reports `STATE_ERROR` with a specific message either when no live session existed at all (the cold-start path — see "Known risk" below) or when one existed but playback was never confirmed within the timeout. Poll ticks, confirm/timeout outcomes, and unrecognized `mediaId` taps are all logged via `ProbeLog` for hardware diagnosis. |
| `Probes.kt` | Session helpers (`ytmController`, `hasNotificationAccess`) plus the production pipeline: `playFavorite()` (the one function every "tap a favorite" surface must call — saves the mix seed *and* plays, so `SessionLogger`'s auto-continue never replays a stale seed), `commandLiveSession()` (quiet `playFromUri`, used on every real play), `debugCommandLiveSession()` (verbose, for Settings' manual debug button), `resolveFavorites()`, `tagGenres()`. The raw diagnostic probes (A/B1/D/E/A+) and the candidate-override machinery from `ytmprobe`'s `Probes.kt` are gone — they answered their questions for good; see `ytmprobe/FINDINGS.md` if you need the history. |
| `Resolver.kt` | title+artist(+album+duration) → YouTube videoId via YTM's InnerTube search endpoint, unchanged from `ytmprobe`. Includes the scoring function (title/artist/album/duration weighted, `ATV`/`OMV`/`UGC` bonus/penalty). |
| `Store.kt` | `SharedPreferences`-backed persistence: last-seen track, `title\|artist -> videoId` resolution cache, and the mix seed `playFavorite()`/`AutoMediaService` write to and `SessionLogger.maybeAutoContinue()` reads. |
| `Favorites.kt` | Passive favorites list built from `USER_RATING` as tracks play. `liked == false` removes an entry; `liked == null` (never rated) does nothing — a never-rated track and a cleared rating are indistinguishable, so neither may delete a favorite. Owns the fixed `GENRES` list (picker-only, never free text). |
| `GenreTagger.kt` | Genre classification via iTunes' free Search API, scored against the known artist. Cyrillic-script titles/artists are bucketed as `"Russian"` without a network call at all. **Manual-only** — triggered from Settings' "Tag genres automatically" button, never automatically on startup; kept that way by explicit choice, not an oversight. |
| `SessionLogger.kt` | Foreground `Service`, the only poller in the app. Logs YTM session appear/disappear and applies the favorites capture rules on the same 10s poll. `foregroundServiceType="dataSync"` — deliberately not `mediaPlayback`, which grants OEM lock-screen exemptions this passive poller shouldn't have (see `ytmprobe`'s history with that exact bug). Started from `LaunchActivity.onCreate` and from `BootReceiver` after a reboot. |
| `BootReceiver.kt` | `ACTION_BOOT_COMPLETED` → starts `SessionLogger`. Without this, tracking is silently off after every reboot until the app is next opened manually — `START_STICKY` doesn't survive a reboot, only a process kill while the phone stays on. |
| `PlayFavoriteActivity.kt` | Invisible (`Theme.NoDisplay`/`noHistory`/`excludeFromRecents`) trampoline `Activity` the lock-screen notification's `PendingIntent` targets. Must be a real `Activity` reached via `PendingIntent.getActivity()`, not a `BroadcastReceiver` — Android 12+'s notification-trampoline block silently kills a `getBroadcast()` PendingIntent that itself calls `startActivity()`. Calls `Probes.playFavorite()`. |
| `FavoritesNotifier.kt` | Posts the lock-screen favorites picker, `VISIBILITY_SECRET` (no duplicate public notification — this was a real bug in `ytmprobe`'s history, see its `FINDINGS.md`/commit history if curious). |
| `FavoriteActions.kt` | The two actions available on a favorite row: `removeFavorite()`, `showGenreTagPicker()`. Formerly `FavoriteGestures` in `ytmprobe`, which also carried a `GestureDetector`-based swipe implementation (`attach()`) used by two screens this app doesn't have; dropped as dead code here since `LaunchActivity` drives swipe through `ItemTouchHelper` directly. |
| `Backup.kt` | Generic SharedPreferences-file JSON export/import (`Favorites` and `Store` share one file). Exists for two reasons: this app has `allowBackup="false"` and otherwise zero redundancy for hand-curated data, and it's the import side of the one-time `ytmprobe` migration (see above). |
| `SettingsActivity.kt` | Setup (Grant Notification Access), tracking toggle, manual resolve/tag-genres/show-favorites buttons, lock-screen notification post/clear, Backup export/import, a minimal Debug section (one videoId field + a manual `playFromUri` test button), and the collapsible log viewer. Trimmed from `ytmprobe`'s `DiagnosticsActivity` — the raw probe buttons and the candidate-override system are gone; see `ytmprobe/FINDINGS.md` if you need why they existed. |
| `NavActivity.kt` | Base class for `LaunchActivity`/`SettingsActivity` — owns the 3-dot menu (just "Settings") and edge-to-edge inset handling. |
| `AppHeader.kt` | Shared status strip (YTM session state, Notification Access / `POST_NOTIFICATIONS` warnings — tap either warning to jump straight to the relevant system settings screen). |
| `NotifListener.kt` | Deliberately empty `NotificationListenerService`. Its mere declaration is what makes the app eligible for Notification Access, required to call `getActiveSessions()` — no notification is ever actually read. |
| `ProbeLog.kt` | Append-only logger shared across the app; writes to both logcat (`adb logcat -s YTMProbe`) and a file the Settings screen polls via a listener callback. Name and log-file path (`probe.log`) are inherited from `ytmprobe` as-is — a pure identity rename is a reasonable follow-up, deliberately deferred to keep the port's diff reviewable. |

Log path on device: `/sdcard/Android/data/com.ytmlauncher/files/probe.log`.

## Design choices carried over from the `ytmprobe` → `YTMLauncher` migration

- **`targetSdk = 34`, not 36**, deliberately — `compileSdk` is still 36 (free). The only
  reason `ytmprobe` was on 36 was `ResumeCarAppService`'s `intentMatchingFlags`, which
  doesn't exist in this app. Staying at 34 buys exemption from Android 15's tightened
  background-activity-launch rules (directly relevant to `AutoMediaService`'s cold-start
  fallback — see below) and from the Android 15 foreground-service timeout that would
  otherwise apply to `SessionLogger`'s `dataSync` type. If this ever needs to move to 35+,
  add a `SessionLogger.onTimeout()` override or switch to
  `foregroundServiceType="specialUse"` first.
- **`AutoMediaService` drops `FLAG_HANDLES_MEDIA_BUTTONS`**, present in `AA-Test`'s
  throwaway version but not carried forward here. As a permanently-installed, always-active
  session, that flag risks this service becoming the system's media-button target and
  stealing steering-wheel/AVRCP play/pause commands from YTM mid-drive — the opposite of
  the goal. Only `FLAG_HANDLES_TRANSPORT_CONTROLS` is kept.
- **Genre tagging stays manual-only.** No auto-chained `tagGenres()` call anywhere,
  including at `LaunchActivity` startup — kept exactly as simple as `ytmprobe` was, by
  explicit direction during this app's design.

## Known, unresolved risk: `AutoMediaService`'s cold-start path

Every `AA-Test` success and failure happened with YTM's session already alive.
`AutoMediaService`'s cold-start branch (no live session — tapping a favorite from inside
Android Auto's browse UI while YTM is fully dead) reports `STATE_ERROR` with
`"YouTube Music isn't running — open it once"` rather than blindly claiming success, but
whether the underlying deep-link `startActivity()` call actually works from inside a
`MediaSessionCompat.Callback` while gearhead's browse UI is live is **unverified** — see
`ytmprobe/FINDINGS.md`'s "Legacy MediaBrowserServiceCompat apps reopen this" section for
the full reasoning on why this specific shape of call is the one most likely to hit the
same "Could not load your selection" wall `AA-Test` hit twice before switching to
commanding a live session directly.

This can't be resolved by more code — it needs either a DHU bench test against the real
phone (not the emulator) or a genuine mid-drive test where YTM is actually killed by the
OS, not force-stopped-and-reconnected (a Samsung routine relaunches YTM on AA connect
before a tap is even possible, so a quick reconnect-and-tap can't isolate this). If both
come back negative, `SYSTEM_ALERT_WINDOW` (documented in `ytmprobe/FINDINGS.md`'s "Cold
start" section as the reliable fallback) is the escape hatch — not implemented here.

## Platform boundary: what `AutoMediaService` cannot control

Raised as real feedback after live testing (on a GLE63s head unit — the only device this
surface has been verified on; a separate MC20 unit remains unproven for this app): no
haptic feedback on tap, and no per-row "pressed" highlight while a finger is down on a
browse item. Both are owned entirely by Android Auto's own UI process (gearhead), which
renders the browse list and now-playing screen from the `MediaItem`/`MediaDescriptionCompat`
data and `MediaSessionCompat` state this service supplies — there is no API for a
`MediaBrowserServiceCompat` app to influence either, and no way to draw a custom
animation screen on the head unit itself. The achievable equivalent, implemented in
`AutoMediaService`: the screen visibly switches to a buffering/loading treatment — now
carrying the tapped track's real title/artist/art — the instant the tap is registered,
and stays there until the app has actually confirmed (not assumed) that YTM started
playing. Don't mistake either limitation for an oversight in a future session — there is
no supported hook to close this gap further.

## Explicitly out of scope / deferred (not silently forgotten)

- `Probes`/`ProbeLog` identity rename and `probe.log` → a non-"probe" filename.
- `ProbeLog` log rotation — currently append-only/unbounded.
- A two-level browsable genre-folder tree and `onPlayFromSearch` (voice: "play X") for
  `AutoMediaService` — current tree is intentionally the same flat list as `LaunchActivity`.
- `SYSTEM_ALERT_WINDOW` as an active cold-start mitigation (see above) — documented as the
  next step if DHU/drive testing shows it's needed.
- A locking/synchronization fix for `Favorites`' read-modify-write races beyond what
  `LaunchActivity`'s startup already serializes — `SessionLogger`'s independent poll-thread
  writes remain a pre-existing, unaddressed race, inherited from `ytmprobe`.

### Key traps to know before touching `Resolver.kt` or the metadata-reading code

Same as `ytmprobe` — carried over verbatim since the code is unchanged:

- **`org.json`'s `optString("text")` silently coerces objects.** Always check
  `node.opt("text") is String` before using it (see `Resolver.texts()`) — this produced no
  error and quietly capped every score, the single hardest bug in the project's history.
- **`dumpsys` under-reports session state; `MediaController` does not.** Queue contents
  and `METADATA_KEY_DURATION` are invisible via `dumpsys` but present via a real
  `MediaController`.
- **An advertised playback action isn't necessarily usable.** `PLAY_FROM_MEDIA_ID` is in
  YTM's actions bitmask, but there's no `mediaId` anywhere to pass it — verify a capability
  by using it, not by checking the bitmask.
- **`playbackState.position` is a snapshot**, paired with `lastPositionUpdateTime`.
  Extrapolate with `elapsedRealtime()` while playing or the stored position goes stale.
- **Clipboard reads require window focus** (Android 10+).
