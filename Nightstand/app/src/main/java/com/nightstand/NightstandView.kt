package com.nightstand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.View
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The whole landscape display, drawn on one Canvas:
 *
 *   ┌──────────────────────────────┬─────────────────────┐
 *   │  10:42                       │ UP NEXT             │
 *   │  Tuesday, November 24        │ ● 11:00 Standup     │
 *   │  ⚡ 87%                       │ ● 14:30 Dentist     │
 *   ├──────────────────────────────┴─────────────────────┤
 *   │  [icon] Messages 3 · Alex     [icon] Gmail 2 …     │
 *   └────────────────────────────────────────────────────┘
 *
 * Deliberately a plain View, not an Activity layout: the same view can be
 * hosted by a DreamService later (idea A in README) with no changes.
 *
 * Burn-in: the whole layout drifts a few pixels every minute ([shiftFor]).
 * Night: every pixel is drawn in one dim red — no white, no app colours.
 */
class NightstandView(context: Context) : View(context) {

    var night = false
        set(v) { field = v; invalidate() }

    /** Night "motion to wake": temporarily brighter red. */
    var nightBoost = false
        set(v) { field = v; invalidate() }

    var events: List<Event> = emptyList()
        set(v) { field = v; invalidate() }
    var notifs: List<NotifSummary> = emptyList()
        set(v) { field = v; invalidate() }
    var showNotifContent = false
    var batteryPct = -1
    var charging = true

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconCache = HashMap<String, Drawable?>()

    // Day palette (OLED black background, white-ish text) vs night (single red).
    private val red get() = if (nightBoost) 0xFFFF3B30.toInt() else 0xFFB3241B.toInt()
    private val primary get() = if (night) red else 0xFFF5F5F7.toInt()
    private val secondary get() = if (night) red else 0xFF9A9AA0.toInt()
    private val accent get() = if (night) red else 0xFFFF9F0A.toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val now = Calendar.getInstance()
        val (sx, sy) = shiftFor(now.get(Calendar.MINUTE), dp(10f))
        canvas.save()
        canvas.translate(sx, sy)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(28f)
        val hasNotifs = notifs.isNotEmpty()
        val bottomH = if (hasNotifs) dp(64f) else 0f
        val splitX = w * 0.58f

        drawClock(canvas, now, pad, pad, splitX - pad, h - bottomH - pad)
        drawEvents(canvas, now, splitX + dp(8f), pad, w - pad, h - bottomH - pad)
        if (hasNotifs) drawNotifs(canvas, pad, h - bottomH, w - pad, h - dp(12f))

