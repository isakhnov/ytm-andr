# YouTube Music resume on Android Auto — findings

Investigation into making YouTube Music resume where it left off when the car
starts, instead of restarting playlists from track one.

**Status:** every technical unknown is resolved. The full chain — capture,
resolve, store, play — is proven working on the target phone. What remains is
assembly plus two questions only answerable on a real drive.

Environment: 2025 Mercedes GLE63 (MBUX NTG7), Samsung SM-S948U1 (Android Auto
beta), development on macOS against an Android emulator and the phone.

Last updated: 2026-08-07

---

## Contents

1. [Problem](#problem)
2. [Current status](#current-status)
3. [Dead ends ruled out](#dead-ends-ruled-out)
4. [What YTM's MediaSession exposes](#what-ytms-mediasession-exposes)
5. [Experiment log](#experiment-log)
6. [Resolution: how track identity is recovered](#resolution-how-track-identity-is-recovered)
7. [Cold start: the one thing that cannot be done headlessly](#cold-start-the-one-thing-that-cannot-be-done-headlessly)
8. [Resulting architecture](#resulting-architecture)
9. [Open questions](#open-questions)
10. [Reference data](#reference-data)
11. [Traps and gotchas](#traps-and-gotchas)

---

## Problem

YouTube Music does not persist playlist position. Close the app, return later,
restart at track one. Spotify keeps player state server-side (context URI,
track index, position) so any client resumes exactly; YTM's queue is
session-local, with server-side resume implemented only for podcasts.

Goal: start the car, press at most one button, resume from the last song (or a
random favourite), and let YTM's recommendation engine choose what follows.

---

## Current status

| Capability | State | Evidence |
|---|---|---|
| Read track metadata from a live session | **works** | probe A |
| Read like state as a boolean | **works** | probe A (`USER_RATING`) |
| Read the 25-item queue | **works** | probe E |
| Resolve title+artist → videoId, in-app, no auth | **works** | probe A+ |
| Persist track + resolution cache | **works** | probe A+ |
| Command a live session (`playFromUri`) | **works** | probe C |
| Restore position (`seekTo`) | untested | — |
| Cold-start a dead YTM from app code | **impossible** | probes B1, D |
| Cold-start via Samsung routine | untested | — |

---

## Dead ends ruled out

Recorded so they aren't re-investigated.

### MBUX itself is closed
NTG7 is signed firmware: no developer mode, no package installer, no adb. The
in-car app "store" is a curated set of partner clients provisioned server-side.
Not Android Automotive OS, so AAOS sideloading doesn't apply. Mercedes'
engineering has moved to MB.OS on the MMA platform (next-gen CLA); nothing
back-ports to V167. The 2023 "MBUX API for Android" announcement never became a
public developer program.

### Android Auto audio is stereo-only
The AA media audio channel is 2-channel PCM. Multichannel is downmixed on the
phone. No bitstream passthrough. **A custom media app can never deliver 5.1
FLAC to the Burmester system** — USB mass storage is the only multichannel
path, and it has no control API. This is why AA was abandoned for hi-res
multichannel and kept only for stereo streaming.

### No YouTube Music API
No official API. YouTube Data API v3 is video-oriented and cannot reach private
YTM features. `ytmusicapi` is a reverse-engineered client — useful for search,
never audio, never player state.

### Wrapper apps are impossible
Android allows one active `MediaSession`. An app that launches YTM immediately
loses the AA media surface to YTM. No delegation primitive exists: whatever app
appears in AA must be the app decoding audio.

### Spotify supports this natively
The App Remote SDK lets an app hand a URI to the Spotify client, which plays and
continues on its own. YTM has no equivalent. If switching services were
acceptable, this would be a documented weekend project.

---

## What YTM's MediaSession exposes

`dumpsys` and a `MediaController` see **different things**. This distinction
cost real time and shapes the design.

| Field | via dumpsys | via MediaController |
|---|---|---|
| Title / artist / album | yes (comma-joined) | yes (separate keys) |
| Duration | **no** | **yes** — critical for resolution |
| Playback position | yes | yes |
| Like state | yes (icon id) | **yes** (`USER_RATING` boolean) |
| Custom actions | yes | yes |
| Actions bitmask | yes | yes |
| Queue | **no** — always `size=0` | **yes** — 25 items |
| `METADATA_KEY_MEDIA_ID` | no | **no — genuinely absent** |
| Queue item `mediaId` | n/a | **no — null on all 25** |

Two conclusions:

- **`dumpsys` under-reports.** It hides the queue and duration entirely. Never
  conclude a field is absent from `dumpsys` alone.
- **Track identity is nowhere.** Not in metadata, not on queue items, not in
  extras. Verified across multiple tracks and all queue entries.

---

## Experiment log

### E1 — Android Auto media app (MediaBrowserService)
Rejected for multichannel: AA's audio channel is stereo PCM. Valid for stereo
streaming only.

### E2 — dumpsys session probe
YTM publishes `PLAY_FROM_URI`, `PLAY_FROM_MEDIA_ID`, `SEEK_TO`. No
`SKIP_TO_QUEUE_ITEM`. Bitmask `2600887`.
**Superseded by E6/E7:** the queue does exist; dumpsys doesn't print it.

### E3 — shell-driven resume loop (v7 toolkit)
Capture → resolve → force-stop → relaunch → verify. **Works.** Proves one track
is enough to restore from, and YTM's algorithm continues from that entry point.

Shell-specific friction, none of which applies to a real app:

- **Non-ASCII mangling.** `adb shell` corrupts Cyrillic in transit; search
  intents failed silently and the session landed in `state=ERROR`.
- **`ResolverActivity` interception.** Unverified app links send deep links to
  the chooser. Worked around by pinning the component via `pm query-activities`.
- **Cold-start timing.** A play intent at launch lands on nothing, since a
  cold-started app has no session yet. Required launch → wait → dispatch.

### E4 — like-state detection via custom actions
Works. Confirmed by toggling and verified visually against the UI.

| Custom action | Icon id | Meaning |
|---|---|---|
| `Like` | `2131233455` | not liked |
| `Undo like` | `2131233050` | **liked** |

**Superseded by E6:** `USER_RATING` gives the same information as a boolean and
additionally distinguishes "unrated" from "thumbed down".

### E5 — Desktop Head Unit
**Abandoned.** Android Auto reports "isn't compatible with your device anymore"
on the emulator and won't install. DHU itself is a native arm64 build and runs,
but has nothing to connect to. The AA-connection question needs real hardware.

### E6 — Probe A: metadata fields
```
9 keys: ALBUM, ALBUM_ART, ALBUM_ARTIST, ARTIST, DURATION, TITLE,
        USER_RATING, VIDEO_HEIGHT_PX, VIDEO_WIDTH_PX
USER_RATING = isRated=true  ratingStyle=2 thumbUp=true   (liked)
USER_RATING = isRated=false ratingStyle=2 thumbUp=false  (not liked)
queue size = 25
```
`METADATA_KEY_MEDIA_ID` absent. `USER_RATING` works. Queue exists despite
dumpsys reporting zero. **`DURATION` is present** and became the key to
reliable resolution.

### E7 — Probe E: queue contents
25 items, `queueTitle = "Up next"`, `activeQueueItemId = 2`. Current track is
`qid=2`; continuation runs `qid=101..124`.

**Every item has `mediaId = null.`** No `mediaUri`, no extras. Titles and
subtitles only. Search-based resolution is unavoidable.

`PLAY_FROM_MEDIA_ID` is advertised but unusable — there is no mediaId to pass
it. **Advertised ≠ usable.**

### E8 — Probe C: playFromUri on a live session ★
**Works.** Sent `playFromUri(music.youtube.com/watch?v=ThqRONlaT_I)` to a
running session; YTM switched to the correct track. Confirmed on both emulator
and phone.

**The pivotal result.** The app can control playback with no activity launch,
no deep link, no intent resolution, no chooser. All E3 friction disappears.

### E9 — cold-start attempts (all failed)

| Mechanism | Result | Why |
|---|---|---|
| `cmd media_session dispatch play` | failed | no active session to route through |
| `input keyevent 85` | failed | `Media button session is null` |
| **B1** `MediaBrowserCompat.connect()` | **failed** | `onGetRoot()` rejected the package |
| **D** broadcast to `MediaButtonReceiver` | **failed** | no session after 8s |

B1 detail: the service is
`com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService`. It
binds, then rejects. Validation exists to admit Android Auto and Wear OS only.
Not bypassable without signing as a permitted package.

D detail: `dumpsys` shows `androidx.media.session.MediaButtonReceiver` is
manifest-registered and survives a force-stop, but `Media button session is
null` means the framework has nothing to route to.

### E10 — Probe A+: full capture → resolve → store → play ★
**Works on the phone.** Reads the session, resolves title+artist to a videoId
over InnerTube, persists it, hands the id to probe C, which plays it.

First attempt selected a **UGC cover** instead of the official track. Two
causes, both fixed — see [Resolution](#resolution-how-track-identity-is-recovered).
After the fix the correct track scores 100 and the cover 68.8.

---

## Resolution: how track identity is recovered

Since YTM exposes no videoId, identity is reconstructed by search. The app
calls YouTube Music's **InnerTube** endpoint directly — the same API
`ytmusicapi` wraps — unauthenticated, no API key, no dependencies:

```
POST https://music.youtube.com/youtubei/v1/search
{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"..."}},
 "query":"title artist","params":"<filter>"}
```

Three shelves are queried (songs, videos, unfiltered) and deduped.

### Scoring

| Signal | Weight | Notes |
|---|---|---|
| Title similarity | 50 | LCS ratio on normalised text |
| Artist similarity | 25 | |
| Album | 10 | matched against title *or* artist — singles repeat the title |
| **Duration** | **15** | ±2s → +15, ±5s → +11, ±15s → +5, >30s → **−10** |
| Exact title / artist | +5 each | |
| Release type | ±5 | `ATV` +5, `OMV` +2, `UGC` −5 |

**Duration is the hardest signal.** Covers and partial edits rarely match
runtime, where fuzzy text often does. `METADATA_KEY_DURATION` supplies the
target; InnerTube returns duration per candidate.

**Release type matters nearly as much.** `MUSIC_VIDEO_TYPE_ATV` is the official
audio track, `OMV` an official video, `UGC` a user upload — and UGC is where
the covers live.

Real example, target `Слишком глупый план — AiRushV` (193s):

```
-> 100.0  h0JZJ4ovvsM ATV  193s  Слишком глупый план          <- correct
    97.0  WinbXliMQ9M OMV     -   Слишком глупый план
    68.8  lpLa656llzw UGC     -   Слишком глупый план. cover  <- previously chosen
    20.6  _joAvsj4_qc ATV     -   Khamoshiyan
```

### Caching and override
Confirmed resolutions are cached under `norm(title)|norm(artist)`, so a song
resolves once and never again. A manual override writes the correction into the
same cache. Below score 70 the result is flagged rather than trusted silently.

---

## Cold start: the one thing that cannot be done headlessly

Everything that worked over `adb` ran as UID 2000, which is exempt from
Background Activity Launch restrictions. App code is not.

Per Android's documented BAL exceptions, an app may start an activity from the
background only if it has a visible window, is the IME, uses a system-sent
`PendingIntent` (e.g. a notification tap), holds `SYSTEM_ALERT_WINDOW`, holds
the privileged `START_ACTIVITIES_FROM_BACKGROUND`, is bound by a service with
that privilege, or the launch comes from the launcher or core OS.

Options, ranked:

1. **Samsung Modes and Routines** — a system app, so it holds the privilege
   outright. Android Auto is a first-class trigger ("Connected devices"), and
   "Open an app" is a standard action. **Set up; awaiting a drive.** Note a
   documented limit: routines can open an app but not activate controls inside
   it — which suits this design exactly, since the routine only needs to wake
   YTM and the app does the rest.
2. **AA's own "Start music automatically"** setting may already cold-start the
   default media app. Untested. If true, nothing else is needed.
3. **`SYSTEM_ALERT_WINDOW`** — explicit documented exception, user-grantable
   once. Reliable fallback for fully automatic behaviour.
4. **Notification tap** — a system-sent `PendingIntent` is exempt. Costs a tap,
   and AA's notification surface is restricted to messaging/navigation, so the
   tap would land on the phone rather than MBUX.
5. **Car App Library button** — a row in AA's launcher. A tap, but on the right
   screen. Best UX among the tap-based options.
6. **AccessibilityService** — how automation apps do it. Brittle, invasive.
7. **Voice** — "Hey Google, play …" wakes YTM through Assistant, which holds
   the privileges. Zero code; replaces a tap with a phrase.

---

## Resulting architecture

```
car starts, AA connects
        ↓
Samsung routine opens YTM        ← system privilege; no BAL problem
        ↓
YTM cold-starts, session appears
        ↓
app's session listener sees it
        ↓
playFromUri(stored videoId) + seekTo(position)   ← proven, probe C
        ↓
YTM's algorithm continues
```

**If the routine works, no button and no special permission are needed** —
commanding a session is not an activity launch.

**Components**

| Piece | Role | Replaces (v7 shell) |
|---|---|---|
| `NotificationListenerService` | unlocks `getActiveSessions()` | — |
| `MediaSessionManager` callback | persist track as it changes | `ytm-observe` / `ytm-watch` |
| `Resolver` (InnerTube) | title+artist → videoId | `ytm-resolve` + ytmusicapi |
| `Store` (SharedPreferences/Room) | last track + resolution cache | `last.env`, `known.json` |
| Resume trigger | `playFromUri` + `seekTo` | `ytm-resume` |
| Favourites | built from `USER_RATING` | `ytm-fav` + icon matching |

**Samsung caveat:** One UI kills background services aggressively. The listener
must be set to **Unrestricted** battery or it won't survive overnight — exactly
when it's needed.

---

## Open questions

**Does the Samsung routine wake YTM on car connect?**
Set up, awaiting a drive. The session logger records session appear/disappear
with timestamps and answers this directly.

**Does AA's "Start music automatically" already do it?**
If so, the routine is redundant. Turn it off for the first routine test so the
two aren't confounded.

**Does `seekTo` actually work?**
`SEEK_TO` is advertised — but so was `PLAY_FROM_MEDIA_ID`, which turned out
unusable. Needs the same treatment probe C gave `playFromUri`.

**Can `SYSTEM_ALERT_WINDOW` remove the tap if the routine fails?**
Documented as a BAL exception. Untested.

---

## Reference data

**Packages**
```
com.google.android.apps.youtube.music
com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService
androidx.media.session.MediaButtonReceiver
com.google.android.projection.gearhead        (Android Auto)
```

**Actions bitmask `2600887`**
```
STOP, PAUSE, PLAY, SKIP_TO_PREVIOUS, SKIP_TO_NEXT, SET_RATING, SEEK_TO,
PLAY_PAUSE, PLAY_FROM_MEDIA_ID, PLAY_FROM_SEARCH, PLAY_FROM_URI,
PREPARE_FROM_MEDIA_ID, PREPARE_FROM_SEARCH, PREPARE_FROM_URI,
SET_REPEAT_MODE, SET_SHUFFLE_MODE
```
Absent: `SKIP_TO_QUEUE_ITEM`, `FAST_FORWARD`, `REWIND`, `SET_PLAYBACK_SPEED`

**Custom action icon ids**
```
2131233455  Like        (not liked)      2131233451  Dislike
2131233050  Undo like   (liked)          2131232819  Shuffle off
                                         2131232721  Repeat off
```

**InnerTube search**
```
POST https://music.youtube.com/youtubei/v1/search
clientName WEB_REMIX
songs  filter params: EgWKAQIIAWoKEAkQBRAKEAMQBA==
videos filter params: EgWKAQIQAWoKEAkQChAFEAMQBA==
musicVideoType: ATV = official audio, OMV = official video, UGC = user upload
```

**Deep link**
```
https://music.youtube.com/watch?v={videoId}                 radio continuation
https://music.youtube.com/watch?v={videoId}&list={playlist} playlist continuation
```

---

## Traps and gotchas

**`optString()` coerces objects.** org.json's `optString("text")` on a node
whose `text` value is `{"runs":[...]}` returns the entire JSON blob as a
string — silently. This made every InnerTube candidate title a JSON dump and
capped all scores under 19. **Always type-check `opt("text") is String`.** This
was the single hardest bug in the project and produced no error at all.

**Advertised ≠ usable.** `PLAY_FROM_MEDIA_ID` sits in the actions bitmask but
cannot be used, because YTM never exposes a mediaId. Verify capabilities, don't
infer them from flags.

**`dumpsys` under-reports.** Queue and duration are invisible to the shell but
present to a `MediaController`.

**Position is a snapshot.** `playbackState.position` is paired with
`lastPositionUpdateTime`; extrapolate with `elapsedRealtime()` when playing or
you store a stale value.

**Build environment**
- Gradle 8.7 / AGP 8.5 need **JDK 17–21**. A newer JDK fails with a bare
  version number as the entire error message (e.g. `26.0.2`).
- macOS has `pip3`, not `pip`; use a venv.
- `ytmusicapi.search()` needs **no authentication** — only library, playlist,
  and history calls do. `ytmusicapi browser` was an unnecessary detour.
- Samsung **Auto Blocker** blocks USB debugging and re-enables itself after
  updates. Settings → Security and privacy → Auto Blocker.
- Samsung USB defaults to charge-only; set Developer options → Default USB
  configuration → File Transfer.

---

## Related artifacts

| Artifact | Purpose |
|---|---|
| v7 shell toolkit | reference implementation of the resume model and resolution |
| `ytmprobe` Android project | probes A, A+, B1, C, D, E, plus the session logger |

The probe app is worth keeping: it re-tests session behaviour whenever a YTM
update changes something, and probe A+ is effectively a working prototype of
the real app's capture path.
