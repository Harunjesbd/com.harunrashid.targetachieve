package com.harunrashid.targetachieve

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityMainBinding
import com.harunrashid.targetachieve.databinding.NavDrawerContentBinding
import com.harunrashid.targetachieve.logic.NotificationHelper
import com.harunrashid.targetachieve.logic.PeriodSummary
import com.harunrashid.targetachieve.logic.SalaryCalculator
import com.harunrashid.targetachieve.models.Report
import com.harunrashid.targetachieve.models.ShiftRecord
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerBinding: NavDrawerContentBinding

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var currentProfile: UserProfile? = null
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private var currentSummary: PeriodSummary? = null
    private var currentPeriodStart: Calendar? = null
    private var currentPeriodEnd: Calendar? = null

    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (auth.currentUser == null) {
            navigateToSignup()
            return
        }

        setSupportActionBar(binding.toolbar)
        drawerBinding = binding.navDrawerContent

        setupDrawer()
        setupDrawerActions()
        setupLanguageSpinner()

        binding.btnAddShift.setOnClickListener {
            startActivity(Intent(this, AddShiftActivity::class.java))
        }
        binding.btnViewReports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }
        binding.btnFinalizePeriod.setOnClickListener {
            finalizeCurrentPeriod()
        }

        // Full Period Details বাটন
        binding.btnViewFullPeriod.setOnClickListener {
            openFullPeriodDetails()
        }
    }

    private fun openFullPeriodDetails() {
        val summary = currentSummary
        val start = currentPeriodStart
        val end = currentPeriodEnd

        if (summary == null || start == null || end == null) {
            Toast.makeText(this, "No data yet", Toast.LENGTH_SHORT).show()
            return
        }

        PeriodDetailActivity.daysList = summary.dayResults

        val intent = Intent(this, PeriodDetailActivity::class.java).apply {
            putExtra("periodStart", sdf.format(start.time))
            putExtra("periodEnd", sdf.format(end.time))
            putExtra("totalClean", summary.totalCleanMoney)
            putExtra("finalSalary", summary.finalSalary)
            putExtra("targetPercent", summary.currentTargetPercent)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            val biometricEnabled = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getBoolean("biometric_enabled", false)

            if (biometricEnabled) {
                showBiometricPrompt()
            } else {
                loadProfileAndDashboard()
            }
        }
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            android.R.string.ok, android.R.string.cancel
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun setupDrawerActions() {
        drawerBinding.btnShareApp.setOnClickListener {
            shareAppAsApk()
        }

        drawerBinding.btnWhatsAppSupport.setOnClickListener {
            val uri = Uri.parse("https://wa.me/971552335930")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        drawerBinding.btnEmailSupport.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:admininfo.bd@gmail.com")
            }
            startActivity(intent)
        }

        drawerBinding.btnAdminPanel.setOnClickListener {
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Toast.makeText(this, "User not logged in!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val userEmail = currentUser.email ?: ""
            val profileName = currentProfile?.name ?: ""
            val adminEmail = "admininfo.bd@gmail.com"

            if (userEmail.equals(adminEmail, ignoreCase = true) || profileName.equals(adminEmail, ignoreCase = true)) {
                startActivity(Intent(this, AdminActivity::class.java))
            } else {
                val uid = currentUser.uid
                db.collection("admins").document(uid).get()
                    .addOnSuccessListener { document ->
                        if (document.exists()) {
                            startActivity(Intent(this, AdminActivity::class.java))
                        } else {
                            Toast.makeText(this, "Access Denied: Only Admin can access this panel!", Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Access Denied: Only Admin can access this panel!", Toast.LENGTH_LONG).show()
                    }
            }
        }

        drawerBinding.root.findViewById<View?>(R.id.btnLogout)?.setOnClickListener {
            performLogout()
        }

        drawerBinding.rgDrawerTaxiType.setOnCheckedChangeListener { _, checkedId ->
            if (drawerBinding.rgDrawerTaxiType.isPressed) {
                val newType = if (checkedId == R.id.rbDrawerAirportTaxi) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI
                updateTaxiType(newType)
            }
        }

        drawerBinding.switchBiometric.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                if (isChecked) {
                    val biometricManager = BiometricManager.from(this)
                    when (biometricManager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )) {
                        BiometricManager.BIOMETRIC_SUCCESS -> {
                            getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                                .putBoolean("biometric_enabled", true).apply()
                            Toast.makeText(this, "Biometric Lock Enabled", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            drawerBinding.switchBiometric.isChecked = false
                            Toast.makeText(this, "Biometric not available on this device", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                        .putBoolean("biometric_enabled", false).apply()
                    Toast.makeText(this, "Biometric Lock Disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Yes") { _, _ ->
                auth.signOut()
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                navigateToSignup()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun navigateToSignup() {
        val intent = Intent(this, SignupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupLanguageSpinner() {
        val languages = listOf("English", "हिंदी (Hindi)", "اردو (Urdu)", "العربية (Arabic)", "বাংলা (Bangla)")
        val languageCodes = listOf("en", "hi", "ur", "ar", "bn")

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        drawerBinding.spinnerLanguage.adapter = adapter

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val activeCode = if (!currentLocales.isEmpty) {
            currentLocales[0]?.language ?: "en"
        } else {
            "en"
        }

        val currentIndex = languageCodes.indexOf(activeCode).let { if (it == -1) 0 else it }
        drawerBinding.spinnerLanguage.setSelection(currentIndex, false)

        drawerBinding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCode = languageCodes[position]

                val nowActiveLocale = AppCompatDelegate.getApplicationLocales()
                val nowActiveCode = if (!nowActiveLocale.isEmpty) nowActiveLocale[0]?.language else "en"

                if (selectedCode != nowActiveCode) {
                    val appLocale = LocaleListCompat.forLanguageTags(selectedCode)
                    AppCompatDelegate.setApplicationLocales(appLocale)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadProfileAndDashboard() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(uid).get().await()
                val profile = doc.toObject(UserProfile::class.java) ?: return@launch
                currentProfile = profile

                drawerBinding.tvDrawerName.text = profile.name
                drawerBinding.tvDrawerMobile.text = profile.mobile

                if (profile.taxiTypeEnum() == TaxiType.AIRPORT_TAXI) {
                    drawerBinding.rbDrawerAirportTaxi.isChecked = true
                } else {
                    drawerBinding.rbDrawerShiftTaxi.isChecked = true
                }

                val biometricOn = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getBoolean("biometric_enabled", false)
                drawerBinding.switchBiometric.isChecked = biometricOn

                val now = System.currentTimeMillis()
                val trialActive = profile.trialExpiryMillis > now
                val subscriptionActive = profile.subscriptionExpiryMillis > now

                if (!trialActive && !subscriptionActive) {
                    startActivity(Intent(this@MainActivity, PaywallActivity::class.java))
                    finish()
                    return@launch
                }

                if (trialActive && !subscriptionActive) {
                    val daysLeft = (profile.trialExpiryMillis - now) / (24 * 60 * 60 * 1000)
                    if (daysLeft in 0..5) {
                        Toast.makeText(
                            this@MainActivity,
                            "Your free trial will expire in $daysLeft days",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                loadCurrentPeriodShifts(uid, profile.taxiTypeEnum())

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to load profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTaxiType(taxiType: TaxiType) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update("taxiType", taxiType.name)
    }

    private fun currentPeriodRange(): Pair<Calendar, Calendar> {
        val now = Calendar.getInstance()
        val start = Calendar.getInstance()
        val end = Calendar.getInstance()

        if (now.get(Calendar.DAY_OF_MONTH) >= 21) {
            start.set(Calendar.DAY_OF_MONTH, 21)
            end.add(Calendar.MONTH, 1)
            end.set(Calendar.DAY_OF_MONTH, 20)
        } else {
            start.add(Calendar.MONTH, -1)
            start.set(Calendar.DAY_OF_MONTH, 21)
            end.set(Calendar.DAY_OF_MONTH, 20)
        }
        return Pair(start, end)
    }

    private fun loadCurrentPeriodShifts(uid: String, taxiType: TaxiType) {
        val (start, end) = currentPeriodRange()
        currentPeriodStart = start
        currentPeriodEnd = end
        binding.tvPeriodRange.text = "${sdf.format(start.time)}  →  ${sdf.format(end.time)}"

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users").document(uid)
                    .collection("shifts")
                    .get()
                    .await()

                val records = snapshot.toObjects(ShiftRecord::class.java)

                val inRange = records.filter { record ->
                    try {
                        val d = sdf.parse(record.dateString)
                        d != null && !d.before(start.time) && !d.after(end.time)
                    } catch (e: Exception) {
                        false
                    }
                }

                val summary = SalaryCalculator.calculatePeriod(inRange, taxiType)
                currentSummary = summary

                binding.tvTotalCleanMoney.text = "%.0f AED".format(summary.totalCleanMoney)
                binding.tvTotalSalary.text = "%.0f AED".format(summary.finalSalary)
                binding.tvCurrentTarget.text =
                    "➤ Achieved Target %%: %.1f%% (%.0f AED)".format(summary.currentTargetPercent, summary.finalSalary)

                binding.tvNextTarget.text = if (summary.nextTargetPercent != null && summary.aedNeededPerDayForNextTarget != null) {
                    "➤ For the next target (%.1f%%) you need per day: +%.0f AED".format(
                        summary.nextTargetPercent, summary.aedNeededPerDayForNextTarget
                    )
                } else {
                    "You're at the highest target level!"
                }

                // ===== Daily Breakdown (Newest → Oldest) =====
                binding.dailyContainer.removeAllViews()

                if (summary.dayResults.isEmpty()) {
                    val emptyTv = TextView(this@MainActivity).apply {
                        text = "No shifts added yet in this period"
                        textSize = 14f
                        setTextColor(0xFF888888.toInt())
                        setPadding(0, 12, 0, 12)
                    }
                    binding.dailyContainer.addView(emptyTv)
                } else {
                    val sortedNewestFirst = summary.dayResults.sortedByDescending { it.record.dateString }

                    sortedNewestFirst.forEach { day ->
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(16, 14, 16, 14)
                            setBackgroundColor(0xFFF8F9FC.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 0, 0, 8)
                            }
                        }

                        val dateTv = TextView(this@MainActivity).apply {
                            text = day.record.dateString
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val moneyTv = TextView(this@MainActivity).apply {
                            text = "%.0f AED".format(day.cleanMoney)
                            textSize = 14f
                            setTextColor(0xFF1A237E.toInt())
                            gravity = android.view.Gravity.END
                        }

                        row.addView(dateTv)
                        row.addView(moneyTv)
                        binding.dailyContainer.addView(row)
                    }
                }

                val notice = NotificationHelper.checkForNotice(this@MainActivity, summary.currentTargetPercent)
                if (notice != null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Target Achieve")
                        .setMessage(notice)
                        .setPositiveButton("OK", null)
                        .show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to load data: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finalizeCurrentPeriod() {
        val uid = auth.currentUser?.uid ?: return
        val profile = currentProfile ?: return
        val summary = currentSummary
        val start = currentPeriodStart
        val end = currentPeriodEnd

        if (summary == null || start == null || end == null || summary.dayResults.isEmpty()) {
            Toast.makeText(this, "No shifts added yet to finalize", Toast.LENGTH_SHORT).show()
            return
        }

        val reportId = "${sdf.format(start.time)}_${sdf.format(end.time)}"

        val report = Report(
            reportId = reportId,
            periodStart = sdf.format(start.time),
            periodEnd = sdf.format(end.time),
            userName = profile.name,
            totalCleanMoney = summary.totalCleanMoney,
            baseSalary = summary.baseSalary,
            extraAdd = summary.extraAdd,
            fuelPenalty = summary.fuelPenalty,
            finalSalary = summary.finalSalary,
            achievedTargetPercent = summary.currentTargetPercent,
            nextTargetPercent = summary.nextTargetPercent ?: 0.0,
            aedNeededPerDayForNextTarget = summary.aedNeededPerDayForNextTarget ?: 0.0,
            generatedAtMillis = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("users").document(uid)
                    .collection("reports").document(reportId)
                    .set(report).await()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Saved ✅")
                    .setMessage("Report for this period has been saved. You can view it from 'View Saved Reports'.")
                    .setPositiveButton("OK", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to save report: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareAppAsApk() {
        try {
            val originalApk = File(applicationInfo.sourceDir)
            val destFile = File(cacheDir, "TargetAchieve.apk")

            if (destFile.exists()) destFile.delete()
            originalApk.copyTo(destFile, overwrite = true)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                destFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Install Target Achieve App")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share App APK via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share APK: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun showBiometricPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                loadProfileAndDashboard()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    finish()
                } else {
                    Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@MainActivity, "Fingerprint / Face not recognized", Toast.LENGTH_SHORT).show()
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Target Achieve Lock")
            .setSubtitle("Verify your identity to open the app")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}