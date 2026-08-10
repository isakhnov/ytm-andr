# YouTube Music resume on Android Auto — findings

Investigation into making YouTube Music resume where it left off when the car
starts, instead of restarting playlists from track one.

**Status:** every technical unknown is resolved. The full chain — capture,
resolve, store, play — is proven working on the target phone. What remains is
assembly plus two questions only answerable on a real drive.

Environment: 2025 Mercedes GLE63 (MBUX NTG7), Samsung SM-S948U1 (Android Auto
beta), development on macOS against an Android emulator and the phone.

Last updated: 2026-08-09

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
| Show `ResumeCarAppService` (Car App Library) in Android Auto (sideloaded) | **impossible** | decompiled gearhead `CAR.VALIDATOR`, see Dead ends |
| Show a `MediaBrowserServiceCompat` app in Android Auto (sideloaded) | **works** ★ | `AA-Test`, live on real hardware — see "Legacy media apps reopen this" |
| Command YTM from a tap on the Android Auto screen | **works**, via `playFromUri` on a live session | `AA-Test` — see below |
| Cold-start YTM (no live session) from an Android Auto media tap | untested | only tested with a live session present so far |

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

### Sideloaded Car App Library apps cannot appear in Android Auto's launcher
`ResumeCarAppService` (category `IOT`) is manifest-correct, icon-correct, and
Play Protect-clean, yet never appears in Android Auto's Customize Launcher —
not even in "Hidden Apps" — regardless of "Unknown sources" being enabled.
Confirmed by decompiling `com.google.android.projection.gearhead`'s own
validator (`CAR.VALIDATOR`, class `iwt`), not by inference:

- Every androidx.car.app category (`IOT`, `POI`, `WEATHER`, ...) maps
  internally to a single generic bucket, `qnh.TEMPLATE`.
- The "Unknown sources" developer toggle only overrides the allowlist
  `{MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE, SMS}` — the
  legacy, pre-Car-App-Library integration paths. `TEMPLATE` (and
  `MESSAGING`) are structurally excluded from that allowlist. No manifest
  change can add a category to this list; it's compiled into gearhead.
- The only way past this gate is the *other* branch above it: a live
  `Finsky.IsValid` check confirming the package was actually installed by
  the Play Store. A locally-signed, adb-installed APK can never satisfy that.

Two manifest fixes were real and are worth keeping (they'd matter the moment
this app is ever Play-distributed, and the second one likely still applies
to real users hitting it today):

- `android:intentMatchingFlags="allowNullAction"` on the service — Android
  15+'s strict intent-filter matching otherwise blocks the *bind*, once a
  package clears the validator. Root-caused via Home Assistant Android's
  GitHub issue #5534 (identical `PackageManager: Intent does not match
  component's intent filter` log line).
- `res/xml/automotive_app_desc.xml` (`<uses name="template"/>`) referenced
  via `com.google.android.gms.car.application` meta-data — the legacy
  "Android Auto for apps" declaration gearhead's validator still checks
  independently of the modern manifest declarations, confirmed by matching
  its exact log format string (`"Uses for %s not defined [%s]"`) against
  Home Assistant's working `automotive_app_desc.xml`.

**Net result: this app can never show up in Android Auto as a local/debug
build.** The only legitimate path is real Play Store distribution (even a
closed internal testing track), which changes the installer-attribution
check entirely — a distribution decision, not a code fix.

**Independent corroboration.** A completely separate reverse-engineering
effort — [matsumo0922/OneNavi](https://github.com/matsumo0922/OneNavi),
`docs/logs/10_android_auto_projection_gating_investigation.md` — decompiled
the same validator class on a newer gearhead build (v16.8, Pixel 10,
targetSdk 37) for a related question (getting a real `Activity` projected
the way Google Maps does, not just a `CarAppService`) and landed on the
identical allowlist, independently:

```
m = {MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE, SMS}
excluded: PROJECTION, TEMPLATE, MESSAGING
```

Confirms this isn't a stale build or a fluke of this specific device —
`TEMPLATE` exclusion is current and consistent across gearhead versions.

**Phase 2 — one real bypass exists, deliberately not pursued now.** Their
investigation traces an *earlier* check in the same validator, one we'd
already seen without registering its significance:

```java
if (I()) { return z4; }   // unconditional ALLOW — runs before the
                           // category-allowlist check above ever executes
```

`I()` is true when the connected head unit's reported `CarInfo` matches a
short hardcoded list of dev rigs — `"Google"/"Desktop Head Unit"`,
`"Google"/"Emulator"`, `"Google"/"tangorpro [AAR]"`,
`"Panasonic"/"Seahawk [AAR]"`. When it matches, gearhead trusts *any*
category, `TEMPLATE` included, and skips signature/Play/allowlist checks
entirely. That data comes from the car during the AA handshake — nothing
in our manifest or app code influences it.

The way this gets exploited in practice (per OneNavi's escape-hatch survey,
and matching what car-audio hobbyist tooling actually does): a **MITM proxy
between phone and car** that rewrites the handshake's make/model fields to
claim "Desktop Head Unit" instead of the real vehicle. `aa-proxy-rs` (a real
open-source wireless-AA-dongle firmware project) implements exactly this.
Everything else they catalogued — hooking `GoogleSignatureVerifier` via
Frida/LSPosed, spoofing `getInstallerPackageName()` to claim Play
installation, rewriting Phenotype server-flags — needs root and is riskier
for no more benefit.

**Deliberately not pursued now.** This stops being an app change and
becomes standing up separate hardware/firmware between the phone and the
car's head unit — a real infrastructure decision (cost, reliability,
maintenance of third-party dongle firmware) distinct in kind from everything
else in this project. Revisit only as an explicit, separately-scoped
decision — not a natural next step off this investigation.

