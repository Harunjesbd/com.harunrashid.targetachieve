package com.harunrashid.targetachieve

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityPaywallBinding
import com.harunrashid.targetachieve.models.SerialKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class PaywallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaywallBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var supportNumber = "971552335930"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // ইচ্ছাকৃতভাবে কিছুই করা হচ্ছে না - ব্যাক প্রেস ব্লক করা হলো
            }
        })

        binding.btnActivate.setOnClickListener {
            val key = binding.etSerialKey.text.toString().trim().uppercase()
            if (key.isEmpty()) {
                Toast.makeText(this, "কোড লিখুন", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activateKey(key)
        }

        binding.btnContactSupport.setOnClickListener {
            val uri = Uri.parse("https://wa.me/$supportNumber")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        loadRemotePlans()
    }

    // প্ল্যানের নাম, দাম, পেমেন্ট নম্বর ও WhatsApp নম্বর Firestore এর config/plans থেকে (কোড বদলানো ছাড়াই)।
    // নথি বা ফিল্ড না থাকলে অ্যাপের নিজস্ব লেখাই দেখায়। কোনো প্ল্যানের নাম "-" লিখলে সেটি লুকানো থাকবে।
    private fun loadRemotePlans() {
        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val d = db.collection("config").document("plans").get().await()
                if (!d.exists()) return@launch

                fun s(k: String): String = (d.get(k)?.toString() ?: "").trim()
                fun setText(tv: android.widget.TextView, k: String) {
                    val v = s(k)
                    if (v.isNotEmpty()) tv.text = v
                }

                setText(binding.tvPlan1Name, "plan1Name"); setText(binding.tvPlan1Price, "plan1Price")
                setText(binding.tvPlan2Name, "plan2Name"); setText(binding.tvPlan2Price, "plan2Price")
                setText(binding.tvPlan3Name, "plan3Name"); setText(binding.tvPlan3Price, "plan3Price")
                if (s("plan1Name") == "-") binding.cardPlan1.visibility = View.GONE
                if (s("plan2Name") == "-") binding.cardPlan2.visibility = View.GONE
                if (s("plan3Name") == "-") binding.cardPlan3.visibility = View.GONE

                setText(binding.tvPayInstruction, "payText")
                setText(binding.tvPayNumber, "payNumber")

                val wa = s("supportWhatsapp").filter { it.isDigit() }
                if (wa.length >= 8) supportNumber = wa
            } catch (_: Exception) { }
        }
    }

    private fun activateKey(key: String) {
        val uid = profileKey() ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnActivate.isEnabled = false

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()

                val keyDoc = db.collection("serialKeys").document(key).get().await()

                if (!keyDoc.exists()) {
                    showError("এই কোডটা সঠিক নয়")
                    return@launch
                }

                val serialKey = keyDoc.toObject(SerialKey::class.java)
                if (serialKey == null || serialKey.used) {
                    showError("এই কোডটা আগেই ব্যবহার হয়ে গেছে")
                    return@launch
                }

                val userDoc = db.collection("users").document(uid).get().await()
                val currentExpiry = userDoc.getLong("subscriptionExpiryMillis") ?: 0L
                val now = com.harunrashid.targetachieve.logic.ServerTime.now(this@PaywallActivity)
                val base = if (currentExpiry > now) currentExpiry else now
                val newExpiry = base + TimeUnit.DAYS.toMillis(serialKey.planDays.toLong())

                // একসাথে দুটি লেখা (batch): কোড "ব্যবহৃত" + ব্যবহারকারীর মেয়াদ
                // Rules এই দুটি একসাথে না থাকলে মেয়াদ বাড়াতে দেয় না
                val batch = db.batch()
                batch.update(
                    db.collection("serialKeys").document(key),
                    mapOf(
                        "used" to true,
                        "usedByUid" to uid,
                        "usedAtMillis" to now
                    )
                )
                batch.update(
                    db.collection("users").document(uid),
                    mapOf(
                        "subscriptionExpiryMillis" to newExpiry,
                        "lastCode" to key
                    )
                )
                batch.commit().await()

                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@PaywallActivity, "✅ অ্যাক্টিভেট হয়েছে! ধন্যবাদ।", Toast.LENGTH_LONG).show()

                startActivity(Intent(this@PaywallActivity, MainActivity::class.java))
                finish()

            } catch (e: Exception) {
                showError("সমস্যা হয়েছে: ${e.message}")
            }
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnActivate.isEnabled = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}