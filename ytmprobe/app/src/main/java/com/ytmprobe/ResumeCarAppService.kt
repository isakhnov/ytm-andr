package com.ytmprobe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat

/**
 * Android Auto surface: five random favorites, tappable, plus a refresh.
 *
 * Plays by commanding YouTube Music's existing MediaSession with
 * playFromUri — proven in probe C (see FINDINGS §E8). This app never plays
 * audio itself; YTM keeps the session and its algorithm continues from the
 * chosen track.
 *
 * Category is IOT because Android Auto has no "utility" category and this app
 * is not navigation, messaging, or calling. Fine for sideloading; would not
 * pass Play review, which does not matter here.
 */
class ResumeCarAppService : CarAppService() {

    // Sideloaded personal build: no host allowlist to maintain.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = FavoritesScreen(carContext)
    }
}

class FavoritesScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        const val ROWS = 5
    }

    /** Held so refresh redraws a genuinely new sample rather than re-shuffling. */
    private var sample: List<Favorites.Fav> = emptyList()
    private var loaded = false

    private fun reload() {
        // Resolved only. There is no connectivity guarantee in the car and no
        // way to correct a bad pick from this screen, so unresolved entries
        // are never offered.
        sample = Favorites.all(carContext)
            .filter { it.videoId.isNotBlank() }
            .shuffled()
            .take(ROWS)
        loaded = true
    }

    override fun onGetTemplate(): Template {
        if (!loaded) reload()

        val list = ItemList.Builder()

        when {
            !Probes.hasNotificationAccess(carContext) ->
                list.addItem(
                    Row.Builder()
                        .setTitle("Permission needed")
                        .addText("Grant Notification Access in the phone app")
                        .build()
                )

            sample.isEmpty() ->
                list.addItem(
                    Row.Builder()
                        .setTitle("No favorites yet")
                        .addText("Thumbs-up tracks while tracking is active")
                        .build()
                )

            else -> sample.forEach { fav ->
                list.addItem(
                    Row.Builder()
                        .setTitle(fav.title)
                        .addText(fav.artist)
                        .setOnClickListener { play(fav) }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle("YTM Resume")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(
                                CarIcon.Builder(
                                    IconCompat.createWithResource(
                                        carContext, android.R.drawable.ic_menu_rotate
                                    )
                                ).build()
                            )
                            .setOnClickListener {
                                reload()
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun play(fav: Favorites.Fav) {
        val controller = Probes.ytmController(carContext)

        if (controller == null) {
            // The Samsung routine should have opened YTM on car connect. If a
            // tap lands with no session, say so plainly — launching a phone
            // activity from a car app is itself restricted and unreliable.
            ProbeLog.w(carContext, "car: tap on ${fav.label()} but no YTM session")
            screenManager.push(MessageScreen(
                carContext,
                "YouTube Music isn't running",
                "Open YouTube Music on the phone, then try again."
            ))
            return
        }

        val uri = Uri.parse("https://music.youtube.com/watch?v=${fav.videoId}")
        runCatching { controller.transportControls.playFromUri(uri, Bundle()) }
            .onSuccess { ProbeLog.w(carContext, "car: playing ${fav.label()}  ${fav.videoId}") }
            .onFailure {
                ProbeLog.w(carContext, "car: playFromUri failed: $it")
                screenManager.push(MessageScreen(
                    carContext, "Couldn't start playback", it.message ?: "Unknown error"
                ))
            }
    }
}

/** Minimal error screen — ListTemplate rather than MessageTemplate to keep
 *  the template-restriction budget simple. */
class MessageScreen(
    carContext: CarContext,
    private val title: String,
    private val detail: String
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        ListTemplate.Builder()
            .setSingleList(
                ItemList.Builder()
                    .addItem(Row.Builder().setTitle(title).addText(detail).build())
                    .build()
            )
            .setTitle("YTM Resume")
            .setHeaderAction(Action.BACK)
            .build()
}
