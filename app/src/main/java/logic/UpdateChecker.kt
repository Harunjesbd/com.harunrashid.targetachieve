package com.harunrashid.targetachieve.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Firestore -> config -> app নথি:
 *   latestVersionCode : number  (নতুন সংস্করণের versionCode, যেমন 2)
 *   latestVersionName : string  (যেমন "1.1")
 *   updateUrl         : string  (APK ডাউনলোডের লিংক, যেমন Google Drive)
 *   forceUpdate       : boolean (true হলে "Later" বোতাম থাকবে না) — ঐচ্ছিক
 *   message / message_bn / message_hi ... : পপ-আপের লেখা — ঐচ্ছিক
 */
object UpdateChecker {

    private var showing = false

    // নতুন সংস্করণ থাকলে পপ-আপ দেখায় এবং true ফেরত দেয়
    suspend fun check(activity: AppCompatActivity): Boolean {
        if (showing) return true
        try {
            val doc = FirebaseFirestore.getInstance()
                .collection("config").document("app").get().await()
            if (!doc.exists()) return false

            val latest = doc.getLong("latestVersionCode") ?: return false
            val url = doc.getString("updateUrl")?.trim().orEmpty()
            if (url.isEmpty()) return false

            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val current = PackageInfoCompat.getLongVersionCode(info)
            if (latest <= current) return false

            val force = doc.getBoolean("forceUpdate") == true
            val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // জোর করার আপডেট না হলে দিনে একবারই জিজ্ঞেস করবে
            if (!force && now - prefs.getLong("last_update_prompt", 0L) < 24L * 60 * 60 * 1000) return false
            if (activity.isFinishing || activity.isDestroyed) return false

            val locales = AppCompatDelegate.getApplicationLocales()
            val lang = (if (!locales.isEmpty) locales[0]?.language else null)
                ?: Locale.getDefault().language
            val name = doc.getString("latestVersionName") ?: latest.toString()
            val message = (doc.getString("message_$lang") ?: doc.getString("message"))
                ?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "A new version of Target Achieve is available. Please update."

            showing = true
            val builder = AlertDialog.Builder(activity)
                .setTitle("Update available (v$name)")
                .setMessage(message)
                .setCancelable(!force)
                .setPositiveButton("Update") { _, _ ->
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .setOnDismissListener {
                    showing = false
                    prefs.edit().putLong("last_update_prompt", now).apply()
                }
            if (!force) builder.setNegativeButton("Later", null)
            builder.show()
            return true
        } catch (e: Exception) {
            showing = false
            return false
        }
    }
}