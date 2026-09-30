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
            val uri = Uri.parse("https://wa.me/971552335930")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun activateKey(key: String) {
        val uid = auth.currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnActivate.isEnabled = false

        lifecycleScope.launch {
            try {
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
                val now = System.currentTimeMillis()
                val base = if (currentExpiry > now) currentExpiry else now
                val newExpiry = base + TimeUnit.DAYS.toMillis(serialKey.planDays.toLong())

                db.collection("users").document(uid)
                    .update("subscriptionExpiryMillis", newExpiry).await()

                db.collection("serialKeys").document(key)
                    .update(
                        mapOf(
                            "used" to true,
                            "usedByUid" to uid,
                            "usedAtMillis" to now
                        )
                    ).await()

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