package com.harunrashid.targetachieve

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
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

    private val adminEmail = "admininfo.bd@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val driverId = binding.etDriverId.text.toString().trim()
            val mobile = binding.etMobile.text.toString().trim()

            if (name.isEmpty() || driverId.isEmpty() || mobile.isEmpty()) {
                Toast.makeText(this, "Please fill in Name, Driver ID, and Mobile number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val taxiType = if (binding.rbAirportTaxi.isChecked) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI
            processUserRegistration(name, driverId, mobile, taxiType)
        }
    }

    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }

    private fun processUserRegistration(name: String, driverId: String, mobile: String, taxiType: TaxiType) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnStart.isEnabled = false

        val deviceId = getUniqueDeviceId()

        lifecycleScope.launch {
            try {
                // Ensure Firebase User Auth exists
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
                val uid = auth.currentUser?.uid ?: throw IllegalStateException("Authentication failed")
                val userEmail = auth.currentUser?.email ?: ""

                val isAdminAccount = userEmail.equals(adminEmail, ignoreCase = true) || name.equals(adminEmail, ignoreCase = true)

                // Perform Device Lock validation ONLY for regular drivers
                if (!isAdminAccount) {
                    val existingDeviceQuery = db.collection("users")
                        .whereEqualTo("deviceId", deviceId)
                        .get()
                        .await()

                    if (!existingDeviceQuery.isEmpty) {
                        val registeredUser = existingDeviceQuery.documents[0].toObject(UserProfile::class.java)

                        // Lock device if already bound to a different Driver ID
                        if (registeredUser != null && registeredUser.driverId.isNotEmpty() && registeredUser.driverId != driverId) {
                            binding.progressBar.visibility = View.GONE
                            binding.btnStart.isEnabled = true
                            Toast.makeText(
                                this@SignupActivity,
                                "Device Lock: This device is registered to Driver ID ${registeredUser.driverId}",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                    }
                }

                // Create or Update Driver Profile in Firestore
                val now = System.currentTimeMillis()
                val profile = UserProfile(
                    uid = uid,
                    name = name,
                    driverId = driverId,
                    mobile = mobile,
                    taxiType = taxiType.name,
                    deviceId = deviceId,
                    trialStartMillis = now,
                    trialExpiryMillis = now + (55L * 24 * 60 * 60 * 1000), // 55 Days Trial
                    subscriptionExpiryMillis = 0L,
                    createdAtMillis = now
                )

                db.collection("users").document(uid).set(profile).await()

                binding.progressBar.visibility = View.GONE

                // Redirect directly to Driver Dashboard (MainActivity)
                val intent = Intent(this@SignupActivity, MainActivity::class.java)
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnStart.isEnabled = true
                Toast.makeText(
                    this@SignupActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}