package com.nightstand

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process glue between WatcherService, NightstandActivity and the
 * notification listener. Everything runs on the main thread; all three live in
 * the app's single process.
 */
object StandByState {
    private val main = Handler(Looper.getMainLooper())

    /** WatcherService listens for the Activity becoming visible/hidden. */
    var listener: ((Boolean) -> Unit)? = null

    /** Set by the visible NightstandActivity so the watcher can close it. */
    var hideRequest: (() -> Unit)? = null

    /** Anything on screen that should redraw (notifications changed, etc.). */
    val changeListeners = mutableSetOf<() -> Unit>()

    var lastPosture = PostureDetector.Posture.UNKNOWN

    fun setVisible(v: Boolean) = listener?.invoke(v)
    fun requestHide() = hideRequest?.invoke()
    fun notifyChanged() = main.post { changeListeners.toList().forEach { it() } }

    // Small ring buffer shown on the setup screen — the prototype's debug log.
    private val log = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun appendLog(msg: String) {
        log.addLast("${fmt.format(Date())}  $msg")
        while (log.size > 40) log.removeFirst()
    }

    @Synchronized
    fun logText(): String = log.reversed().joinToString("\n")
}
