# Nightstand

An iPhone-StandBy-style bedside display for Android, built for and to be tested on a
**Samsung Galaxy S26 Ultra** (One UI 9 / Android 17) in a magnetic Qi2 case on a stand.

Put the phone on the charger **sideways, still and locked** → full-screen clock,
upcoming calendar events and a notification strip. In a dark room it turns **dim red**.
Tap to brighten for a moment, double-tap to unlock. Pick it up or pull the
charger and it goes away.

> Status: **prototype**. It builds and its logic is unit-tested, but it hasn't run on a
> real S26 Ultra yet. See [On-device test plan](#on-device-test-plan).
> This lives inside `ytm-andr` for now and is self-contained, so it can move to its
> own repo as-is (`git subtree split -P Nightstand` keeps its history).

---

## How iPhone StandBy works (the target)

- **Starts when:** charging (cable, MagSafe or Qi) + landscape + locked + still. A
  MagSafe car mount in landscape doesn't count while moving.
- **Shows:** big clock / two widget stacks (calendar etc.) / photos. Notifications as
  large banners, content hidden until Face ID unlocks.
- **Night Mode:** in a dark room everything turns dim red, driven by the light
  sensor rather than the clock. "Motion to Wake" brightens it on a nudge.
- **Always-on** on Pro models; others sleep and wake on tap.

Samsung has nothing equivalent: landscape Always On Display has no calendar, no red
mode and no charging trigger. The Display → Screen saver menu only offers basic
built-in screensavers. Google's own StandBy-like "hub mode" (Android 16 QPR2) is
Pixel-only.

## Three ways to start, and why this prototype uses B

### A — Screensaver (`DreamService`)
Implement Android's screensaver API. The system starts it on charge (Settings →
Display → Screen saver → "While charging") and draws it over the lock screen.

- ➕ Official, no special permissions, no background service, near-zero battery cost.
- ➖ **The system decides when it starts:** only on screen timeout while charging. It
  doesn't care whether the phone is sideways, so it starts face-up on a bedside pad
  too, and it doesn't start when you put the phone down with the screen already off.
- ➖ **Depends on Samsung keeping the Screen saver menu.** It's reportedly missing on
  some Samsung models, and the Always On Display can take its place. Nobody can
  confirm the S26 Ultra / One UI 9 setup without the phone.
- ➖ Rotation is awkward: screensavers can't force landscape. Content has to be turned
  by hand from the sensor.

### B — Own trigger: watcher service + lock-screen Activity ✅ chosen
A small foreground service watches **power connected → accelerometer posture →
locked/screen-off**, and when all hold it launches an Activity that shows over the
lock screen and turns the screen on. It launches the way an alarm clock does (a
full-screen notification), plus a direct launch when "Display over other apps" is
granted.

- ➕ **Implements StandBy's actual rule:** sideways + still + charging + locked. Lying
  flat or upright on the charger does nothing; turning the phone sideways does.
- ➕ Depends on nothing Samsung-specific. Uses stock APIs that every alarm app uses.
- ➕ Full control of the screen: fixed landscape, brightness override for night mode,
  keep-screen-on, immersive.
- ➖ Needs a set of one-time grants: full-screen alerts, display over apps, battery
  unrestricted. Fine for a sideloaded app, and the setup screen walks through them.
  Getting this through Play Store review would be harder.
- ➖ An always-running service. When unplugged it's just one broadcast listener; the
  sensor and wake lock only run while plugged in.

### C — Accessibility-service overlay
Use an accessibility service to draw a window over everything, including the lock
screen, triggered the same way as B.

- ➕ Draws above the lock screen without the full-screen notification path. Survives
  apps that grab the screen.
- ➖ Can't turn the screen on, so it needs the screen already on or a separate wake
  trick.
- ➖ Accessibility is a very powerful permission. One UI shows scary warnings,
  sometimes switches services off after updates, and Google is tightening its use for
  anything that isn't real accessibility.
- ➖ Touch, rotation and lock-screen behaviour of overlays are less predictable across
  One UI versions than a normal Activity.

### Verdict
**B** is the only option that reproduces the defining behaviour, *sideways on the
charger means StandBy*, regardless of what Samsung ships. A depends on a Samsung menu
we can't verify and ignores orientation. C trades B's alarm-style launch for an
intrusive permission with no gain.

B is also built so A stays cheap to add: the whole display is a single
`NightstandView` (a plain `View`). A `DreamService` can host the same view in ~30
lines, as a fallback if Samsung ever blocks the launch path.

---

## Architecture

