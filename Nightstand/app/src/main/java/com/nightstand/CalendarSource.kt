package com.nightstand

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

data class Event(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

/**
 * Upcoming events from Android's shared calendar provider. Samsung Calendar,
 * Google Calendar and Exchange/Outlook accounts all sync into it, so nothing
 * account-specific is needed.
 */
object CalendarSource {
    fun isGranted(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events still running or starting before the end of tomorrow. */
    fun upcoming(c: Context, now: Long = System.currentTimeMillis(), limit: Int = 4): List<Event> {
        if (!isGranted(c)) return emptyList()
        val endOfTomorrow = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // All-day instances are stored in UTC midnight-to-midnight; widen the
        // query window so today's all-day events aren't missed in negative-UTC zones.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 86_400_000L)
            android.content.ContentUris.appendId(it, endOfTomorrow)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = mutableListOf<Event>()
        try {
            c.contentResolver.query(
                uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = cur.getInt(3) == 1
                    var begin = cur.getLong(1)
                    var end = cur.getLong(2)
                    if (allDay) { begin = utcDayToLocal(begin); end = utcDayToLocal(end) }
                    if (end <= now) continue
                    out += Event(cur.getString(0) ?: "(no title)", begin, end, allDay, cur.getInt(4))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        // Timed events first by start, all-day ones of today float to the top.
        return out.sortedWith(compareBy({ !it.allDay }, { it.begin })).take(limit)
    }

    private fun utcDayToLocal(utcMidnight: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}
