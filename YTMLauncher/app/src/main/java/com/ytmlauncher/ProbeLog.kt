package com.ytmlauncher

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only log shared by the activity and the background service.
 *
 * File lands in the app's external files dir, so it can be pulled with:
 *   adb pull /sdcard/Android/data/com.ytmlauncher/files/probe.log
 */
object ProbeLog {

    const val TAG = "YTMLauncher"
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) { listener = l }

    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), "probe.log")

    fun w(ctx: Context, msg: String) {
        val line = "${stamp.format(Date())}  $msg"
        Log.i(TAG, msg)
        runCatching { file(ctx).appendText(line + "\n") }
        listener?.invoke(line)
    }

    fun section(ctx: Context, title: String) {
        w(ctx, "")
        w(ctx, "===== $title =====")
    }

    fun read(ctx: Context): String =
        runCatching { file(ctx).readText() }.getOrDefault("(log empty)")

    fun clear(ctx: Context) {
        runCatching { file(ctx).writeText("") }
    }
}
