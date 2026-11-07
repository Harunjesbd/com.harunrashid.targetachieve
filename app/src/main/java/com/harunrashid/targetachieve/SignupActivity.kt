package com.harunrashid.targetachieve

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivitySignupBinding
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (profileKey() != null) {
            goMain(); return
        }

        binding.btnStart.setOnClickListener { signup() }
        binding.btnGoToLogin.setOnClickListener { goLogin() }
    }

    private fun signup() {
        val name = binding.etName.text.toString().trim()
        val driverNo = binding.etDriverId.text.toString().trim()
        val mobile = binding.etMobile.text.toString().trim()
        val key = mobileToKey(mobile)
        val taxiType = if (binding.rbAirportTaxi.isChecked) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI

        if (name.isEmpty() || driverNo.isEmpty() || key.length < 9) {
            Toast.makeText(this, "Name, Driver No এবং সঠিক Mobile দিন", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnStart.isEnabled = false

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()

                val ref = db.collection("users").document(key)
                if (ref.get().await().exists()) {
                    askGoLogin("এই মোবাইল নম্বরে আগেই অ্যাকাউন্ট আছে। Log In করুন।")
                    return@launch
                }

                // অ্যাডমিন নম্বর হলে "এক ফোনে এক অ্যাকাউন্ট" নিয়ম প্রযোজ্য নয়
                val isAdminNumber = db.collection("admins").document(key).get().await().exists()

                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                if (!isAdminNumber && deviceId.isNotEmpty()) {
                    val dup = db.collection("users").whereEqualTo("deviceId", deviceId).limit(1).get().await()
                    if (!dup.isEmpty) {
                        askGoLogin("এই ফোনে আগেই একটি অ্যাকাউন্ট খোলা হয়েছে। Log In করুন।")
                        return@launch
                    }
                }

                val now = System.currentTimeMillis()
                val profile = UserProfile(
                    uid = auth.currentUser?.uid ?: "",
                    mobileKey = key,
                    name = name,
                    driverId = driverNo,
                    mobile = mobile,
                    taxiType = taxiType.name,
                    deviceId = deviceId,
                    trialStartMillis = now,
                    trialExpiryMillis = now + 60L * 24 * 60 * 60 * 1000,
                    subscriptionExpiryMillis = 0L,
                    createdAtMillis = now
                )
                ref.set(profile).await()

                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("profile_key", key).apply()
                goMain()
            } catch (e: Exception) {
                Toast.makeText(this@SignupActivity, "Signup failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnStart.isEnabled = true
            }
        }
    }

    private fun askGoLogin(msg: String) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Log In") { _, _ -> goLogin() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun goMain() {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }
}