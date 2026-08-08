# YTM Resume

Make YouTube Music pick up where it left off when the car starts, instead of
restarting playlists from track one.

---

## What this is

YouTube Music doesn't persist playlist position. Close it, come back, and you
restart at track one — including every morning in the car. Spotify keeps player
state server-side so any client resumes exactly; YouTube Music never built
that, and offers no API to work around it.

This project works around it from outside, using Android's `MediaSession` — the
same interface Android Auto talks to.

**The chain:**

```
observe what's playing  ->  store it  ->  resolve it to a video ID
                                                  |
car starts, Android Auto connects                 |
        |                                         |
Samsung routine opens YouTube Music               |
        |                                         |
   session appears (10-20s) ------------------> play it
                                                  |
                        YouTube Music's algorithm continues from there
```

It also builds a **favorites list passively** — anything you thumbs-up while
driving gets captured, so "play a random favorite" needs no curation.

**Target setup:** 2025 Mercedes GLE63 (MBUX NTG7), Samsung Galaxy phone,
Android Auto. Development on macOS.

**Status:** capture, resolution, persistence, favorites tracking, and live-session
playback are proven on the target phone. The Android Auto favorites surface is
implemented and builds, but still needs an in-car test. Position restore also
remains untested — see [FINDINGS](FINDINGS.md#open-questions).

---

## Documents

| File | What's in it |
|---|---|
| **README.md** (this) | orientation and full setup from scratch |
| [**FINDINGS.md**](FINDINGS.md) | every experiment, result, and dead end. The reference. |
| [**SPEC-favorites.md**](SPEC-favorites.md) | favorites tracking design and acceptance criteria |
| [`ytmprobe/README.md`](ytmprobe/README.md) | the Android probe app itself |

Read **FINDINGS** before changing anything — it records what was already tried
and why several obvious approaches don't work.

---

## Repository layout

```
.
├── README.md                 you are here
├── FINDINGS.md               experimental record
├── SPEC-favorites.md         favorites specification
├── ytmprobe/                 Android app (Kotlin, command-line build)
│   ├── build.sh              builds, installs, launches
│   ├── pull-log.sh           retrieves the on-device log
│   └── app/src/main/java/com/ytmprobe/
│       ├── MainActivity.kt   UI: probes, tracking control, pickers
│       ├── SessionLogger.kt  tracking service (sessions + 10s poll)
│       ├── Favorites.kt      favorites list and capture rules
│       ├── ResumeCarAppService.kt
│       │                     Android Auto favorites surface
│       ├── Resolver.kt       InnerTube search, title+artist -> video ID
│       ├── Store.kt          last track + resolution cache
│       ├── Probes.kt         probes A, A+, B1, C, D, E
│       └── ProbeLog.kt       logging to file and logcat
```

---

## Setup from scratch

Assumes a clean Mac and an unprepared Android phone. About 30 minutes.

### 1. Mac — install tooling

```bash
# Homebrew, if not present
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

brew install --cask temurin@21          # JDK 21 — see the warning below
brew install --cask android-platform-tools
brew install --cask android-commandlinetools
brew install gradle
```

> **JDK version matters.** Gradle 8.7 / AGP 8.5 support **Java 17–21 only**. A
> newer JDK fails with a bare version number as the entire error message
> (`26.0.2`), which looks like nothing at all. `build.sh` pins a supported JDK
> automatically if one is installed.

Confirm the SDK path — `build.sh` defaults to it:

```bash
ls /opt/homebrew/share/android-commandlinetools
```

Different path? Set `ANDROID_HOME` before building.

### 2. Phone — enable developer access

On the phone:

1. **Settings → About phone → Software information** → tap **Build number**
   seven times
2. **Settings → Developer options** → enable **USB debugging**
3. **Settings → Developer options → Default USB configuration** → **File
   transfer**
   (Samsung defaults to charge-only, which silently blocks adb)
4. **Settings → Security and privacy → Auto Blocker** → **off**

> **Auto Blocker re-enables itself** after system updates and blocks USB
> debugging every time. If adb suddenly stops seeing the phone, check here
> first.

Plug into the Mac, unlock the screen, and accept the **Allow USB debugging**
prompt — tick *Always allow from this computer*.

```bash
adb devices          # expect: <serial>  device
```

`unauthorized` means the prompt wasn't accepted. `unattached` means USB mode is
still charge-only.

### 3. Build and install

```bash
cd ytmprobe
bash build.sh
```

`build.sh` writes `local.properties`, pins a supported JDK, generates the
Gradle wrapper, accepts SDK licences, builds, installs, and launches.

With both a phone and an emulator attached, target the phone explicitly:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Grant permissions

In the app:

1. Tap **Grant Notification Access** → enable *YTM Probe* → back
2. Tap **Status** → confirm `notification access: true`

Without this, `getActiveSessions()` throws and nothing works.

Then, and this one matters:

**Settings → Apps → YTM Probe → Battery → Unrestricted**

One UI kills background services aggressively. Tracking must survive overnight
— exactly when it's needed.

### 5. Samsung routine — the wake mechanism

Your app cannot cold-start YouTube Music. Three mechanisms were tested and all
were rejected by the OS (see [FINDINGS §E9](FINDINGS.md#e9--cold-start-attempts-all-failed)).
Samsung's Modes and Routines runs as a system app and holds the privilege
outright.

**Settings → Modes and Routines → Routines → +**

- **If:** Connected devices → **Android Auto**
- **Then:** Apps → **Open app** → **YouTube Music**

Confirmed working: YouTube Music cold-starts on car connect, session appears in
10–20 seconds, and it does **not** start playing on its own — so there's
nothing to fight when the resume command lands.

Also check **Android Auto → Start music automatically**. If it's on, it may
already do this and the routine is redundant. Turn it off while testing the
routine so the two aren't confounded.

### 6. Verify

1. Open the app — the indicator should read `● tracking active`
2. Play something in YouTube Music
3. Tap **A+ then C** — captures, resolves, stores, then plays it back
4. Thumbs-up a track; within 10 seconds the log shows `++ FAVORITE added`
5. Tap **Pick a favorite** — 10 random, refreshable

---

## Daily use

Tracking auto-starts when you open the app and runs as a foreground service.
Drive, like what you like; the list builds itself.

```bash
./pull-log.sh                        # retrieve the log
adb logcat -s YTMProbe               # or watch it live
```

Occasionally:

- **Resolve favorites** — fills in missing video IDs
- **Show favorites** — the full list with IDs and play counts
- **Pick from last candidates** — corrects a bad resolution; the fix is
  permanent

---

## Known limitations

**Track identity isn't published.** YouTube Music exposes no video ID anywhere
— not in metadata, not on queue items. Everything is reconstructed by search,
scored on title, artist, album, duration, and release type. It gets it right
most of the time and wrong occasionally; the candidate picker is the fix, and
corrections are cached permanently.

**Only what plays is captured.** Your existing liked library is invisible unless
you play through it. Reading it needs authenticated InnerTube, which was
deliberately not pursued. The list builds over weeks.

**A tap or a routine is required.** No app-level mechanism can wake a dead
YouTube Music. This is an OS restriction, not an oversight.

**Stereo only.** Android Auto's audio channel is 2-channel PCM; multichannel is
downmixed on the phone. Multichannel FLAC needs the USB media source, which has
no control API at all.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `adb` doesn't see the phone | Auto Blocker on, or USB set to charge-only |
| `unauthorized` in `adb devices` | debugging prompt not accepted; unlock the screen |
| Build fails with just a version number | JDK too new — install `temurin@21` |
| `notification access: false` | permission not granted, or revoked after reinstall |
| Tracking stops overnight | battery not set to Unrestricted |
| Wrong song resolved | use **Pick from last candidates**; the correction sticks |
| No favorites captured | tracking inactive, or the track was never thumbs-upped |

More detail in [FINDINGS §Traps and gotchas](FINDINGS.md#traps-and-gotchas).

---

## What's next

Three open questions, all answered by driving:

1. Does the tracking service survive a full overnight gap on One UI?
2. Does `seekTo` actually restore position? (`SEEK_TO` is advertised, but so
   was `PLAY_FROM_MEDIA_ID`, which turned out unusable.)
3. Does Android Auto accept and render the sideloaded IoT-category favorites
   surface on the target head unit?

Then phase 2 from [SPEC-favorites](SPEC-favorites.md#phase-2--storage-cap):
1,000-entry cap with least-used eviction, and boot-time tracking start.

The end state is a Car App Library app presenting *Resume last* and *Random
favorite* in Android Auto's launcher — or no UI at all, if the routine proves
reliable enough to make the whole thing automatic.
