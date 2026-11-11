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
import com.google.firebase.firestore.FirebaseFirestore
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

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLoginSubmit.isEnabled = false

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()

                val doc = db.collection("users").document(key).get().await()
                if (!doc.exists()) {
                    showError("এই মোবাইল নম্বরে কোনো অ্যাকাউন্ট পাওয়া যায়নি। Mobile নম্বর চেক করুন বা Sign Up করুন।")
                    return@launch
                }

                val p = doc.toObject(UserProfile::class.java)
                if (p == null) {
                    showError("অ্যাকাউন্ট ডেটা পড়া যায়নি।")
                    return@launch
                }

                val nameOk = p.name.trim().equals(name, ignoreCase = true)
                val driverOk = p.driverId.trim().equals(driverNo, ignoreCase = true)

                when {
                    !nameOk && !driverOk ->
                        showError("Mobile ঠিক আছে, কিন্তু Name এবং Driver No দুটোই মিলছে না।")
                    !nameOk ->
                        showError("Mobile ঠিক আছে, কিন্তু Name মিলছে না।")
                    !driverOk ->
                        showError("Mobile ও Name ঠিক আছে, কিন্তু Driver No মিলছে না।")
                    else -> {
                        getSharedPreferences("app_prefs", MODE_PRIVATE)
                            .edit().putString("profile_key", key).putBoolean("account_ready", true).apply()
                        val i = Intent(this@LoginActivity, MainActivity::class.java)
                        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(i)
                        finish()
                    }
                }
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