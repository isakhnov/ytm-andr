# YTM Probe

Six probes, passive favorites tracking, and an Android Auto favorites surface.
The project answers the questions that decide the architecture of the
car-resume app and provides a working prototype. No Android Studio is required.

## Why

Everything validated so far went through `adb shell`, which runs as UID 2000
and is exempt from restrictions your app will face. Four things remain unknown:

| Probe | Question | If it passes |
|---|---|---|
| **A** | Does YTM publish `METADATA_KEY_MEDIA_ID`? | the whole search/resolution layer is deleted |
| **B1** | Can `MediaBrowserCompat.connect()` cold-start YTM? | automatic resume, no button, no permissions |
| **C** | Does a *live* session honour `playFromUri`? | **the approach works at all** |
| **D** | Does a direct broadcast wake the MediaButtonReceiver? | a second automatic path |
| **logger** | Does AA connecting create a YTM session by itself? | cold start stops being your problem |

C matters most. It was never testable over adb — `am start` launches *and*
plays in one move, so commanding an already-running session is unproven.

## Build

```bash
brew install --cask temurin      # JDK 17+
brew install gradle              # only needed once, to generate the wrapper

./build.sh
```

`build.sh` writes `local.properties`, generates the Gradle wrapper, accepts SDK
licences, builds, installs, and launches. It defaults to
`/opt/homebrew/share/android-commandlinetools` — override with `ANDROID_HOME`.

Works against the emulator or your Samsung over USB. The Samsung is the more
useful target since it's the phone that goes in the car.

## Run

**First:** tap *Grant Notification Access* and enable YTM Probe in the list.
Without it `getActiveSessions()` throws and every probe fails.

Then *Status* to confirm the plumbing works before probing anything.

### Probe A — metadata fields
Start playback in YouTube Music, then tap A. Dumps every metadata key with its
value, plus the actions bitmask and queue size as a cross-check against the
shell findings.

The line to look for is `MEDIA_ID = '...'`. If it holds a videoId, resolution,
scoring, caching, and the wrong-cover problem all disappear.

### Probe B1 — bind-based cold start
```bash
adb shell am force-stop com.google.android.apps.youtube.music
```
Then tap B1. A service bind isn't subject to the background activity launch
restriction, so success here means automatic resume with no button. The
obstacle is YTM's `onGetRoot()` package validation.

### Probe C — playFromUri on a live session
Start playback, put a videoId in the field, tap C. It records the current
track, sends `playFromUri`, waits 6s, and reports whether the track changed.

### Probe D — direct broadcast
Force-stop YTM first. `dumpsys` showed the receiver is manifest-registered and
survives a force-stop; this aims a broadcast straight at the component,
bypassing the null media-button-session routing that blocked the shell tests.

### Session logger
Tap *Start logger*, then drive. It timestamps every session appear/disappear.
Next morning:

```bash
./pull-log.sh
```

Look for `>>> YTM SESSION APPEARED` and its timestamp relative to when AA
connected. If a session exists before you touch anything, your app only needs
to command it.

**Samsung caveat:** One UI kills background services aggressively. Before the
overnight test, go to Settings → Apps → YTM Probe → Battery → **Unrestricted**,
or the logger won't survive to record the morning.

## Reading the results

```
A passes  ->  drop resolution entirely; app reads the exact videoId
A fails   ->  port the v7 search/scoring/cache layer into the app

C passes  ->  approach confirmed; resume is playFromUri + seekTo
C fails   ->  deep link only; every resume needs an activity launch

B1 or D passes  ->  automatic resume on car connect
both fail       ->  Car App Library button, or SYSTEM_ALERT_WINDOW to lift
                    the background activity launch restriction

logger shows a session on AA connect  ->  B1 and D stop mattering
```

## Files

| File | Role |
|---|---|
| `MainActivity.kt` | day-to-day screen: status strip, AA-shaped favorites list, tracking, live log |
| `DiagnosticsActivity.kt` | setup, settled probes (A, E, B1, D), and manual videoId override |
| `Probes.kt` | the four probes and shared session helpers |
| `SessionLogger.kt` | foreground service, logs session lifecycle |
| `Favorites.kt` | passively captures liked tracks and stores playable favorites |
| `Resolver.kt` | resolves metadata to YouTube Music video IDs through InnerTube |
| `ResumeCarAppService.kt` | Android Auto list of resolved favorites |
| `Store.kt` | persists the last track and resolution cache |
| `NotifListener.kt` | empty by design — its declaration unlocks session access |
| `ProbeLog.kt` | append-only log to file and logcat |
| `build.sh` / `pull-log.sh` | command-line build, install, retrieve |

Log path: `/sdcard/Android/data/com.ytmprobe/files/probe.log`
Live logcat: `adb logcat -s YTMProbe`
