package com.harunrashid.targetachieve

import com.harunrashid.targetachieve.logic.ServerTime
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
    private var etEmail: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (profileKey() != null) {
            goMain(); return
        }

        addEmailField()
        binding.btnStart.setOnClickListener { signup() }
        binding.btnGoToLogin.setOnClickListener { goLogin() }
    }

    // Mobile ঘরের ঠিক নিচে Email ঘর কোডে যোগ করা হয় (layout ফাইল বদলাতে হয় না)
    private fun addEmailField() {
        try {
            val mobile = binding.etMobile
            val parent = mobile.parent as? LinearLayout ?: return
            val idx = parent.indexOfChild(mobile)
            val mp = mobile.layoutParams as ViewGroup.MarginLayoutParams

            val label = TextView(this).apply {
                text = "Email"
                setTextColor(0xFFE6CF9E.toInt())
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
            }
            val et = EditText(this).apply {
                hint = "name@gmail.com"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                background = mobile.background?.constantState?.newDrawable()?.mutate()
                setTextColor(mobile.currentTextColor)
                setHintTextColor(mobile.currentHintTextColor)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(mobile.paddingLeft, mobile.paddingTop, mobile.paddingRight, mobile.paddingBottom)
                layoutParams = LinearLayout.LayoutParams(mp)
            }
            parent.addView(label, idx + 1)
            parent.addView(et, idx + 2)
            etEmail = et
        } catch (_: Exception) {
            etEmail = null
        }
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

        val email = etEmail?.text?.toString()?.trim()?.lowercase() ?: ""
        if (etEmail != null && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "সঠিক Email দিন (যেমন name@gmail.com)", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnStart.isEnabled = false

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()

                val ref = db.collection("users").document(key)
                // সুরক্ষিত (migrated) অ্যাকাউন্টের নথি অন্যের পড়া নিষেধ: PERMISSION_DENIED মানে অ্যাকাউন্ট আগেই আছে
                val alreadyExists = try {
                    ref.get().await().exists()
                } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                    if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) true else throw e
                }
                if (alreadyExists) {
                    askGoLogin("এই মোবাইল নম্বরে আগেই অ্যাকাউন্ট আছে। Log In করুন।")
                    return@launch
                }

                // অ্যাডমিন নম্বর হলে "এক ফোনে এক অ্যাকাউন্ট" নিয়ম প্রযোজ্য নয়
                val isAdminNumber = db.collection("admins").document(key).get().await().exists()

                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                // এক ফোনে এক অ্যাকাউন্ট: deviceIds/{ফোনের id} নথি থাকলে আগে অ্যাকাউন্ট খোলা হয়েছে
                if (!isAdminNumber && deviceId.isNotEmpty()) {
                    val dup = db.collection("deviceIds").document(deviceId).get().await()
                    if (dup.exists()) {
                        askGoLogin("এই ফোনে আগেই একটি অ্যাকাউন্ট খোলা হয়েছে। Log In করুন।")
                        return@launch
                    }
                }

                val now = ServerTime.now(this@SignupActivity)

                // ফ্রি ট্রায়ালের দিন Firestore config/app এর trialDays ফিল্ড থেকে (না থাকলে ৬০)
                val trialDays = try {
                    db.collection("config").document("app").get().await()
                        .getLong("trialDays")?.toInt()?.coerceIn(1, 730) ?: 60
                } catch (_: Exception) { 60 }

                val profile = UserProfile(
                    uid = auth.currentUser?.uid ?: "",
                    mobileKey = key,
                    name = name,
                    driverId = driverNo,
                    mobile = mobile,
                    taxiType = taxiType.name,
                    email = email,
                    deviceId = deviceId,
                    trialStartMillis = now,
                    trialExpiryMillis = now + trialDays * 24L * 60 * 60 * 1000,
                    subscriptionExpiryMillis = 0L,
                    createdAtMillis = now,
                    migrated = true
                )
                ref.set(profile).await()

                // এই ফোনটি চিহ্নিত করে রাখা (ব্যর্থ হলেও Sign Up চলবে)
                if (!isAdminNumber && deviceId.isNotEmpty()) {
                    try {
                        db.collection("deviceIds").document(deviceId)
                            .set(mapOf("mobileKey" to key, "createdAtMillis" to now)).await()
                    } catch (_: Exception) { }
                }

                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                    .putString("profile_key", key)
                    .putBoolean("account_ready", true)
                    .apply()
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