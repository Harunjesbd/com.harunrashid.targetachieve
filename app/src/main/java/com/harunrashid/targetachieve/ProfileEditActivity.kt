package com.harunrashid.targetachieve

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityProfileEditBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = profileKey()
        if (key == null) { finish(); return }

        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(key).get().await()
                binding.etEditName.setText(doc.getString("name") ?: "")
                binding.etEditDriverId.setText(doc.getString("driverId") ?: "")
                binding.tvEditMobile.text = "Mobile: " + (doc.getString("mobile") ?: "")
            } catch (e: Exception) {
                Toast.makeText(this@ProfileEditActivity, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSaveProfile.setOnClickListener {
            val name = binding.etEditName.text.toString().trim()
            val driverNo = binding.etEditDriverId.text.toString().trim()
            if (name.isEmpty() || driverNo.isEmpty()) {
                Toast.makeText(this, "Name ও Driver No খালি রাখা যাবে না", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    db.collection("users").document(key)
                        .update(mapOf("name" to name, "driverId" to driverNo)).await()
                    Toast.makeText(this@ProfileEditActivity, "Profile updated ✅", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@ProfileEditActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}