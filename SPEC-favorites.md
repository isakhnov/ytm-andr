# YTM Probe — favorites tracking specification

Companion to [`FINDINGS.md`](FINDINGS.md), which records what was established
experimentally. This document specifies what gets built on top of it.

Every capability relied on here is proven — see FINDINGS §Current status.
Nothing in this spec depends on an untested assumption.

**Status:** phase 1 implemented in `ytmprobe`; phase 2 remains deferred.

---

## Contents

1. [Purpose](#purpose)
2. [Tracking service](#tracking-service)
3. [Favorites capture rules](#favorites-capture-rules)
4. [Storage](#storage)
5. [Resolution](#resolution)
6. [Pickers](#pickers)
7. [UI states](#ui-states)
8. [Phase 2 — storage cap](#phase-2--storage-cap)
9. [Acceptance criteria](#acceptance-criteria)
10. [Explicitly out of scope](#explicitly-out-of-scope)

---

## Purpose

Build a favorites list passively from what actually plays, so the resume app
can offer "play a random favorite" without the user curating anything.

Track identity is not published by YouTube Music (FINDINGS §What YTM's
MediaSession exposes), so favorites are stored by title+artist and resolved to
video IDs lazily.

---

## Tracking service

**One service, one duty.** The existing `SessionLogger` and the new favorites
poller are the same thing — a single foreground service that observes YTM and
records what it sees. There is no separate "poller" concept in the UI.

### Responsibilities

1. **Session lifecycle** (existing) — log YTM sessions appearing and
   disappearing with timestamps. This answers the car-connect timing question
   in FINDINGS §Open questions.
2. **Track observation** (new) — every **10 seconds**, read YTM's
   `MediaController` and apply the [capture rules](#favorites-capture-rules).

10s rather than 30s because a track liked and skipped inside one poll window
would be missed entirely, and capture is the whole point. A controller read is
local IPC — no network, no wake locks, negligible cost.

### Lifecycle

| Event | Behaviour |
|---|---|
| App launch | tracking **starts automatically** if not already running |
| *Stop tracking* pressed | service stops; state reflected immediately in UI |
| *Start tracking* pressed | service starts |
| App killed | service continues (`START_STICKY`) |
| Device reboot | tracking is **off** until the app is opened (phase 2: `BOOT_COMPLETED`) |

Auto-start on launch means the common case needs no interaction. The button
exists to stop it, and to restart after stopping.

### Skip conditions

A poll tick does nothing at all when:

- no YTM session exists
- metadata is null or the title is blank
- `USER_RATING` is absent from the metadata

**A skipped tick is not an observation.** It must never be interpreted as
"unliked" — a dead or paused session would otherwise silently delete
favorites.

---

## Favorites capture rules

Applied per poll tick, keyed on `norm(title)|norm(artist)` using the same
normalisation as the resolver (lowercase, noise words stripped, punctuation
removed).

| `USER_RATING` state | Meaning | Action |
|---|---|---|
| `isRated=true, thumbUp=true` | user liked it | **add** if absent; refresh `lastSeen` if present |
| `isRated=true, thumbUp=false` | user thumbed it **down** | **remove** from favorites |
| `isRated=false` | never rated, or rating cleared | **no signal — skip** |

`isRated=false` is deliberately inert. FINDINGS §E6 shows never-rated tracks
report `isRated=false thumbUp=false`, which is indistinguishable from a cleared
rating. Only an explicit thumb-down removes anything.

**Last definitive state wins.** The same song is polled many times and its like
state may change between reads; each definitive read overwrites the last. A
failed removal simply happens next time that song plays.

---

## Storage

New `favorites` blob in the existing `SharedPreferences`, alongside `last` and
`cache`.

```json
{
  "favorites": [
    {
      "key":        "слишком глупый план|airushv",
      "title":      "Слишком глупый план",
      "artist":     "AiRushV",
      "album":      "Слишком глупый план",
      "durationMs": 193000,
      "videoId":    "h0JZJ4ovvsM",
      "addedAt":    1754600000000,
      "lastSeenAt": 1754600000000,
      "playCount":  1
    }
  ]
}
```

`durationMs` and `album` are stored because both feed resolution scoring
(FINDINGS §Resolution) — without them a later resolve is markedly less
accurate.

`videoId` is blank until resolved.

### playCount

Incremented **once per play**, not once per poll. The service tracks the
currently-observed key and only increments when the observed track *changes*
to a different key. Otherwise a three-minute song would count eighteen times.

Not used in phase 1 beyond display; it exists so [phase 2](#phase-2--storage-cap)
has the data it needs.

---

## Resolution

Lazy, per FINDINGS §Resolution. Capture never blocks on the network.

**On capture:** check the resolution cache. A hit fills `videoId` for free. A
miss leaves it blank — no network call while driving.

**On resolve:** only entries with a blank `videoId` are processed. Entries that
already have one are never re-resolved, so manual corrections via the candidate
picker are permanent and cannot be overwritten by a fresh bad search.

**Triggers:**

- app start (background, non-blocking)
- *Resolve favorites* button
- favorites picker opening (see below)

No periodic timer. A 10-minute background resolve burns network while driving
for no benefit the button doesn't provide.

---

## Pickers

Two separate, unrelated pickers. They must not be conflated.

### 1. Candidate picker (exists)

Corrects a **resolution** mistake. Lists candidates from the last A+ run with
index, score, duration, type, title, artist. Selecting one writes the
correction to the cache permanently and fills the videoId field.

Unchanged by this spec.

### 2. Favorites picker (new)

Chooses **which song to play**. Distinct purpose, distinct button.

- Shows **10 favorites at random**
- Displays **title — artist only**. No IDs, no scores, no technical detail.
- **Refresh** button draws a different random 10 without closing the dialog
- Selecting one fills the videoId field, ready for probe C

**On open:** resolve any unresolved entries in the visible set first, then
**filter out anything that failed** — connectivity failure, no candidates, or
any other error. The user never sees a row that cannot be played.

If fewer than 10 resolve successfully, show what did. If none do, say so
plainly rather than showing an empty dialog.

---

## UI states

Tracking state must be visible without opening a menu.

| State | Button label | Indicator |
|---|---|---|
| Tracking | "Stop tracking" | ● active, adjacent to the button |
| Stopped | "Start tracking" | ○ stopped |

The indicator reflects **actual service state**, not the last button press —
Samsung's One UI can kill the service (FINDINGS §Samsung caveat), and the UI
must show that rather than claiming tracking is running when it isn't.

Also surface a **favorites count** so list growth is visible without opening
the picker.

---

## Phase 2 — storage cap

Not in the first implementation. Specified now so the schema supports it.

- **Cap: 1,000 entries.**
- On exceeding the cap, evict the **least used**: lowest `playCount` first,
  ties broken by oldest `lastSeenAt`.
- Eviction runs on insert, not on a timer.
- Never evict an entry added within the last 7 days, regardless of count — a
  newly liked song hasn't had a chance to accumulate plays.

This is why `playCount` and `lastSeenAt` are tracked from phase 1: retrofitting
usage data is impossible, and without it eviction would be arbitrary.

Also deferred to phase 2:

- `BOOT_COMPLETED` receiver so tracking survives a reboot without opening the app
- purge control for entries that repeatedly fail to resolve

---

## Acceptance criteria

1. Opening the app starts tracking without interaction; the indicator shows active.
2. Liking a track in YTM adds it to favorites within 10 seconds.
3. Thumbing a track down removes it within 10 seconds.
4. Neither pausing YTM nor force-stopping it removes anything.
5. A track already in the resolution cache is captured **with** its videoId, no network.
6. *Resolve favorites* fills blank IDs and leaves populated ones untouched.
7. The favorites picker shows only playable entries, 10 at a time, refreshable.
8. Selecting a favorite fills the videoId field and probe C plays that track.
9. Stopping tracking updates the indicator immediately and halts observation.
10. `playCount` increments once per play, not once per poll.

---

## Explicitly out of scope

**Importing existing YTM likes.** Only what plays is captured. Reading the
liked library requires authenticated InnerTube, and the browser-cookie auth
route was already found impractical (FINDINGS §Traps). The list builds over
weeks of normal listening — accepted.

**Playlist position.** Queue reading works (FINDINGS §E7) but is not used.
Resume is track-level.

**Automatic cold start.** Handled by the Samsung routine (FINDINGS §Cold
start), not by this app.
