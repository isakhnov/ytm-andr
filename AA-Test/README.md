# AA-Test

A single-hypothesis test app, sibling to `ytmprobe`, not a product. It set
out to answer one question — **does a legacy `MediaBrowserServiceCompat` app
(category `MEDIA`) appear in Android Auto's Customize Launcher when
sideloaded, unlike `ytmprobe`'s `ResumeCarAppService`?** — and the answer is
**yes, confirmed on real hardware.** See `ytmprobe/FINDINGS.md`'s "Legacy
MediaBrowserServiceCompat apps reopen this" section for the full writeup;
this file covers just this throwaway project's own state.

## Background

`ytmprobe/FINDINGS.md`'s "Dead ends ruled out" section documents decompiling
`com.google.android.projection.gearhead`'s own `CAR.VALIDATOR` (class `iwt`)
and finding a hard-coded category allowlist for sideloaded/"Unknown sources"
apps:

```
allowed:   {MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE, SMS}
excluded:  {TEMPLATE, MESSAGING, PROJECTION}
```

Every `androidx.car.app` category — including `ResumeCarAppService`'s `IOT` —
maps internally to the single bucket `TEMPLATE`, which is excluded. This app
uses the older, pre-Car-App-Library media integration (`androidx.media`,
`MediaBrowserServiceCompat`) instead, which gearhead buckets as `MEDIA` — a
category that *is* on the allowed list.

## What happened, in order

1. Built with `automotive_app_desc.xml` declaring `<uses name="media"/>`
   (the classic pre-Car-App-Library declaration, same one Google's UAMP
   sample uses) instead of `ResumeCarAppService`'s `template`. **Appeared in
   Android Auto's Customize Launcher, sideloaded, standard "Unknown sources"
   toggle only.** No Phase 2 bypass needed.
2. First tap: **"Could not load your selection."** The one media item had
   no icon. Added `setIconUri()` via an `android.resource://` URI — helped,
   but didn't fully fix it.
3. Second tap, same error, despite `onPlayFromMediaId` and the deep-link
   `startActivity()` call both demonstrably firing (logged; `ytmprobe`'s own
   session logger independently saw a fresh YTM session appear right after).
   Added `mediaSession.setPlaybackState()` transitions around the launch —
   still the same error.
4. Third attempt: stopped always cold-starting via `startActivity()` and
   ported `ytmprobe`'s actual `Probes.playOrLaunch` logic instead — check
   for YTM's live `MediaController` first (`MediaSessionManager`, gated on
   Notification Access via `NotifListener`, same pattern as `ytmprobe`), and
   command it directly with `playFromUri()` when one exists. **Worked
   immediately.** Log: `"live YTM session found — commanding playFromUri
   directly, no activity launch."`

**Conclusion:** the failure was never really about the icon or the playback
state — it's that launching an `Activity` from inside a
`MediaSessionCompat.Callback` while gearhead's browse UI is live doesn't
satisfy whatever gearhead is waiting on. Commanding an *existing* session
directly (no window, no activity, just a transport-control call) does.

**Still untested: cold start.** Every test so far had YTM already running.
Whether the deep-link fallback (used when there's no live session) works or
hits the same wall as the direct-`startActivity()` attempts above is
unknown — and that's precisely the case ("resume after YTM got killed on a
long drive") the real feature needs to handle.

Force-stopping YTM and reconnecting doesn't test this: the Samsung routine
that opens YTM on AA connect relaunches it before a tap is possible, so it's
never actually dead by the time the service checks. Needs a real mid-drive
kill — testing on a longer drive, not a quick reconnect-and-tap.

## Build & install

```bash
./build.sh
```

Same JDK-pinning/SDK-license/`local.properties` dance as `ytmprobe/build.sh`.
No launcher `Activity` — the only surface is the service.

Notification Access must be granted once for the live-session check to work
(`ytmController()` throws `SecurityException` without it, silently falling
back to cold-start): either via Settings → Notifications → Special app
access → Notification access → AA Test, or directly over adb:

```bash
adb shell cmd notification allow_listener com.aatest/com.aatest.NotifListener
```

## Next steps

Not yet folded back into `ytmprobe` — this stays a throwaway sibling until:

- The cold-start case above is actually tested.
- `onGetRoot()` gets real caller validation (currently accepts anyone —
  fine for a local test, not for anything more).
- The single hardcoded track becomes a real favorites list
  (`ytmprobe`'s `Favorites`), each item its own `onPlayFromMediaId` target.

## Known simplifications (test app, not a product)

- `onGetRoot()` accepts any caller — no signature/package allowlist.
- `automotive_app_desc.xml`'s `<uses name="media"/>` is high-confidence
  (matches Google's UAMP sample) but not decompile-verified the way
  `ytmprobe`'s `template` declaration is — it just empirically worked.
