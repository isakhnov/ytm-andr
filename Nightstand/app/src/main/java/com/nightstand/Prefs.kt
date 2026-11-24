package com.nightstand

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nightstand", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Show notification titles on the locked screen (iPhone hides them until Face ID). */
    fun showContent(c: Context) = sp(c).getBoolean("showContent", false)
    fun setShowContent(c: Context, v: Boolean) = sp(c).edit().putBoolean("showContent", v).apply()
}
