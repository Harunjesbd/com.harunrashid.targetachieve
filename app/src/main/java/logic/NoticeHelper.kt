package com.harunrashid.targetachieve.logic

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * অ্যাডমিনের বার্তা দেখায়: Firestore -> config -> notice নথি।
 *   active : true হলে দেখাবে
 *   id     : নতুন বার্তা দিলে এই সংখ্যা/লেখা বদলান (একই id একজন একবারই দেখবে)
 *   title, message           : সব ভাষার জন্য ডিফল্ট
 *   title_bn, message_bn ... : নির্দিষ্ট ভাষার জন্য (en, hi, ur, ar, bn) — ঐচ্ছিক
 */
object NoticeHelper {

    private var showing = false

    suspend fun checkAndShow(activity: AppCompatActivity) {
        if (showing) return
        try {
            val doc = FirebaseFirestore.getInstance()
                .collection("config").document("notice").get().await()
            if (!doc.exists()) return
            if (doc.getBoolean("active") != true) return

            val id = doc.get("id")?.toString()?.trim().orEmpty()
            if (id.isEmpty()) return

            val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("last_notice_id", null) == id) return

            val locales = AppCompatDelegate.getApplicationLocales()
            val lang = (if (!locales.isEmpty) locales[0]?.language else null)
                ?: Locale.getDefault().language

            val message = (doc.getString("message_$lang") ?: doc.getString("message"))?.trim().orEmpty()
            if (message.isEmpty()) return
            val title = (doc.getString("title_$lang") ?: doc.getString("title"))?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: "Target Achieve"

            if (activity.isFinishing || activity.isDestroyed) return

            showing = true
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    prefs.edit().putString("last_notice_id", id).apply()
                }
                .setOnDismissListener { showing = false }
                .show()
        } catch (e: Exception) {
            showing = false // ইন্টারনেট না থাকলে চুপচাপ বাদ
        }
    }
}
