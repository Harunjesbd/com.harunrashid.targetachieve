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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.harunrashid.targetachieve.databinding.ActivityMainBinding
import com.harunrashid.targetachieve.databinding.NavDrawerContentBinding
import com.harunrashid.targetachieve.logic.NotificationHelper
import com.harunrashid.targetachieve.logic.PeriodSummary
import com.harunrashid.targetachieve.logic.SalaryCalculator
import com.harunrashid.targetachieve.models.PeriodDetailActivity
import com.harunrashid.targetachieve.models.Report
import com.harunrashid.targetachieve.models.ReportDay
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
import com.harunrashid.targetachieve.logic.RateProvider
import com.harunrashid.targetachieve.logic.ServerTime
import com.harunrashid.targetachieve.logic.SlabDialog
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

        // এই ফোনে অ্যাকাউন্ট আছে: লগ-ইন স্ক্রিনের "Create Account" ঝলমল আর লাগবে না
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("account_ready", true).apply()

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
        // "Achieved Target" কার্ডে চাপলে Salary Slab দেখায়
        binding.tvCurrentTarget.setOnClickListener { SlabDialog.show(this, currentSummary?.currentTargetPercent) }
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
        drawerBinding.btnSalarySlab.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            SlabDialog.show(this, currentSummary?.currentTargetPercent)
        }

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
        // Admin Panel খোলা জায়গায় নয়: টুলবারের ⋮ মেনুর ভেতরে (শুধু অ্যাডমিনের ফোনে দেখায়)
        binding.btnToolbarMore.setOnClickListener { v ->
            val pm = android.widget.PopupMenu(this, v)
            pm.menu.add(0, 1, 0, "🔒  Admin Panel")
            pm.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) openAdminPanel()
                true
            }
            pm.show()
        }
        drawerBinding.btnLogout.setOnClickListener { performLogout() }

        drawerBinding.rgDrawerTaxiType.setOnCheckedChangeListener { group, checkedId ->
            if (revertingTaxi) return@setOnCheckedChangeListener
            if (group.isPressed || drawerBinding.rbDrawerAirportTaxi.isPressed || drawerBinding.rbDrawerShiftTaxi.isPressed) {
                val t = if (checkedId == R.id.rbDrawerAirportTaxi) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI
                if (currentProfile?.taxiTypeEnum() == t) return@setOnCheckedChangeListener
                confirmTaxiChange(t)
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

    // ---------- Admin panel: admins তালিকা + ইমেইল-পাসওয়ার্ড (Firebase Authentication) ----------
    private fun openAdminPanel() {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val isAdminDoc = db.collection("admins").document(key).get().await().exists()
                if (isAdminDoc) showAdminLogin()
                else Toast.makeText(this@MainActivity, "Access Denied", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Access Denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAdminLogin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etEmail = EditText(this).apply {
            hint = "Admin email"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPass = EditText(this).apply {
            hint = "Admin password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(etEmail)
        box.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                val email = etEmail.text.toString().trim()
                val pass = etPass.text.toString()
                if (email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        auth.signInWithEmailAndPassword(email, pass).await()
                        startActivity(Intent(this@MainActivity, AdminActivity::class.java))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Access Denied: wrong email or password", Toast.LENGTH_LONG).show()
                    }
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
    // প্রোফাইলের তথ্য মেনু/ড্রয়ারে বসানো
    private fun paintProfile(profile: UserProfile) {
        drawerBinding.tvDrawerName.text = profile.name
        drawerBinding.tvDrawerMobile.text = profile.mobile
        drawerBinding.tvDrawerDriverNo.text = "Driver No: ${profile.driverId}"
        showProfilePicture(profile.profilePicBase64)

        if (profile.taxiTypeEnum() == TaxiType.AIRPORT_TAXI) drawerBinding.rbDrawerAirportTaxi.isChecked = true
        else drawerBinding.rbDrawerShiftTaxi.isChecked = true

        drawerBinding.switchBiometric.isChecked =
            getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("biometric_enabled", false)
    }

    private fun loadProfileAndDashboard() {
        val key = profileKey() ?: return

        lifecycleScope.launch {
            // ধাপ ০ (তাৎক্ষণিক): ফোনে জমানো কপি থেকে সঙ্গে সঙ্গে স্ক্রিন ভরা, ইন্টারনেটের অপেক্ষা ছাড়া
            try {
                RateProvider.loadCached(this@MainActivity)
                val cached = db.collection("users").document(key).get(Source.CACHE).await()
                    .toObject(UserProfile::class.java)
                if (cached != null) {
                    paintProfile(cached)
                    loadCurrentPeriodShifts(key, cached.taxiTypeEnum(), Source.CACHE)
                }
            } catch (_: Exception) { }

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
                RateProvider.applyProfile(profile)
                if (!profile.migrated) migrateOwnership(key, profile)
                paintProfile(profile)

                // এক সঙ্গে চারটা কাজ (একটার পর একটা নয়): Admin কিনা, সার্ভারের সময়, ফ্রি-তারিখ, কোম্পানির রেট
                val (isAdminUser, now, freeUntil) = coroutineScope {
                    val adminD = async {
                        try { db.collection("admins").document(key).get().await().exists() }
                        catch (e: Exception) { false }
                    }
                    val timeD = async { ServerTime.now(this@MainActivity) }
                    val freeD = async {
                        try {
                            db.collection("config").document("app").get().await()
                                .getTimestamp("freeUntil")?.toDate()?.time ?: 0L
                        } catch (e: Exception) { 0L }
                    }
                    val rateD = async { RateProvider.refreshRemote(this@MainActivity) }
                    rateD.await()
                    Triple(adminD.await(), timeD.await(), freeD.await())
                }
                binding.btnToolbarMore.visibility = if (isAdminUser) View.VISIBLE else View.GONE

                markActive(key, now)
                val trialActive = profile.trialExpiryMillis > now
                val subActive = profile.subscriptionExpiryMillis > now
                val freeActive = freeUntil > now

                if (!trialActive && !subActive && !freeActive) {
                    startActivity(Intent(this@MainActivity, PaywallActivity::class.java))
                    finish()
                    return@launch
                }
                if (trialActive && !subActive && !freeActive) {
                    val daysLeft = (profile.trialExpiryMillis - now) / (24L * 60 * 60 * 1000)
                    if (daysLeft in 0..5) {
                        Toast.makeText(this@MainActivity, "Your free trial will expire in $daysLeft days", Toast.LENGTH_LONG).show()
                    }
                }
                if (profile.email.isBlank()) askForEmail(key)
                loadCurrentPeriodShifts(key, profile.taxiTypeEnum())

                // আপডেট/নোটিশ চেক আলাদা কাজ হিসেবে: স্ক্রিন দেখানো আটকায় না
                lifecycleScope.launch {
                    try {
                        if (!UpdateChecker.check(this@MainActivity)) {
                            NoticeHelper.checkAndShow(this@MainActivity)
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // অ্যাডমিন প্যানেলের Active/Inactive দেখার জন্য: দিনে একবার "শেষ খোলার সময়" লেখা (খুবই কম খরচ)
    private fun markActive(key: String, now: Long) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val today = now / (24L * 60 * 60 * 1000)
        val prefKey = "last_active_day_$key"
        if (prefs.getLong(prefKey, -1L) == today) return
        db.collection("users").document(key).update("lastActiveMillis", now)
            .addOnSuccessListener { prefs.edit().putLong(prefKey, today).apply() }
    }

    // Email না থাকলে দিনে একবার চাওয়া হয় (পরে Play Store ও অ্যাকাউন্ট উদ্ধারের জন্য)
    private fun askForEmail(key: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val today = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        if (prefs.getLong("email_prompt_day", -1L) == today) return
        prefs.edit().putLong("email_prompt_day", today).apply()

        val input = EditText(this).apply {
            hint = "name@gmail.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Add your Email")
            .setMessage("Please add your email address. It helps to keep your account safe.")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Later", null)
            .create()
            .also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val e = input.text.toString().trim().lowercase()
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            try {
                                db.collection("users").document(key).update("email", e).await()
                                Toast.makeText(this@MainActivity, "Email saved ✅", Toast.LENGTH_SHORT).show()
                                d.dismiss()
                            } catch (ex: Exception) {
                                Toast.makeText(this@MainActivity, "Failed: ${ex.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                d.show()
            }
    }

    private var revertingTaxi = false

    // Taxi type বদলানোর আগে নিশ্চিতকরণ: পুরনো দিনের হিসাব বদলাবে না
    private fun confirmTaxiChange(t: TaxiType) {
        AlertDialog.Builder(this)
            .setTitle("Change Taxi Type")
            .setMessage(
                "From now on, new shifts will be calculated as ${t.displayName()}.\n\n" +
                        "Days already saved keep their old taxi type and rates, so earlier days will NOT change.\n\n" +
                        "Do you want to continue?"
            )
            .setCancelable(false)
            .setPositiveButton("Change") { _, _ -> updateTaxiType(t) }
            .setNegativeButton("Cancel") { _, _ -> revertTaxiRadio() }
            .show()
    }

    private fun revertTaxiRadio() {
        revertingTaxi = true
        if (currentProfile?.taxiTypeEnum() == TaxiType.AIRPORT_TAXI) drawerBinding.rbDrawerAirportTaxi.isChecked = true
        else drawerBinding.rbDrawerShiftTaxi.isChecked = true
        revertingTaxi = false
    }

    private fun updateTaxiType(t: TaxiType) {
        val key = profileKey() ?: return
        db.collection("users").document(key).update("taxiType", t.name)
            .addOnSuccessListener {
                Toast.makeText(this, "Taxi type changed to ${t.displayName()}", Toast.LENGTH_LONG).show()
                loadProfileAndDashboard()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not change Taxi type", Toast.LENGTH_LONG).show()
                revertTaxiRadio()
            }
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
        // পিরিয়ডের শুরু ২১ তারিখ ০০:০০ এবং শেষ ২০ তারিখ ২৩:৫৯ ধরা হয়, নইলে ২১ তারিখের শিফট বাদ পড়ে যেত
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
        end.set(Calendar.HOUR_OF_DAY, 23); end.set(Calendar.MINUTE, 59)
        end.set(Calendar.SECOND, 59); end.set(Calendar.MILLISECOND, 999)
        return Pair(start, end)
    }

    private fun loadCurrentPeriodShifts(key: String, taxiType: TaxiType, source: Source = Source.DEFAULT) {
        val (start, end) = currentPeriodRange()
        currentPeriodStart = start
        currentPeriodEnd = end
        binding.tvPeriodRange.text = "${sdf.format(start.time)}  →  ${sdf.format(end.time)}" +
                (if (RateProvider.isCustom()) "\n⚙ Custom rates active" else "")

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users").document(key).collection("shifts").get(source).await()
                // Taxi type লেখা নেই এমন দিনে এখনকার type একবার বসিয়ে দেওয়া হয় (পরে type বদলালেও এই দিন বদলাবে না)
                val records = snapshot.documents.mapNotNull { d ->
                    val r = d.toObject(ShiftRecord::class.java) ?: return@mapNotNull null
                    if (r.taxiType.isEmpty()) {
                        d.reference.update("taxiType", taxiType.name)
                        r.copy(taxiType = taxiType.name)
                    } else r
                }

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
                binding.tvTotalDeposit.text = "%.0f AED".format(summary.totalDeposit)
                binding.tvCleanSub.text = "${summary.workingDays} days worked"
                binding.tvSalarySub.text = "Tips included: %.0f".format(summary.extraAdd)
                binding.tvAvgClean.text = "%.0f AED".format(summary.averageClean)
                binding.tvAvgSub.text =
                    if (summary.workingDays > 0) "%.0f ÷ %d days".format(summary.totalCleanMoney, summary.workingDays)
                    else "per working day"
                binding.tvSlabPct.text = "%.1f%%".format(summary.currentTargetPercent)
                val nextMin = com.harunrashid.targetachieve.logic.CommissionSlab.nextSlab(summary.averageClean)?.second
                binding.tvSlabNext.text =
                    if (summary.nextTargetPercent != null && nextMin != null)
                        "Next: %.1f%% — needs %.0f avg/day".format(summary.nextTargetPercent, nextMin)
                    else "Highest slab"
                val topSlab = com.harunrashid.targetachieve.logic.CommissionSlab.TABLE.last().percent
                binding.progressTarget.progress =
                    ((summary.currentTargetPercent / topSlab) * 100).toInt().coerceIn(0, 100)
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
    // ⋮ চাপলেই সরাসরি "Check & Edit" স্ক্রিন খোলে (দিন ধরে দেখা, ✏️ সংশোধন, 🗑️ মোছা)
    private fun showCurrentMonthPopup(anchor: View) {
        openFullPeriodDetails()
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

    // এই ফোনের পরিচয় (uid) অ্যাকাউন্টের সঙ্গে বাঁধা: প্রথমবার নতুন অ্যাপ চালালে নিঃশব্দে একবার হয়
    private fun migrateOwnership(key: String, p: UserProfile) {
        lifecycleScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val ref = db.collection("users").document(key)
                ref.update(
                    mapOf(
                        "uid" to uid,
                        "migrated" to true,
                        "claimName" to p.name.trim().lowercase(),
                        "claimDriverId" to p.driverId.trim().lowercase()
                    )
                ).await()
                ref.update(
                    mapOf(
                        "claimName" to FieldValue.delete(),
                        "claimDriverId" to FieldValue.delete()
                    )
                ).await()
            } catch (_: Exception) { }
        }
    }

    // ---------- Finalize ----------
    // ধাপ ১: শর্ত দেখে কোম্পানির জরিমানা (Penalty) জিজ্ঞেস করা; ধাপ ২: saveReport()
    private fun finalizeCurrentPeriod() {
        val key = profileKey() ?: return
        val summary = currentSummary
        val start = currentPeriodStart
        val end = currentPeriodEnd
        if (currentProfile == null || summary == null || start == null || end == null || summary.dayResults.isEmpty()) {
            Toast.makeText(this, "No shifts added yet to finalize", Toast.LENGTH_SHORT).show()
            return
        }
        val reportId = "${sdf.format(start.time)}_${sdf.format(end.time)}"
        lifecycleScope.launch {
            // আগে একবার সেভ করা থাকলে তার জরিমানা আগে থেকে বসানো
            var prevPenalty = 0.0
            var prevNote = ""
            try {
                val d = db.collection("users").document(key).collection("reports").document(reportId).get().await()
                prevPenalty = d.getDouble("penalty") ?: 0.0
                prevNote = d.getString("penaltyNote") ?: ""
            } catch (_: Exception) { }
            showPenaltyDialog(summary.finalSalary, prevPenalty, prevNote)
        }
    }

    private fun showPenaltyDialog(salaryBefore: Double, prevPenalty: Double, prevNote: String) {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "Salary before deduction: %.0f AED".format(Locale.ENGLISH, salaryBefore)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(10))
        })
        box.addView(TextView(this).apply { text = "Company Penalty (AED) — leave 0 if none"; textSize = 12f })
        val etPenalty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (prevPenalty > 0) "%.0f".format(Locale.ENGLISH, prevPenalty) else "0")
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { v, f -> if (f) (v as EditText).post { v.selectAll() } }
            setOnClickListener { (it as EditText).selectAll() }
        }
        box.addView(etPenalty)
        box.addView(TextView(this).apply { text = "Reason (optional)"; textSize = 12f; setPadding(0, dp(10), 0, 0) })
        val etNote = EditText(this).apply {
            hint = "e.g. accident / fine"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prevNote)
        }
        box.addView(etNote)
        val tvNet = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        box.addView(tvNet)

        fun penaltyValue(): Double = (etPenalty.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        fun refresh() {
            val net = (salaryBefore - penaltyValue()).coerceAtLeast(0.0)
            tvNet.text = "Final salary after deduction: %.0f AED".format(Locale.ENGLISH, net)
        }
        refresh()
        etPenalty.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
        })

        AlertDialog.Builder(this)
            .setTitle("Finalize & Save")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save Report") { _, _ -> saveReport(penaltyValue(), etNote.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveReport(penalty: Double, penaltyNote: String) {
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
            finalSalary = (summary.finalSalary - penalty).coerceAtLeast(0.0),
            penalty = penalty,
            penaltyNote = penaltyNote,
            achievedTargetPercent = summary.currentTargetPercent,
            nextTargetPercent = summary.nextTargetPercent ?: 0.0,
            aedNeededPerDayForNextTarget = summary.aedNeededPerDayForNextTarget ?: 0.0,
            generatedAtMillis = System.currentTimeMillis(),
            workingDays = summary.workingDays,
            averageClean = summary.averageClean,
            shiftTrips = summary.dayResults.sumOf { SalaryCalculator.totalTripsOf(it.record) },
            bookings = summary.dayResults.sumOf { it.record.bookings },
            airportTrips = summary.dayResults.sumOf { it.record.airportTrips },
            totalDeposit = summary.totalDeposit,
            totalKmHired = summary.dayResults.sumOf { it.record.kmHired },
            normalTrips = summary.dayResults.sumOf { SalaryCalculator.normalTripsOf(it.record) },
            days = summary.dayResults.map {
                ReportDay(
                    date = it.record.dateString,
                    clean = it.cleanMoney,
                    tips = it.record.tips,
                    avg = it.runningAverage,
                    deposit = it.deposit
                )
            }
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