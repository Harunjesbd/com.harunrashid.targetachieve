package com.harunrashid.targetachieve

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityProfileEditBinding
import com.harunrashid.targetachieve.logic.RateProvider
import com.harunrashid.targetachieve.logic.SlabDialog
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private val db = FirebaseFirestore.getInstance()

    private var key = ""
    private var taxi = TaxiType.SHIFT_TAXI
    private var profile: UserProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val k = profileKey()
        if (k == null) { finish(); return }
        key = k

        binding.tabProfile.setOnClickListener { showTab(true) }
        binding.tabSettings.setOnClickListener { showTab(false) }
        showTab(true)

        binding.rgRateMode.setOnCheckedChangeListener { _, _ -> refreshRateUi() }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        binding.btnSaveRates.setOnClickListener { saveRates() }
        binding.btnViewSlabSettings.setOnClickListener { SlabDialog.show(this, null) }

        loadProfile()
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(key).get().await()
                binding.etEditName.setText(doc.getString("name") ?: "")
                binding.etEditDriverId.setText(doc.getString("driverId") ?: "")
                binding.tvEditMobile.text = "Mobile: " + (doc.getString("mobile") ?: "")

                val p = doc.toObject(UserProfile::class.java)
                profile = p
                if (p != null) {
                    taxi = p.taxiTypeEnum()
                    RateProvider.applyProfile(p)
                    // রেডিও সেট করলে listener চলে refreshRateUi() ডাকবে
                    if (p.rateMode == "CUSTOM") binding.rbRateCustom.isChecked = true
                    else binding.rbRateDefault.isChecked = true
                }
                refreshRateUi()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileEditActivity, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- ট্যাব ----------
    private fun showTab(profileTab: Boolean) {
        binding.sectionProfile.visibility = if (profileTab) View.VISIBLE else View.GONE
        binding.sectionSettings.visibility = if (profileTab) View.GONE else View.VISIBLE
        styleTab(binding.tabProfile, profileTab)
        styleTab(binding.tabSettings, !profileTab)
    }

    private fun styleTab(tv: android.widget.TextView, selected: Boolean) {
        if (selected) {
            tv.setBackgroundColor(0xFFE6CF9E.toInt())
            tv.setTextColor(0xFF000000.toInt())
        } else {
            tv.setBackgroundColor(0xFF232E63.toInt())
            tv.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    // ---------- Edit Profile ----------
    private fun saveProfile() {
        val name = binding.etEditName.text.toString().trim()
        val driverNo = binding.etEditDriverId.text.toString().trim()
        if (name.isEmpty() || driverNo.isEmpty()) {
            Toast.makeText(this, "Name ও Driver No খালি রাখা যাবে না", Toast.LENGTH_SHORT).show()
            return
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

    // ---------- Settings: Calculation Rate ----------
    private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun refreshRateUi() {
        val custom = binding.rbRateCustom.isChecked
        val d = RateProvider.defaultFor(taxi)
        val saved = profile?.customRates ?: emptyMap()

        fun pick(suffix: String, def: Double): Double =
            if (custom) (saved["${taxi.name}_$suffix"] ?: def) else def

        binding.tvRateTaxi.text = "Rates for: ${taxi.displayName()}"
        binding.etRateNormal.setText(fmt(pick("normal", d.normalTripRate)))
        binding.etRateBooking.setText(fmt(pick("booking", d.bookingRate)))
        binding.etRateFuel.setText(fmt(pick("fuel", d.fuelPerKm)))
        binding.etRateAirport.setText(fmt(pick("airport", d.airportTripRate)))

        // Airport Taxi তে Bookings এর চার্জ নেই
        binding.rowRateBooking.visibility = if (taxi == TaxiType.AIRPORT_TAXI) View.GONE else View.VISIBLE

        for (et in listOf(binding.etRateNormal, binding.etRateBooking, binding.etRateFuel, binding.etRateAirport)) {
            et.isEnabled = custom
            et.alpha = if (custom) 1f else 0.6f
        }
        binding.tvRateNote.text = if (custom)
            "Your salary will be calculated with YOUR rates (not the company's default rates)."
        else
            "Default = company rates. These values cannot be edited."
        binding.btnSaveRates.text = if (custom) "Save My Rates" else "Use Default Rates"
    }

    private fun readRate(et: EditText): Double? {
        val v = et.text.toString().trim().toDoubleOrNull() ?: return null
        return if (v < 0 || v > 1000) null else v
    }

    private fun saveRates() {
        val custom = binding.rbRateCustom.isChecked
        val update = HashMap<String, Any>()

        if (!custom) {
            update["rateMode"] = "DEFAULT"
            writeRates(update, "Default rates are now used ✅")
            return
        }

        val normal = readRate(binding.etRateNormal)
        val fuel = readRate(binding.etRateFuel)
        val airport = readRate(binding.etRateAirport)
        val booking = if (taxi == TaxiType.AIRPORT_TAXI) 0.0 else readRate(binding.etRateBooking)
        if (normal == null || fuel == null || airport == null || booking == null) {
            Toast.makeText(this, "Please enter valid rates (numbers only)", Toast.LENGTH_SHORT).show()
            return
        }

        update["rateMode"] = "CUSTOM"
        update["customRates.${taxi.name}_normal"] = normal
        update["customRates.${taxi.name}_fuel"] = fuel
        update["customRates.${taxi.name}_airport"] = airport
        if (taxi != TaxiType.AIRPORT_TAXI) update["customRates.${taxi.name}_booking"] = booking

        AlertDialog.Builder(this)
            .setTitle("Use your own rates?")
            .setMessage("Salary for the whole current period will be calculated with your rates, not the company's default rates.\n\nYou can switch back to Default anytime.")
            .setPositiveButton("Yes, Save") { _, _ -> writeRates(update, "My rates saved ✅") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeRates(update: Map<String, Any>, okMessage: String) {
        lifecycleScope.launch {
            try {
                db.collection("users").document(key).update(update).await()
                val doc = db.collection("users").document(key).get().await()
                val p = doc.toObject(UserProfile::class.java)
                if (p != null) {
                    profile = p
                    RateProvider.applyProfile(p)
                }
                Toast.makeText(this@ProfileEditActivity, okMessage, Toast.LENGTH_SHORT).show()
                refreshRateUi()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileEditActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
