package com.harunrashid.targetachieve

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.harunrashid.targetachieve.models.PeriodDetailActivity
import com.harunrashid.targetachieve.models.Report
import com.harunrashid.targetachieve.models.ShiftRecord
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor
import com.harunrashid.targetachieve.logic.NoticeHelper
import com.harunrashid.targetachieve.logic.UpdateChecker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerBinding: NavDrawerContentBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var currentProfile: UserProfile? = null
    private var currentSummary: PeriodSummary? = null
    private var currentPeriodStart: Calendar? = null
    private var currentPeriodEnd: Calendar? = null
    private var picCameraUri: Uri? = null

    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
    private val adminEmail = "admininfo.bd@gmail.com"

    // ---------- Profile picture launchers ----------
    private val picCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) picCameraUri?.let { saveProfilePicture(it) }
    }
    private val picGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveProfilePicture(uri)
    }
    private val picCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchPicCamera() else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (profileKey() == null) {
            navigateToLogin()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""
        drawerBinding = binding.navDrawerContent

        setupDrawer()
        setupDrawerActions()
        setupLanguageSpinner()

        binding.btnAddShift.setOnClickListener { startActivity(Intent(this, AddShiftActivity::class.java)) }
        binding.btnViewReports.setOnClickListener { startActivity(Intent(this, ReportsActivity::class.java)) }
        binding.btnFinalizePeriod.setOnClickListener { finalizeCurrentPeriod() }
        binding.btnCurrentMonthMenu.setOnClickListener { showCurrentMonthPopup(it) }
    }

    private var biometricAsked = false

    override fun onResume() {
        super.onResume()
        if (profileKey() == null || !::drawerBinding.isInitialized) return

        val biometricEnabled = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getBoolean("biometric_enabled", false)

        if (biometricEnabled && !biometricAsked) {
            biometricAsked = true
            showBiometricPrompt()
        } else {
            loadProfileAndDashboard()
        }
    }

    // ---------- Drawer ----------
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
        drawerBinding.btnShareApp.setOnClickListener { shareAppAsApk() }
        drawerBinding.btnDriverGuide.setOnClickListener { showDriverGuide() }

        drawerBinding.btnWhatsAppSupport.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/971552335930")))
        }
        drawerBinding.btnEmailSupport.setOnClickListener {
            startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:$adminEmail") })
        }

        drawerBinding.btnEditProfile.setOnClickListener {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }
        drawerBinding.btnUploadProfilePic.setOnClickListener { chooseProfilePictureSource() }
        drawerBinding.btnAdminPanel.setOnClickListener { openAdminPanel() }
        drawerBinding.btnLogout.setOnClickListener { performLogout() }

        drawerBinding.rgDrawerTaxiType.setOnCheckedChangeListener { group, checkedId ->
            if (group.isPressed || drawerBinding.rbDrawerAirportTaxi.isPressed || drawerBinding.rbDrawerShiftTaxi.isPressed) {
                val t = if (checkedId == R.id.rbDrawerAirportTaxi) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI
                updateTaxiType(t)
            }
        }

        drawerBinding.switchBiometric.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (isChecked) {
                val ok = BiometricManager.from(this).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                if (ok == BiometricManager.BIOMETRIC_SUCCESS) {
                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("biometric_enabled", true).apply()
                    Toast.makeText(this, "Biometric Lock Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    drawerBinding.switchBiometric.isChecked = false
                    Toast.makeText(this, "Biometric not available on this device", Toast.LENGTH_LONG).show()
                }
            } else {
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("biometric_enabled", false).apply()
                Toast.makeText(this, "Biometric Lock Disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Driver Guide (follows app language) ----------
    private fun showDriverGuide() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.driver_guide_title))
            .setMessage(getString(R.string.driver_guide_text))
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Admin panel: admins/{mobileKey} OR admin email ----------
    private fun openAdminPanel() {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val isAdminDoc = db.collection("admins").document(key).get().await().exists()
                if (isAdminDoc) {
                    startActivity(Intent(this@MainActivity, AdminActivity::class.java))
                } else {
                    askAdminEmail()
                }
            } catch (e: Exception) {
                askAdminEmail()
            }
        }
    }

    private fun askAdminEmail() {
        val input = EditText(this).apply {
            hint = "Admin email"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                if (input.text.toString().trim().equals(adminEmail, ignoreCase = true)) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    Toast.makeText(this, "Access Denied: Only Admin can access this panel!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Logout ----------
    private fun performLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out? Your saved data stays safe. Log in again with Name, Driver No and Mobile.")
            .setPositiveButton("Yes") { _, _ ->
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                    .remove("profile_key")
                    .remove("biometric_enabled")
                    .apply()
                auth.signOut()
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                navigateToLogin()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun navigateToLogin() {
        val i = Intent(this, LoginActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }

    // ---------- Language ----------
    private fun setupLanguageSpinner() {
        val languages = listOf("English", "हिंदी (Hindi)", "اردو (Urdu)", "العربية (Arabic)", "বাংলা (Bangla)")
        val codes = listOf("en", "hi", "ur", "ar", "bn")

        drawerBinding.spinnerLanguage.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)

        val cur = AppCompatDelegate.getApplicationLocales()
        val activeCode = if (!cur.isEmpty) cur[0]?.language ?: "en" else "en"
        drawerBinding.spinnerLanguage.setSelection(codes.indexOf(activeCode).let { if (it == -1) 0 else it }, false)

        drawerBinding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val sel = codes[position]
                val now = AppCompatDelegate.getApplicationLocales()
                val nowCode = if (!now.isEmpty) now[0]?.language else "en"
                if (sel != nowCode) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(sel))
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------- Profile / dashboard ----------
    private fun loadProfileAndDashboard() {
        val key = profileKey() ?: return

        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()

                val doc = db.collection("users").document(key).get().await()
                val profile = doc.toObject(UserProfile::class.java)
                if (profile == null) {
                    Toast.makeText(this@MainActivity, "Account not found. Please log in again.", Toast.LENGTH_LONG).show()
                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit().remove("profile_key").apply()
                    navigateToLogin()
                    return@launch
                }
                currentProfile = profile

                drawerBinding.tvDrawerName.text = profile.name
                drawerBinding.tvDrawerMobile.text = profile.mobile
                drawerBinding.tvDrawerDriverNo.text = "Driver No: ${profile.driverId}"
                showProfilePicture(profile.profilePicBase64)

                if (profile.taxiTypeEnum() == TaxiType.AIRPORT_TAXI) drawerBinding.rbDrawerAirportTaxi.isChecked = true
                else drawerBinding.rbDrawerShiftTaxi.isChecked = true

                drawerBinding.switchBiometric.isChecked =
                    getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("biometric_enabled", false)

                val now = System.currentTimeMillis()
                val trialActive = profile.trialExpiryMillis > now
                val subActive = profile.subscriptionExpiryMillis > now

                if (!trialActive && !subActive) {
                    startActivity(Intent(this@MainActivity, PaywallActivity::class.java))
                    finish()
                    return@launch
                }
                if (trialActive && !subActive) {
                    val daysLeft = (profile.trialExpiryMillis - now) / (24L * 60 * 60 * 1000)
                    if (daysLeft in 0..5) {
                        Toast.makeText(this@MainActivity, "Your free trial will expire in $daysLeft days", Toast.LENGTH_LONG).show()
                    }
                }
                loadCurrentPeriodShifts(key, profile.taxiTypeEnum())
                if (!UpdateChecker.check(this@MainActivity)) {
                    NoticeHelper.checkAndShow(this@MainActivity)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun updateTaxiType(t: TaxiType) {
        val key = profileKey() ?: return
        db.collection("users").document(key).update("taxiType", t.name)
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

    private fun loadCurrentPeriodShifts(key: String, taxiType: TaxiType) {
        val (start, end) = currentPeriodRange()
        currentPeriodStart = start
        currentPeriodEnd = end
        binding.tvPeriodRange.text = "${sdf.format(start.time)}  →  ${sdf.format(end.time)}"

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users").document(key).collection("shifts").get().await()
                val records = snapshot.toObjects(ShiftRecord::class.java)

                val inRange = records.filter { r ->
                    try {
                        val d = sdf.parse(r.dateString)
                        d != null && !d.before(start.time) && !d.after(end.time)
                    } catch (e: Exception) { false }
                }

                val summary = SalaryCalculator.calculatePeriod(inRange, taxiType)
                currentSummary = summary

                binding.tvTotalCleanMoney.text = "%.0f AED".format(summary.totalCleanMoney)
                binding.tvTotalSalary.text = "%.0f AED".format(summary.finalSalary)
                binding.tvCurrentTarget.text =
                    "➤ Achieved Target %%: %.1f%% (%.0f AED)".format(summary.currentTargetPercent, summary.finalSalary)

                binding.tvNextTarget.text =
                    if (summary.nextTargetPercent != null && summary.aedNeededPerDayForNextTarget != null) {
                        "➤ For the next target (%.1f%%) earn %.0f AED Clean Money per day (next %d days)".format(
                            summary.nextTargetPercent,
                            summary.aedNeededPerDayForNextTarget,
                            summary.remainingWorkDays
                        )
                    } else "You're at the highest target level!"

                // Daily breakdown: newest 3 only (sorted by real date)
                binding.dailyContainer.removeAllViews()
                if (summary.dayResults.isEmpty()) {
                    binding.dailyContainer.addView(TextView(this@MainActivity).apply {
                        text = "No shifts added yet in this period"
                        textSize = 14f
                        setTextColor(0xFF888888.toInt())
                        setPadding(0, 12, 0, 12)
                    })
                } else {
                    val newest3 = summary.dayResults
                        .sortedByDescending { sdf.parse(it.record.dateString)?.time ?: 0L }
                        .take(3)

                    newest3.forEach { day ->
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(16, 14, 16, 14)
                            setBackgroundColor(0xFF232E63.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, 0, 8) }
                        }
                        row.addView(TextView(this@MainActivity).apply {
                            text = day.record.dateString
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this@MainActivity).apply {
                            text = "%.0f AED".format(day.cleanMoney)
                            textSize = 14f
                            setTextColor(0xFFE6CF9E.toInt())
                            gravity = android.view.Gravity.END
                        })
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

    // ---------- ⋮ menu → full month popup ----------
    private fun showCurrentMonthPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("View Current Month Details")
        popup.setOnMenuItemClickListener {
            openFullPeriodDetails()
            true
        }
        popup.show()
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
        startActivity(Intent(this, PeriodDetailActivity::class.java).apply {
            putExtra("periodStart", sdf.format(start.time))
            putExtra("periodEnd", sdf.format(end.time))
            putExtra("totalClean", summary.totalCleanMoney)
            putExtra("finalSalary", summary.finalSalary)
            putExtra("targetPercent", summary.currentTargetPercent)
        })
    }

    // ---------- Finalize ----------
    private fun finalizeCurrentPeriod() {
        val key = profileKey() ?: return
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
                db.collection("users").document(key).collection("reports").document(reportId).set(report).await()
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

    // ---------- Profile picture ----------
    private fun chooseProfilePictureSource() {
        AlertDialog.Builder(this)
            .setTitle("Profile Picture")
            .setItems(arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")) { _, which ->
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        launchPicCamera()
                    else picCameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    picGalleryLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun launchPicCamera() {
        try {
            val f = File(cacheDir, "profile_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
            picCameraUri = uri
            picCameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveProfilePicture(uri: Uri) {
        val key = profileKey() ?: return
        try {
            val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
            val side = minOf(original.width, original.height)
            val square = Bitmap.createBitmap(original, (original.width - side) / 2, (original.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, 200, 200, true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            drawerBinding.ivProfilePic.setImageBitmap(small)
            lifecycleScope.launch {
                try {
                    db.collection("users").document(key).update("profilePicBase64", b64).await()
                    Toast.makeText(this@MainActivity, "Profile picture saved ✅", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProfilePicture(b64: String) {
        if (b64.isEmpty()) return
        try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            drawerBinding.ivProfilePic.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } catch (_: Exception) { }
    }

    // ---------- Share app (always the currently installed APK) ----------
    private fun shareAppAsApk() {
        try {
            val original = File(applicationInfo.sourceDir)
            val dest = File(cacheDir, "TargetAchieve.apk")
            if (dest.exists()) dest.delete()
            original.copyTo(dest, overwrite = true)

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", dest)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "I'm using the \"TARGET ACHIEVE\" app. You should try it too to hit your targets!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share App via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Biometric ----------
    private fun showBiometricPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                loadProfileAndDashboard()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@MainActivity, "Fingerprint / Face not recognized", Toast.LENGTH_SHORT).show()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Target Achieve Lock")
            .setSubtitle("Verify your identity to open the app")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        BiometricPrompt(this, executor, callback).authenticate(info)
    }
}