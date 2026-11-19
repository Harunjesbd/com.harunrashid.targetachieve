package com.harunrashid.targetachieve.logic

import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ফোনের তারিখ/সময়ের বদলে Firebase সার্ভারের সময় দেয়।
 * ফোনের তারিখ আগে-পরে করলেও ট্রায়াল/সাবস্ক্রিপশন হিসাব বদলাবে না।
 */
object ServerTime {

    suspend fun now(ctx: Context): Long {
        val prefs = ctx.getSharedPreferences("server_time", Context.MODE_PRIVATE)
        val lastServer = prefs.getLong("last_server_millis", 0L)

        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotEmpty() } ?: "unknown"

        val serverMillis: Long? = try {
            withTimeoutOrNull(8000L) {
                val ref = FirebaseFirestore.getInstance().collection("serverClock").document(id)
                ref.set(mapOf("t" to FieldValue.serverTimestamp())).await()
                val snap = ref.get(Source.SERVER).await()
                snap.getTimestamp("t")?.toDate()?.time
            }
        } catch (e: Exception) {
            null
        }

        if (serverMillis != null) {
            prefs.edit().putLong("last_server_millis", serverMillis).apply()
            return serverMillis
        }
        // ইন্টারনেট না থাকলে: ফোনের সময় ও শেষ জানা সার্ভার-সময়ের মধ্যে যেটা বড় সেটা নিই
        // (ফোনের তারিখ পেছনে নিয়ে ট্রায়াল বাড়ানো যাবে না)
        return maxOf(System.currentTimeMillis(), lastServer)
    }
}