        canvas.restore()
    }

    private fun drawClock(c: Canvas, now: Calendar, l: Float, t: Float, r: Float, b: Float) {
        val is24 = DateFormat.is24HourFormat(context)
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()

        // Fit the time to the column width.
        clockPaint.color = primary
        clockPaint.textSize = 100f
        val fit = (r - l) / clockPaint.measureText("00:00")
        clockPaint.textSize = minOf(100f * fit, (b - t) * 0.62f)
        val fm = clockPaint.fontMetrics
        val clockH = fm.descent - fm.ascent

        textPaint.textSize = clockPaint.textSize * 0.16f
        boldPaint.textSize = textPaint.textSize

        val dateLine = DateFormat.format(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now
        ).toString()
        val blockH = clockH + textPaint.textSize * 2.8f
        var y = t + ((b - t) - blockH) / 2 - fm.ascent
        c.drawText(time, l, y, clockPaint)
        if (!is24) {
            textPaint.color = secondary
            c.drawText(
                if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM",
                l + clockPaint.measureText(time) + dp(8f), y, textPaint
            )
        }
        y += fm.descent + textPaint.textSize * 1.3f
        boldPaint.color = accent
        c.drawText(dateLine, l + dp(4f), y, boldPaint)

        if (batteryPct >= 0) {
            y += textPaint.textSize * 1.4f
            textPaint.color = secondary
            c.drawText((if (charging) "⚡ " else "") + "$batteryPct%", l + dp(4f), y, textPaint)
        }
    }

    private fun drawEvents(c: Canvas, now: Calendar, l: Float, t: Float, r: Float, b: Float) {
        val titleSize = minOf(dp(22f), (b - t) / 9f)
        boldPaint.textSize = titleSize * 0.7f
        boldPaint.color = secondary
        var y = t + boldPaint.textSize + dp(16f)
        c.drawText(if (events.isEmpty()) "NO MORE EVENTS" else "UP NEXT", l, y, boldPaint)
        y += dp(14f)

        val todayEnd = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val is24 = DateFormat.is24HourFormat(context)
        val rowH = titleSize * 2.6f
        for (e in events) {
            if (y + rowH > b) break
            val tomorrow = e.begin >= todayEnd
            val whenStr = when {
                e.allDay -> if (tomorrow) "Tomorrow · all day" else "All day"
                else -> (if (tomorrow) "Tomorrow " else "") +
                    DateFormat.format(if (is24) "HH:mm" else "h:mm a", Date(e.begin))
            }
            dotPaint.color = if (night) red else (e.color or 0xFF000000.toInt())
            c.drawRoundRect(l, y + dp(4f), l + dp(4f), y + rowH - dp(6f), dp(2f), dp(2f), dotPaint)

            textPaint.textSize = titleSize
            textPaint.color = primary
            val avail = r - l - dp(14f)
            val title = TextUtils.ellipsize(e.title, textPaint, avail, TextUtils.TruncateAt.END)
            c.drawText(title, 0, title.length, l + dp(14f), y + titleSize, textPaint)

            textPaint.textSize = titleSize * 0.72f
            textPaint.color = secondary
            c.drawText(whenStr, l + dp(14f), y + titleSize * 2.05f, textPaint)
            y += rowH
        }
    }

    private fun drawNotifs(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val iconSize = (b - t) * 0.55f
        val cy = (t + b) / 2
        textPaint.textSize = iconSize * 0.62f
        var x = l
        val maxChips = 3
        for ((i, n) in notifs.withIndex()) {
            if (i == maxChips) {
                textPaint.color = secondary
                c.drawText("+${notifs.size - maxChips} more", x, cy + textPaint.textSize / 3, textPaint)
                break
            }
            val chipW = (r - l) / maxChips - dp(12f)
            icon(n.pkg)?.let { d ->
                d.setBounds(x.toInt(), (cy - iconSize / 2).toInt(), (x + iconSize).toInt(), (cy + iconSize / 2).toInt())
                d.colorFilter = if (night) PorterDuffColorFilter(red, PorterDuff.Mode.SRC_IN) else null
                d.alpha = if (night) 255 else 230
                d.draw(c)
            }
            val shownTitle = if (showNotifContent) n.title else n.publicTitle
            val label = buildString {
                append(n.appLabel)
                if (n.count > 1) append(" ").append(n.count)
                if (!shownTitle.isNullOrBlank()) append(" · ").append(shownTitle)
            }
            textPaint.color = primary
            val tx = x + iconSize + dp(10f)
            val text = TextUtils.ellipsize(label, textPaint, chipW - iconSize - dp(10f), TextUtils.TruncateAt.END)
            c.drawText(text, 0, text.length, tx, cy + textPaint.textSize / 3, textPaint)
            x += chipW + dp(12f)
        }
    }

    private fun icon(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        try { context.packageManager.getApplicationIcon(pkg).mutate() } catch (_: Throwable) { null }
    }

    companion object {
        /**
         * OLED burn-in protection: walk a small square, one step per minute, so
         * no pixel draws the same static digit edge all night.
         */
        fun shiftFor(minute: Int, amplitude: Float): Pair<Float, Float> {
            val steps = arrayOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
            val (a, b) = steps[minute % steps.size]
            return a * amplitude to b * amplitude
        }
    }
}
