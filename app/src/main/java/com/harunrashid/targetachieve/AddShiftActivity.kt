package com.harunrashid.targetachieve

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.animation.ObjectAnimator
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.harunrashid.targetachieve.databinding.ActivityAddShiftBinding
import com.harunrashid.targetachieve.logic.AppRules
import com.harunrashid.targetachieve.logic.MlKitOcrHelper
import com.harunrashid.targetachieve.logic.RateProvider
import com.harunrashid.targetachieve.logic.ShiftTextParser
import com.harunrashid.targetachieve.models.ShiftRecord
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddShiftActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddShiftBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
    private var selectedDate: Calendar = Calendar.getInstance()
    private var currentBitmap: Bitmap? = null
    private var cameraPhotoUri: Uri? = null
    private var isAirport = false   // Airport Taxi হলে: Bookings = 0 (লক), Shift Trips ঘর = সাধারণ ৩ AED ট্রিপ, Airport Trips ঘর = ২০ AED ট্রিপ
    private var alertView: TextView? = null
    private var blinkAnim: ObjectAnimator? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = cameraPhotoUri
            if (uri != null) {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap != null) {
                    onImageReady(bitmap)
                } else {
                    Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap != null) {
                onImageReady(bitmap)
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddShiftBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideUnusedFields()
        updateDateLabel()

        binding.tvDate.setOnClickListener { showDatePicker() }

        binding.btnCapture.setOnClickListener {
            showPhotoGuideline {
                checkCameraPermissionAndLaunch()
            }
        }

        binding.btnGallery.setOnClickListener {
            showPhotoGuideline {
                galleryLauncher.launch("image/*")
            }
        }

        binding.btnSaveShift.setOnClickListener { saveShift() }

        loadTaxiType()
    }

    // হিসাবে লাগে না এমন ঘর লুকানো: Driver No, Total Sharjah, KM Vacant
    // (রসিদ থেকে মান পড়া ও সেভ হতেই থাকে, শুধু স্ক্রিনে দেখা যায় না)
    private fun hideUnusedFields() {
        listOf<View>(binding.etPlateNo, binding.etTotalSharjah, binding.etKmVacant).forEach {
            (it.parent as? View)?.visibility = View.GONE
        }
    }

    private fun loadTaxiType() {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(key).get().await()
                val profile = doc.toObject(UserProfile::class.java)
                isAirport = profile?.taxiTypeEnum() == TaxiType.AIRPORT_TAXI
                if (profile != null) RateProvider.applyProfile(profile)
                applyTaxiLock()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun labelOf(v: View): TextView? = (v.parent as? ViewGroup)?.getChildAt(0) as? TextView

    private fun applyTaxiLock() {
        val shiftLabel = labelOf(binding.etShiftTrips)
        val airportLabel = labelOf(binding.etAirportTrips)
        if (isAirport) {
            binding.etBookings.setText("0")
            binding.etBookings.isEnabled = false
            shiftLabel?.text = "Normal Trips (3 AED)"
            airportLabel?.text = "Airport Trips (20 AED)"
        } else {
            binding.etBookings.isEnabled = true
            shiftLabel?.text = "Shift Trips"
            airportLabel?.text = "Airport Trips"
        }
    }

    // Airport Taxi: "Data loaded..." লেখার ওপরে লাফানো (blink) সংকেত
    private fun showTripAlert() {
        if (alertView == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.BLACK)
                setBackgroundColor(0xFFFFC107.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(24, 20, 24, 20)
            }
            val parent = binding.tvOcrStatus.parent as ViewGroup
            parent.addView(tv, parent.indexOfChild(binding.tvOcrStatus))
            alertView = tv
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { stopTripAlert() }
            }
            binding.etShiftTrips.addTextChangedListener(watcher)
            binding.etAirportTrips.addTextChangedListener(watcher)
            binding.etBookings.addTextChangedListener(watcher)
        }
        alertView?.apply {
            visibility = View.VISIBLE
            setBackgroundColor(0xFFFFC107.toInt())
            text = if (isAirport) {
                "⚠ AIRPORT TAXI: Please correct the trips!\n" +
                        "• Shift Trips box = NORMAL trips only (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).normalTripRate)} AED each)\n" +
                        "• Airport Trips box = AIRPORT trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).airportTripRate)} AED each)"
            } else {
                "⚠ SHIFT TAXI: Please correct the trips!\n" +
                        "• Airport Trips box = AIRPORT trips (${rate(RateProvider.activeFor(TaxiType.SHIFT_TAXI).airportTripRate)} AED each)"
            }
        }
        blinkAnim?.cancel()
        blinkAnim = ObjectAnimator.ofFloat(alertView, "alpha", 1f, 0.25f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    // 15.0 -> "15", 6.5 -> "6.5"
    private fun rate(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun stopTripAlert() {
        val tv = alertView ?: return
        if (blinkAnim == null) return
        blinkAnim?.cancel()
        blinkAnim = null
        tv.alpha = 1f
        tv.setBackgroundColor(0xFF2E7D32.toInt())
        tv.setTextColor(Color.WHITE)
        tv.text = "✔ Trips updated. Please check again before saving."
    }

    private fun updateDateLabel() {
        binding.tvDate.text = "📅 " + sdf.format(selectedDate.time)
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate.set(year, month, day)
                updateDateLabel()
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun checkCameraPermissionAndLaunch() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPhotoGuideline(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Photo Tip")
            .setMessage("অনুগ্রহ করে রসিদ সোজা করে এবং উজ্জ্বল আলোতে তুলুন।\n\nPlease take the photo straight and under bright light.")
            .setPositiveButton("OK, Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchCamera() {
        try {
            val photoFile = File(cacheDir, "shift_${System.currentTimeMillis()}.jpg")
            val authority = "${packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, photoFile)
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun onImageReady(bitmap: Bitmap) {
        currentBitmap = bitmap
        binding.ivPreview.visibility = View.VISIBLE
        binding.ivPreview.setImageBitmap(bitmap)

        AlertDialog.Builder(this)
            .setTitle("Is the photo clear?")
            .setMessage("Make sure the photo is clear and complete. Press Continue to read data.")
            .setPositiveButton("Continue") { _, _ -> runOcr(bitmap) }
            .setNegativeButton("Retake") { _, _ ->
                binding.ivPreview.visibility = View.GONE
                binding.formContainer.visibility = View.GONE
            }
            .setCancelable(false)
            .show()
    }

    private fun runOcr(bitmap: Bitmap) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvOcrStatus.text = "Reading data from image..."

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // MlKitOcrHelper ব্যবহার করে সারিগুলো পজিশন অনুযায়ী পুনরায় জোড়া দেওয়া হচ্ছে
                val mlKitText = MlKitOcrHelper.reconstructRows(visionText)
                val dateString = sdf.format(selectedDate.time)

                // ডিবাগের জন্য শুধু Logcat এ থাকে, ব্যবহারকারীকে দেখানো হয় না
                Log.d("OCR_RAW", "===== START =====\n$mlKitText\n===== END =====")

                val parsed = ShiftTextParser.parse(mlKitText, dateString)
                fillForm(parsed)
                binding.progressBar.visibility = View.GONE
                binding.tvOcrStatus.text = "Data loaded - please check and edit if needed"
                showTripAlert()
                binding.formContainer.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.tvOcrStatus.text = "Could not read image - please enter manually"
                fillForm(ShiftRecord(dateString = sdf.format(selectedDate.time)))
                binding.formContainer.visibility = View.VISIBLE
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun fillForm(record: ShiftRecord) {
        binding.etPlateNo.setText(record.driverNo)
        binding.etShiftTotal.setText(record.shiftTotal.toString())
        binding.etTotalSharjah.setText(record.totalSharjah.toString())
        binding.etTotalTollways.setText(record.totalTollways.toString())
        binding.etShiftTrips.setText(record.shiftTrips.toString())
        // Airport Taxi: Bookings = 0, বুকিংয়ের সংখ্যা Airport Trips ঘরে বসে (ড্রাইভার পরে ঠিক করে দেবে)
        binding.etBookings.setText(if (isAirport) "0" else record.bookings.toString())
        binding.etAirportTrips.setText(if (isAirport) record.bookings.toString() else record.airportTrips.toString())
        binding.etKmHired.setText(record.kmHired.toString())
        binding.etKmVacant.setText(record.kmVacant.toString())
        binding.etPaidInCareem.setText(record.paidInCareem.toString())
        binding.etHalaPeak.setText(record.halaPeak.toString())
        binding.etTips.setText(record.tips.toString())
    }

    // সেভের আগে ড্রাইভারকে ট্রিপের সংখ্যা নিশ্চিত করতে হয় (Shift ও Airport দুই Taxi type-এই)
    private fun saveShift() {
        val shiftTrips = binding.etShiftTrips.text.toString().toIntOrNull() ?: 0
        val bookings = binding.etBookings.text.toString().toIntOrNull() ?: 0
        val airport = binding.etAirportTrips.text.toString().toIntOrNull() ?: 0

        val msg = if (isAirport) {
            "Normal trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).normalTripRate)} AED each): $shiftTrips\n" +
                    "Airport trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).airportTripRate)} AED each): $airport\n\n" +
                    "Are these correct?"
        } else {
            val normal = (shiftTrips - bookings).coerceAtLeast(0)
            "Normal trips (${rate(RateProvider.activeFor(TaxiType.SHIFT_TAXI).normalTripRate)} AED each): $normal\n" +
                    "Bookings (${rate(RateProvider.activeFor(TaxiType.SHIFT_TAXI).bookingRate)} AED each): $bookings\n" +
                    "Airport trips (${rate(RateProvider.activeFor(TaxiType.SHIFT_TAXI).airportTripRate)} AED each): $airport\n\n" +
                    "Are these correct?"
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm trips")
            .setMessage(msg)
            .setPositiveButton("Yes, Save") { _, _ -> performSave() }
            .setNegativeButton("Edit", null)
            .show()
    }

    private fun performSave() {
        val uid = profileKey()
        if (uid == null) {
            Toast.makeText(this, "Login problem, please try again", Toast.LENGTH_SHORT).show()
            return
        }

        val dateString = sdf.format(selectedDate.time)

        val enteredBookings = binding.etBookings.text.toString().toIntOrNull() ?: 0
        val enteredAirport = binding.etAirportTrips.text.toString().toIntOrNull() ?: 0
        val bookingsValue = if (isAirport) 0 else enteredBookings
        val airportValue = enteredAirport

        val record = ShiftRecord(
            dateString = dateString,
            driverNo = binding.etPlateNo.text.toString().trim(),
            shiftTotal = binding.etShiftTotal.text.toString().toDoubleOrNull() ?: 0.0,
            totalSharjah = binding.etTotalSharjah.text.toString().toDoubleOrNull() ?: 0.0,
            totalTollways = binding.etTotalTollways.text.toString().toDoubleOrNull() ?: 0.0,
            shiftTrips = binding.etShiftTrips.text.toString().toIntOrNull() ?: 0,
            bookings = bookingsValue,
            taxiType = (if (isAirport) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI).name,
            airportTrips = airportValue,
            kmHired = binding.etKmHired.text.toString().toDoubleOrNull() ?: 0.0,
            kmVacant = binding.etKmVacant.text.toString().toDoubleOrNull() ?: 0.0,
            paidInCareem = binding.etPaidInCareem.text.toString().toDoubleOrNull() ?: 0.0,
            halaPeak = binding.etHalaPeak.text.toString().toDoubleOrNull() ?: 0.0,
            tips = binding.etTips.text.toString().toDoubleOrNull() ?: 0.0,
            createdAtMillis = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("users").document(uid)
                    .collection("shifts").document(dateString)
                    .set(record).await()

                Toast.makeText(this@AddShiftActivity, "Saved successfully ✅", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddShiftActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}