package com.ytmlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * "YTM Launch" — the app's default entry point. One row per genre (a random
 * resolved favorite tagged with it), plus a pinned "Resume" row when a mix
 * seed is stored, tap to play, swipe left to remove / swipe right or
 * long-press to tag genre (via FavoriteActions), reshuffled on every
 * refresh. RecyclerView + ItemTouchHelper for swipe — GestureDetector-based
 * swipe (ytmprobe's original approach) was broken by construction: its
 * onFling() only recognises a fast, decisive flick, not the slow controlled
 * drag people actually use for swipe-to-act, and a row inside a vertically-
 * scrolling container intercepts anything with a vertical component before
 * a fling can even be measured. ItemTouchHelper is the standard Android
 * answer for exactly this conflict.
 */
class LaunchActivity : NavActivity() {

    companion object {
        private const val RESUME_KEY = "__resume__"
        private const val POST_NOTIFICATIONS_REQUEST = 1
    }

    private lateinit var appHeader: AppHeader
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: GenreRowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YTM Launch"

        if (!SessionLogger.running && Probes.hasNotificationAccess(this)) startTracking()
        requestPostNotificationsIfNeeded()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        appHeader = AppHeader(this)
        root.addView(appHeader.view)

        emptyView = TextView(this).apply {
            text = "no tagged, resolved favorites yet — resolve and tag genres from Settings first"
            setPadding(0, 16, 0, 8)
        }
        root.addView(emptyView)

        adapter = GenreRowAdapter(
            onPlay = { f -> play(f) },
            onLongPress = { f -> FavoriteActions.showGenreTagPicker(this, f) { reload() } }
        )

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LaunchActivity)
            adapter = this@LaunchActivity.adapter
        }
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f }
        )
        ItemTouchHelper(SwipeCallback()).attachToRecyclerView(recyclerView)

        root.addView(Button(this).apply {
            text = "Refresh"
            isAllCaps = false
            setOnClickListener { reload() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        reload()
        Probes.resolveFavorites(this, quiet = true) { _, _ -> reload() }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST)
    }

    private fun startTracking() {
        val i = Intent(this, SessionLogger::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun reload() {
        val all = Favorites.all(this).filter { it.videoId.isNotBlank() }
        val picks = Favorites.GENRES.mapNotNull { genre ->
            all.filter { it.genre == genre }.shuffled().firstOrNull()
        }

        // Pinned first — "resume where I left off" is the app's whole
        // premise, and this is the only in-app way to manually re-trigger it
        // rather than waiting for SessionLogger's automatic auto-continue.
        // Synthetic Fav, not a real Favorites entry: RESUME_KEY never
        // matches a stored key, so swipe/long-press on this row safely
        // no-op against Favorites.remove/setGenre rather than corrupting
        // real data.
        val seed = Store.loadMixSeed(this)
        val rows = if (seed != null && seed.videoId.isNotBlank()) {
            listOf(
                Favorites.Fav(
                    key = RESUME_KEY, title = seed.title, artist = seed.artist, album = "",
                    durationMs = 0L, videoId = seed.videoId, addedAt = 0L, lastSeenAt = 0L,
                    playCount = 0, genre = "Resume"
                )
            ) + picks
        } else picks

        adapter.submit(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun play(f: Favorites.Fav) {
        ProbeLog.w(this, "launch play: ${f.label()}  ${f.videoId}")
        appHeader.setMixHeader(f.title, f.artist)
        Probes.playFavorite(this, f.title, f.artist, f.videoId)
    }

    /**
     * LEFT = remove, RIGHT = tag genre — same two actions as long-press,
     * just driven by ItemTouchHelper's touch handling. notifyItemChanged()
     * after triggering either action resets that row's drag offset
     * immediately: showGenreTagPicker only calls back on OK, so without
     * this a Cancelled dialog would leave the row looking permanently
     * swiped away until some unrelated refresh happened to fix it.
     */
    private inner class SwipeCallback :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

        private val labelPaint = Paint().apply {
            isAntiAlias = true
            textSize = 34f
            color = Color.WHITE
        }
        private val removeBg = Paint().apply { color = Color.parseColor("#B71C1C") }
        private val tagBg = Paint().apply { color = Color.parseColor("#2E7D32") }

        override fun onMove(
            rv: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val f = adapter.itemAt(position)
            if (direction == ItemTouchHelper.LEFT) {
                FavoriteActions.removeFavorite(this@LaunchActivity, f) { reload() }
            } else {
                FavoriteActions.showGenreTagPicker(this@LaunchActivity, f) { reload() }
            }
            adapter.notifyItemChanged(position)
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val item = viewHolder.itemView
            when {
                dX > 0 -> {
                    c.drawRect(
                        item.left.toFloat(), item.top.toFloat(), dX, item.bottom.toFloat(), tagBg
                    )
                    c.drawText(
                        "Tag genre", item.left + 32f,
                        item.top + item.height / 2f + 12f, labelPaint
                    )
                }
                dX < 0 -> {
                    c.drawRect(
                        item.right + dX, item.top.toFloat(), item.right.toFloat(), item.bottom.toFloat(), removeBg
                    )
                    val w = labelPaint.measureText("Remove")
                    c.drawText(
                        "Remove", item.right - 32f - w,
                        item.top + item.height / 2f + 12f, labelPaint
                    )
                }
            }
            super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun onResume() {
        super.onResume()
        appHeader.start()
    }

    override fun onPause() {
        appHeader.stop()
        super.onPause()
    }
}

private class GenreRowAdapter(
    private val onPlay: (Favorites.Fav) -> Unit,
    private val onLongPress: (Favorites.Fav) -> Unit
) : RecyclerView.Adapter<GenreRowAdapter.RowHolder>() {

    private var items: List<Favorites.Fav> = emptyList()

    fun submit(newItems: List<Favorites.Fav>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Favorites.Fav = items[position]

    class RowHolder(val row: LinearLayout, val titleView: TextView, val subView: TextView) :
        RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val ctx = parent.context
        val titleView = TextView(ctx).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E3ECFF"))
        }
        val subView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8FA6D6"))
            setPadding(0, 2, 0, 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E2A47"))
            setPadding(28, 22, 28, 22)
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8 }
            addView(titleView)
            addView(subView)
            isClickable = true
            isHapticFeedbackEnabled = true
            // Ripple drawn on top of the row's own background color — the
            // themed selectableItemBackground rather than a hand-rolled
            // state-list, so it matches the system's ripple color/speed.
            val ripple = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = ctx.getDrawable(ripple.resourceId)
        }
        return RowHolder(row, titleView, subView)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val f = items[position]
        holder.titleView.text = f.title
        holder.subView.text = "${f.artist}  ·  ${f.genre}"
        holder.row.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onPlay(f)
        }
        holder.row.setOnLongClickListener { onLongPress(f); true }
    }

    override fun getItemCount(): Int = items.size
}
