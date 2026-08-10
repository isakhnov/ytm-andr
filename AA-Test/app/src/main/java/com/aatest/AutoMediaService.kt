package com.aatest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat

/**
 * The entire hypothesis under test: does a legacy MediaBrowserServiceCompat
 * app (category MEDIA, per gearhead's decompiled CAR.VALIDATOR — see
 * README.md) actually appear in Android Auto's Customize Launcher when
 * sideloaded with "Unknown sources" enabled, unlike ytmprobe's
 * ResumeCarAppService (androidx.car.app, category TEMPLATE — confirmed
 * blocked by the same validator; see ytmprobe/FINDINGS.md).
 *
 * onGetRoot() is deliberately permissive (any caller gets a root) — this is
 * a local visibility test, not a distributable app. A real caller
 * allowlist (gearhead's package + signature, at minimum) would be needed
 * before this became anything more than that.
 */
class AutoMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        private const val TAG = "AATest"
        private const val ROOT_ID = "root"
        private const val ITEM_ID = "play_test_track"
        // Rick Astley — Never Gonna Give You Up. Chosen only because it's
        // guaranteed to exist and be unambiguous to recognize on-screen;
        // swap for anything to confirm the right track actually opens.
        private const val TEST_VIDEO_ID = "dQw4w9WgXcQ"
        private const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        mediaSession = MediaSessionCompat(this, "AATest").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // Live test #1 (icon-less item) failed with "Could not load your
            // selection"; fixing the missing icon alone did not fix it.
            // logcat showed onPlayFromMediaId DID fire and the YTM launch
            // DID run (ytmprobe's own session logger picked up a fresh YTM
            // session right after), but gearhead's own log called our
            // session "maybe is not activated" — we never called
            // setPlaybackState() at all, ever. Android Auto's browse UI
            // appears to wait for this session's own state to move before
            // it treats a tap as successful, regardless of what actually
            // happens in the launched app. STATE_NONE here just establishes
            // a baseline; playAndHandOff() below does the real transition.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.i(TAG, "onPlayFromMediaId: $mediaId")
                    if (mediaId == ITEM_ID) playAndHandOff()
                }

                override fun onPlay() {
                    Log.i(TAG, "onPlay")
                    playAndHandOff()
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i(TAG, "onGetRoot: caller=$clientPackageName uid=$clientUid hints=$rootHints")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        Log.i(TAG, "onLoadChildren: $parentId")
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        // setIconUri is not cosmetic here — Android Auto's browse UI failed
        // with "Could not load your selection" on the first live test with
        // no icon set at all. A resource URI is enough; no ContentProvider
        // needed.
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(ITEM_ID)
            .setTitle("AA Test — tap to play")
            .setSubtitle("Confirms this app is visible and controllable from Android Auto")
            .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_play}"))
            .build()
        result.sendResult(mutableListOf(MediaItem(description, MediaItem.FLAG_PLAYABLE)))
    }

    /**
     * Mirrors ytmprobe's Probes.ytmController(): finds YTM's own live
     * MediaController via MediaSessionManager, which requires this app to
     * hold Notification Access (granted by NotifListener's mere existence —
     * see its doc comment). Requires the user to grant it manually first
     * (Settings → Notifications → Special app access → Notification access
     * → AA Test), same one-time step ytmprobe needs.
     */
    private fun ytmController(): MediaController? = try {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mgr.getActiveSessions(ComponentName(this, NotifListener::class.java))
            .firstOrNull { it.packageName == YTM_PACKAGE }
    } catch (e: SecurityException) {
        Log.w(TAG, "no Notification Access — cannot query YTM's session", e)
        null
    }

    /**
     * Live test #2 always cold-started via deep link (startActivity) and
     * still got "Could not load your selection" even after acknowledging
     * the tap with setPlaybackState() — the same fix that closed the
     * identical-looking bug in ytmprobe's lock-screen notification turned
     * out not to be the (whole) story here. Reusing ytmprobe's actual
     * playOrLaunch logic instead: if YTM already has a live session (it did
     * in both live tests — ytmprobe's own session logger saw it), command
     * it directly via playFromUri on the MediaController. That's not an
     * activity launch at all — no window, no BAL, no gearhead app-switch —
     * just a transport-control call, which is likely a much better fit for
     * whatever gearhead's browse UI is actually waiting on. Falls back to
     * the deep-link cold start only when there's truly no session to
     * command, same as the phone-side fix.
     */
    private fun playAndHandOff() {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f)
                .build()
        )

        val uri = Uri.parse("https://music.youtube.com/watch?v=$TEST_VIDEO_ID")
        val live = ytmController()
        if (live != null) {
            Log.i(TAG, "live YTM session found — commanding playFromUri directly, no activity launch")
            runCatching { live.transportControls.playFromUri(uri, Bundle()) }
                .onFailure { Log.w(TAG, "playFromUri failed", it) }
        } else {
            Log.i(TAG, "no live YTM session — cold-starting via deep link: $uri")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(YTM_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
                .onFailure { Log.w(TAG, "launch failed", it) }
        }

        // We never actually play anything ourselves — this is a handoff to
        // YTM's own session, not real playback — so drop to STOPPED rather
        // than claim STATE_PLAYING for audio we don't have.
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                .build()
        )
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        mediaSession.release()
        super.onDestroy()
    }
}
