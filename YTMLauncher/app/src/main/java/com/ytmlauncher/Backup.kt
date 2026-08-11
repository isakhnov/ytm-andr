package com.ytmlauncher

import android.content.Context
import org.json.JSONObject

/**
 * Export/import for Favorites+Store's data. Both objects share one
 * SharedPreferences file (PREFS = "ytmlauncher"), so this mirrors the whole
 * file rather than knowing about their specific keys — robust to either
 * gaining new keys later without this needing an update too.
 *
 * Exists for two reasons: this app has allowBackup="false" and no other
 * export path, so ~50 hand-curated favorites (hand-corrected videoIds,
 * hand-assigned genres, built up over real driving/testing) currently have
 * exactly zero redundancy — a factory reset or accidental uninstall loses
 * them permanently. It's also the write side of the one-time migration from
 * ytmprobe (see that project's data; migration reads ytmprobe's prefs file
 * directly via `adb exec-out run-as`, reshapes it into this same JSON
 * format, and imports it here — see this app's README).
 */
object Backup {

    private const val PREFS = "ytmlauncher"

    fun export(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is String -> out.put(k, v)
                is Boolean -> out.put(k, v)
                is Int -> out.put(k, v)
                is Long -> out.put(k, v)
                is Float -> out.put(k, v)
                else -> Unit // Set<String> etc. — unused by Favorites/Store, skip rather than fail
            }
        }
        return out.toString(2)
    }

    /** Overwrites every key present in [json]; keys not present are left untouched. Returns the count written. */
    fun import(ctx: Context, json: String): Int {
        val obj = JSONObject(json)
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        var count = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.get(k)) {
                is String -> editor.putString(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }
}
