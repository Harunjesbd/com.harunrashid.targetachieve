package com.harunrashid.targetachieve

import android.content.Intent
import android.os.Bundle
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.harunrashid.targetachieve.databinding.ActivityLoginBinding
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var blinkAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLoginWhatsApp.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/971552335930"))) }
            catch (e: Exception) { Toast.makeText(this, "WhatsApp: +971 55 233 5930", Toast.LENGTH_LONG).show() }
        }
        binding.btnLoginEmail.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:admininfo.bd@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "Target Achieve - Login problem")
                })
            } catch (e: Exception) { Toast.makeText(this, "Email: admininfo.bd@gmail.com", Toast.LENGTH_LONG).show() }
        }

        binding.btnLoginSubmit.setOnClickListener { login() }

        // প্রথমবার ইনস্টলে (এই ফোনে এখনো অ্যাকাউন্ট হয়নি) "New User? Create Account" ঝলমল করবে
        val accountReady = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("account_ready", false)
        if (!accountReady) {
            blinkAnim = ObjectAnimator.ofFloat(binding.btnGoToSignup, "alpha", 1f, 0.2f).apply {
                duration = 700
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        binding.btnGoToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            finish()
        }
    }

    private fun login() {
        val name = binding.etLoginName.text.toString().trim()
        val driverNo = binding.etLoginDriverNo.text.toString().trim()
        val mobile = binding.etLoginMobile.text.toString().trim()
        val key = mobileToKey(mobile)

        if (name.isEmpty() || driverNo.isEmpty() || key.length < 9) {
            Toast.makeText(this, "Name, Driver No এবং Mobile তিনটাই দিন", Toast.LENGTH_SHORT).show()
            return
        }

        // পরপর ভুল চেষ্টা আটকানো: ৫ বার ভুল হলে ১৫ মিনিট বন্ধ
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lockUntil = prefs.getLong("login_lock_until", 0L)
        val nowMs = System.currentTimeMillis()
        if (lockUntil > nowMs) {
            val mins = ((lockUntil - nowMs) / 60000L) + 1
            showError("অনেকবার ভুল হয়েছে। $mins মিনিট পরে আবার চেষ্টা করুন।")
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLoginSubmit.isEnabled = false

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val uid = auth.currentUser?.uid ?: ""
                val ref = db.collection("users").document(key)

                // নিরাপদ দাবি (claim): Name ও Driver No সার্ভারের Rules যাচাই করে।
                // মিললে এই ফোনের পরিচয় অ্যাকাউন্টের সঙ্গে বাঁধা হয়, না মিললে (বা অ্যাকাউন্ট না থাকলে) একই বার্তা,
                // ফলে কোন অংশ ঠিক/ভুল তা জানা যায় না।
                var ok = false
                try {
                    ref.update(
                        mapOf(
                            "uid" to uid,
                            "migrated" to true,
                            "claimName" to name.lowercase(),
                            "claimDriverId" to driverNo.lowercase()
                        )
                    ).await()
                    ok = true
                } catch (e: FirebaseFirestoreException) {
                    if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                        showError("ইন্টারনেট সংযোগ নেই। সংযোগ দেখে আবার চেষ্টা করুন।")
                        return@launch
                    }
                }

                if (!ok) {
                    val fails = prefs.getInt("login_fail_count", 0) + 1
                    if (fails >= 5) {
                        prefs.edit().putInt("login_fail_count", 0)
                            .putLong("login_lock_until", System.currentTimeMillis() + 15 * 60_000L).apply()
                    } else {
                        prefs.edit().putInt("login_fail_count", fails).apply()
                    }
                    showError("তথ্য মিলছে না। Name, Driver No ও Mobile আবার দেখুন।\nঅ্যাকাউন্ট না থাকলে \"New User? Create Account\" চাপুন।")
                    return@launch
                }

                // সফল: অস্থায়ী দাবির ঘর দুটো মুছে ফেলা
                try {
                    ref.update(
                        mapOf(
                            "claimName" to FieldValue.delete(),
                            "claimDriverId" to FieldValue.delete()
                        )
                    ).await()
                } catch (_: Exception) { }

                prefs.edit().putInt("login_fail_count", 0).putLong("login_lock_until", 0L)
                    .putString("profile_key", key).putBoolean("account_ready", true).apply()
                val i = Intent(this@LoginActivity, MainActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
                finish()
            } catch (e: Exception) {
                showError("Login failed: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLoginSubmit.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        blinkAnim?.cancel()
        super.onDestroy()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Login Failed")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}