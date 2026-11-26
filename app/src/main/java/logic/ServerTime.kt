package com.harunrashid.targetachieve.logic

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ফোনের তারিখ/সময়ের বদলে Firebase সার্ভারের সময় দেয়।
 * ফোনের তারিখ আগে-পরে করলেও ট্রায়াল/সাবস্ক্রিপশন হিসাব বদলাবে না।
 *
 * দ্রুত খোলার জন্য: সার্ভার থেকে সময় নেওয়ার পর ফোনের "চালু থাকার ঘড়ি" (elapsedRealtime, যা তারিখ বদলালে বদলায় না)
 * দিয়ে সময় গুনে নেওয়া হয়। ৬ ঘণ্টার মধ্যে আবার ইন্টারনেটে সার্ভারকে জিজ্ঞেস করতে হয় না।
 */
object ServerTime {

    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    suspend fun now(ctx: Context): Long {
        val prefs = ctx.getSharedPreferences("server_time", Context.MODE_PRIVATE)
        val lastServer = prefs.getLong("last_server_millis", 0L)
        val lastElapsed = prefs.getLong("last_elapsed", -1L)

        // সার্ভার-সময় + এরপর যত সময় গেছে (ফোন রিবুট না হলে সঠিক)
        val elapsedNow = SystemClock.elapsedRealtime()
        val estimate: Long? =
            if (lastServer > 0L && lastElapsed >= 0L && elapsedNow >= lastElapsed)
                lastServer + (elapsedNow - lastElapsed)
            else null

        if (estimate != null && (elapsedNow - lastElapsed) < REFRESH_MS) return estimate

        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotEmpty() } ?: "unknown"

        val serverMillis: Long? = try {
            withTimeoutOrNull(5000L) {
                val ref = FirebaseFirestore.getInstance().collection("serverClock").document(id)
                ref.set(mapOf("t" to FieldValue.serverTimestamp())).await()
                val snap = ref.get(Source.SERVER).await()
                snap.getTimestamp("t")?.toDate()?.time
            }
        } catch (e: Exception) {
            null
        }

        if (serverMillis != null) {
            prefs.edit()
                .putLong("last_server_millis", serverMillis)
                .putLong("last_elapsed", SystemClock.elapsedRealtime())
                .apply()
            return serverMillis
        }
        // ইন্টারনেট না থাকলে: ফোনের সময়, জানা সার্ভার-সময় ও গোনা সময়ের মধ্যে যেটা বড় সেটা নিই
        // (ফোনের তারিখ পেছনে নিয়ে ট্রায়াল বাড়ানো যাবে না)
        return maxOf(System.currentTimeMillis(), lastServer, estimate ?: 0L)
    }
}
