package com.harunrashid.targetachieve

import android.content.Context

fun Context.profileKey(): String? =
    getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("profile_key", null)

fun mobileToKey(mobile: String): String {
    val digits = mobile.filter { it.isDigit() }
    return if (digits.length > 9) digits.takeLast(9) else digits
}