---

### Legacy `MediaBrowserServiceCompat` apps reopen this — confirmed live, real hardware

The `TEMPLATE` block above is specific to `androidx.car.app` (Car App
Library) — every category in that library, including `ResumeCarAppService`'s
`IOT`, maps to that one blocked bucket. It says nothing about the *other*
allowed buckets. `{MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE,
SMS}` was sitting there the whole time, decompile-confirmed as allowed for
sideloaded/"Unknown sources" apps — this project just never tried building
against it, because every prior AA attempt was Car App Library or the
now-abandoned multichannel-audio `MediaBrowserService` idea (`E1`, rejected
for a stereo-only audio channel — irrelevant to a pure launcher that hands
off rather than plays).

Tested via a throwaway sibling project, **`AA-Test`** (`com.aatest`, see its
own `README.md`): a minimal legacy `MediaBrowserServiceCompat` (`androidx.
media`, not `androidx.car.app`), one fake "track," `automotive_app_desc.xml`
declaring `<uses name="media"/>` (the classic pre-Car-App-Library
declaration, same one Google's UAMP sample uses) instead of `ResumeCarApp
Service`'s `template`.

**Result: it appears in Android Auto's Customize Launcher, sideloaded, with
just the standard Developer Mode "Unknown sources" toggle.** No Phase 2
bypass, no spoofed `CarInfo`, no MITM proxy. Confirmed on real hardware, not
DHU. This is the first time anything in this project has gotten a sideloaded
surface onto the actual Android Auto screen.

**Getting the tap to actually work took two more rounds, and both fixes are
worth keeping in mind for any real implementation:**

1. **First attempt failed: "Could not load your selection."** The one media
   item had no icon set on its `MediaDescriptionCompat`. Adding
   `setIconUri()` pointing at a bundled drawable via an
   `android.resource://` URI was necessary but not sufficient.
2. **Second attempt, same error**, despite the icon fix and despite
   `onPlayFromMediaId` demonstrably firing (logged) and the deep-link
   `startActivity()` call demonstrably running (logged, and confirmed
   independently — `ytmprobe`'s own session logger picked up a fresh YTM
   session appearing right after the tap). Added `mediaSession.
   setPlaybackState()` calls (`STATE_BUFFERING` then `STATE_STOPPED`) around
   the launch, since gearhead's own log called the session "maybe is not
   activated" and the code had never called `setPlaybackState()` at all —
   still the same error.
3. **Third attempt succeeded**, with a different fix: instead of always
   cold-starting YTM via `startActivity()` on a deep link, port `Probes.
   playOrLaunch`'s actual logic — check for YTM's live `MediaController`
   first (via `MediaSessionManager`, gated on Notification Access, same
   `NotifListener` pattern) and command it directly with `playFromUri()`
   when one exists, falling back to the deep link only if not. YTM already
   had a live session in every test so far, so this path is the one that's
   actually been exercised. It worked immediately, logged: `"live YTM
   session found — commanding playFromUri directly, no activity launch."`

**What this actually shows:** the failure was never about the icon or the
playback state (those may be real requirements too, but neither fixed it
alone) — it's that **launching an `Activity` from inside a
`MediaSessionCompat.Callback` while gearhead's browse UI is live doesn't
work**, or at least doesn't satisfy whatever gearhead is waiting on to
consider the tap successful. Commanding an *existing* session directly —
no window, no activity, just a transport-control Binder call — does. This
rhymes with, but is a distinct mechanism from, the notification-trampoline
BAL block this same project already hit and fixed on the phone side (see
`PlayFavoriteActivity`'s doc comment) — that was a broadcast-receiver
restriction; this looks like something gearhead itself enforces on its
browse/playback UI, not a general Android BAL rule.

**Still genuinely untested: the cold-start case.** Every successful (and
failed) live test so far happened with YTM already running. Whether tapping
a favorite in Android Auto with YTM *dead* successfully cold-starts it via
the deep-link fallback — or hits the same "Could not load your selection"
wall the direct-activity-launch path did — is unknown. Given the pattern
above, there's real reason to expect it might fail the same way; if so, the
phone-side fix (an invisible trampoline `Activity`, `PlayFavoriteActivity`)
won't directly transfer, since gearhead's browse UI is a different caller
context than a notification tap. Worth testing explicitly before relying on
this for the actual "resume after a long drive" scenario, which is
precisely the case where YTM is most likely to be dead.

Force-stopping YTM and reconnecting to test this doesn't isolate anything —
the Samsung routine that opens YTM on AA connect (see "Cold start" section
below) relaunches it before a tap is even possible, so the app is never
actually dead when `AA-Test`'s service gets a chance to check. This needs a
YTM death that happens *mid-drive*, after the routine has already fired —
i.e. a real test on a longer drive, not a quick reconnect-and-tap.

**Practical implication:** this reopens what this document called flatly
"impossible" two sections up. The path to a real Android Auto surface for
`ytmprobe` isn't Car App Library and isn't the DHU-spoofing Phase 2 bypass —
it's a legacy `MediaBrowserServiceCompat`, the same architecture `ytmprobe`
already depends on `androidx.media` for (probe B1). Not yet folded back into
`ytmprobe` itself; `AA-Test` is a standalone, throwaway sibling project for
exactly this reason — real caller validation in `onGetRoot()`, a real
favorites list instead of one hardcoded track, and the cold-start question
above all need resolving first.

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
