package com.harunrashid.targetachieve.logic

import android.content.Context

object NotificationHelper {

    private const val PREFS = "notice_prefs"
    private const val KEY_BAND = "current_band"
    private const val KEY_BAND_START = "band_start_millis"
    private const val KEY_LAST_SHOWN_DATE = "last_shown_date"

    enum class Band(val minPercent: Double, val maxPercent: Double, val daysRequired: Int, val message: String) {
        LOW(5.0, 15.0, 3, "You are doing good, but You should increase Duty Time and Trip for the Good salary."),
        MID(20.0, 25.0, 7, "You are doing Better but you should increase Duty Time and Trip for the Better salary."),
        HIGH(30.0, 37.0, 7, "Excellent!! You are doing Best. Continue like this."),
        NONE(-1.0, -1.0, 0, "")
    }

    private fun bandFor(percent: Double): Band {
        return when {
            percent in Band.LOW.minPercent..Band.LOW.maxPercent -> Band.LOW
            percent in Band.MID.minPercent..Band.MID.maxPercent -> Band.MID
            percent in Band.HIGH.minPercent..Band.HIGH.maxPercent -> Band.HIGH
            else -> Band.NONE
        }
    }

    fun checkForNotice(context: Context, currentPercent: Double): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val band = bandFor(currentPercent)
        if (band == Band.NONE) return null

        val storedBand = prefs.getString(KEY_BAND, null)
        val now = System.currentTimeMillis()

        if (storedBand != band.name) {
            prefs.edit()
                .putString(KEY_BAND, band.name)
                .putLong(KEY_BAND_START, now)
                .remove(KEY_LAST_SHOWN_DATE)
                .apply()
            return null
        }

        val bandStart = prefs.getLong(KEY_BAND_START, now)
        val daysInBand = ((now - bandStart) / (24 * 60 * 60 * 1000)).toInt()

        if (daysInBand < band.daysRequired) return null

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())
        val lastShown = prefs.getString(KEY_LAST_SHOWN_DATE, "")
        if (lastShown == today) return null

        prefs.edit().putString(KEY_LAST_SHOWN_DATE, today).apply()
        return band.message
    }
}