```
WatcherService (foreground, specialUse)
  ├─ ACTION_POWER_CONNECTED/DISCONNECTED ─┐
  ├─ ACTION_SCREEN_OFF / SCREEN_ON        ├─► TriggerPolicy ──► launch()
  └─ accelerometer (only while plugged)   │       (pure)        ├─ startActivity  (if overlay granted)
        └─► PostureDetector (pure) ───────┘                     └─ full-screen-intent notification
                                                                        │
NightstandActivity  (showWhenLocked + turnScreenOn + keep-screen-on, sensorLandscape)
  ├─ NightstandView           clock · date · battery · events · notification strip · burn-in shift
  ├─ light sensor ─► NightModeController (pure)  → red palette + window brightness 0.01
  ├─ CalendarSource           CalendarContract.Instances, now → end of tomorrow
  └─ NotificationFeed         NotificationListenerService, grouped per app, silent/ongoing dropped
```

| File | Role |
|---|---|
| `WatcherService.kt` | Trigger: receivers, accelerometer + wake lock while plugged, launch routes |
| `PostureDetector.kt` | Gravity → LANDSCAPE / PORTRAIT / FLAT, "still for 1.5s", drops at once on motion |
| `TriggerPolicy.kt` | When to show: charging ∧ landscape ∧ locked ∧ armed ∧ not visible. Re-arm rules |
| `NightModeController.kt` | Lux hysteresis (enter < 3 lx, leave > 15 lx, 8s hold), schedule fallback |
| `NightstandActivity.kt` | The StandBy screen; gestures; closes itself if you unlock and leave |
| `NightstandView.kt` | Canvas-drawn landscape UI; red-only palette at night; per-minute pixel shift |
| `CalendarSource.kt` | Upcoming events from all synced calendars; declined events skipped |
| `NotificationFeed.kt` | Notification listener; respects each app's lock-screen visibility |
| `SetupActivity.kt` | Permission checklist, toggles, preview, live debug log |

**Re-arm rules** (`TriggerPolicy`), so it never fights you:
- shows once per *reason*: charger connected, screen turned off, or phone moved out
  of landscape and back;
- double-tap to unlock → stays away while you use the phone → comes back when the
  screen times out (iPhone behaviour);
- power button while showing → stays the top screen; it's there when you wake it.

**Privacy:** notification titles appear only if the posting app marks them public, or
if you turn on "Show notification titles while locked". Otherwise you just see the app
name and count.

**Charging vs plugged:** uses *plugged* (`EXTRA_PLUGGED`), not *charging*, because One
UI 9's battery protection stops charging at 80/90% while the phone is still on the stand.

## Build

```bash
./build.sh            # builds, installs and opens setup if a device is attached via adb
./gradlew testDebugUnitTest --no-daemon   # 18 JVM unit tests, no device needed
```
Toolchain matches the sibling projects: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, JDK
17/21, compileSdk 36, targetSdk 35, minSdk 30. No Compose, no AndroidX beyond
core-ktx.

## First run on the S26 Ultra

Open **Nightstand** and turn every row green:
1. **Notifications**: allow.
2. **Full-screen alerts**: allow (Android 14+ lists it under special app access).
3. **Display over other apps**: allow (lets it start directly; belt-and-braces with 2).
4. **Battery: unrestricted**: otherwise One UI kills the watcher overnight.
5. **Calendar**: allow.
6. **Notification access**: enable Nightstand (optional; strip stays empty without it).

Then put it on the stand sideways and lock it.

## On-device test plan

The unit tests cover the logic; these are what only the phone can answer:

| # | Check | Expected | If not |
|---|---|---|---|
| 1 | Lock, place sideways on the charger | Nightstand appears within ~2s | Check the debug log on the setup screen: did it see `power connected`, `landscape (stable)`, `launching`? |
| 2 | Same, screen already off | Screen turns on with Nightstand | If not, the full-screen intent is being shown as a heads-up only. Check grant 2 |
| 3 | Place flat / upright on the charger | Nothing happens | Posture thresholds in `PostureDetector.classify` |
| 4 | Lights off | Red within ~8s, very dim | Log shows `night=true (lux=…)`. Tune `enterLux` |
| 5 | Tap at night | Brighter red for 6s | |
| 6 | Double-tap | Unlock prompt → closes | |
| 7 | Use the phone unlocked on the stand, let it time out | Nightstand returns | |
| 8 | Lift off stand / unplug | Closes within ~3s | |
| 9 | Leave overnight | Still working in the morning; no burn-in ghosting | Battery setting (grant 4) |
| 10 | Reboot | Watcher restarts by itself | `BootReceiver` |
| 11 | Samsung Always On Display on | Nightstand still appears over it | |

## Next steps after the device test
- Swap the night "tap to brighten" for real Motion to Wake (accelerometer nudge).
- Settings: brightness levels, which calendars, clock style, 24h override.
- Weather and alarm-time line (next `AlarmManager.getNextAlarmClock()`).
- Optional `DreamService` host for idea A as a fallback.
- Move to its own repo.
