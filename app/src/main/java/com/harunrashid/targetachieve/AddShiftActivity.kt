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
    private var lastOcrText: String = ""   // ডিবাগ: "Data loaded" লেখায় অনেকক্ষণ চেপে ধরলে দেখা যায়
    private var tripsMismatch = false       // রসিদে Bookings > Shift Trips পড়া হলে true (OCR ভুল সম্ভব)

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
            val raw = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: return null
            // ক্যামেরা ছবির ঘোরানো তথ্য (EXIF) ফোন যেভাবে ধরা ছিল সেভাবে সোজা করা
            val orient = try {
                contentResolver.openInputStream(uri)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { android.media.ExifInterface.ORIENTATION_NORMAL }
            val deg = when (orient) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) raw else rotateBitmap(raw, deg)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // রসিদের চেনা শব্দ কতগুলো পড়া গেল (ছবি সোজা কিনা বোঝার জন্য)
    private fun ocrScore(text: String): Int {
        val t = text.lowercase().replace(Regex("\\s+"), "")
        return listOf("shift", "total", "trips", "book", "toll", "hired", "careem", "credit", "cash", "summary")
            .count { t.contains(it) }
    }

    // ছবি আড়াআড়ি (landscape) হলে ০°, ৯০°, ২৭০° ঘুরিয়ে OCR চালিয়ে সবচেয়ে ভালো পড়া হয় যেটাতে সেটা নেওয়া হয়
    private fun pickUpright(
        bmp: Bitmap,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        done: (Bitmap) -> Unit
    ) {
        if (bmp.width <= bmp.height) { done(bmp); return }
        val rots = listOf(0f, 90f, 270f)
        val scores = IntArray(rots.size) { -1 }
        var finished = 0
        rots.forEachIndexed { idx, r ->
            val b = rotateBitmap(bmp, r)
            recognizer.process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener { vt -> scores[idx] = ocrScore(MlKitOcrHelper.reconstructRows(vt)) }
                .addOnCompleteListener {
                    finished++
                    if (finished == rots.size) {
                        var best = 0
                        for (i in rots.indices) if (scores[i] > scores[best]) best = i
                        done(rotateBitmap(bmp, rots[best]))
                    }
                }
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

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                        "• Shift Trips box = NORMAL trips only (receipt Shift Trips − Bookings) (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).normalTripRate)} AED each)\n" +
                        "• Airport Trips box = AIRPORT trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).airportTripRate)} AED each)"
            } else {
                (if (tripsMismatch) "❗ Check Shift Trips / Bookings on the receipt: Shift Trips is 0 or smaller than Bookings + Airport. Rule: Shift Trips = Bookings + Airport + Normal. Normal trips cannot be minus (counted as 0).\n" else "") +
                "⚠ SHIFT TAXI: Please check the trips!\n" +
                        "• Shift Trips & Bookings boxes = numbers printed on the receipt\n" +
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
            .setMessage("মোবাইল সোজা (Portrait) ধরে, রসিদ সোজা করে, উজ্জ্বল আলোতে ছবি তুলুন। মোবাইল আড়াআড়ি (Landscape) ধরবেন না।\n\nHold the phone upright (Portrait), keep the receipt straight, bright light.")
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

    private fun runOcr(original: Bitmap) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvOcrStatus.text = "Reading data from image..."

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        pickUpright(original, recognizer) { upright ->
            currentBitmap = upright
            binding.ivPreview.setImageBitmap(upright)
            startOcr(upright, recognizer)
        }
    }

    private fun startOcr(bitmap: Bitmap, recognizer: com.google.mlkit.vision.text.TextRecognizer) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // MlKitOcrHelper ব্যবহার করে সারিগুলো পজিশন অনুযায়ী পুনরায় জোড়া দেওয়া হচ্ছে
                val mlKitText = MlKitOcrHelper.reconstructRows(visionText)
                val dateString = sdf.format(selectedDate.time)

                // ডিবাগের জন্য শুধু Logcat এ থাকে, ব্যবহারকারীকে দেখানো হয় না
                Log.d("OCR_RAW", "===== START =====\n$mlKitText\n===== END =====")

                lastOcrText = mlKitText
                val first = ShiftTextParser.parse(mlKitText, dateString)

                // Shift Trips পড়া না গেলে, বা Bookings+Airport মোটের চেয়ে বেশি দেখালে (সংখ্যা ভুল পড়ার লক্ষণ) দ্বিতীয়বার পড়া হয়
                val suspicious = first.shiftTrips == 0 || first.bookings + first.airportTrips > first.shiftTrips
                if (suspicious) {
                    // Shift Trips পড়া যায়নি: ছবি বড় ও স্পষ্ট করে দ্বিতীয়বার পড়া হয়, তারপর দুই ফল মেলানো হয়
                    binding.tvOcrStatus.text = "Reading again for better accuracy..."
                    val big = enhanceForOcr(bitmap)
                    recognizer.process(InputImage.fromBitmap(big, 0))
                        .addOnSuccessListener { vt2 ->
                            val text2 = MlKitOcrHelper.reconstructRows(vt2)
                            lastOcrText = mlKitText + "\n----- PASS 2 -----\n" + text2
                            finishOcr(mergeRecords(first, ShiftTextParser.parse(text2, dateString)))
                        }
                        .addOnFailureListener { finishOcr(first) }
                } else {
                    finishOcr(first)
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.tvOcrStatus.text = "Could not read image - please enter manually"
                fillForm(ShiftRecord(dateString = sdf.format(selectedDate.time)))
                binding.formContainer.visibility = View.VISIBLE
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun finishOcr(parsed: ShiftRecord) {
        fillForm(parsed)
        binding.progressBar.visibility = View.GONE
        binding.tvOcrStatus.text = "Data loaded - please check and edit if needed"
        binding.tvOcrStatus.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("OCR raw text (debug)")
                .setMessage(lastOcrText)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", lastOcrText))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
            true
        }
        showTripAlert()
        binding.formContainer.visibility = View.VISIBLE
    }

    // প্রথম পাসে ০ আসা ঘরে দ্বিতীয় পাসের মান বসানো হয়
    private fun mergeRecords(a: ShiftRecord, b: ShiftRecord): ShiftRecord = a.copy(
        driverNo = a.driverNo.ifEmpty { b.driverNo },
        shiftTotal = if (a.shiftTotal != 0.0) a.shiftTotal else b.shiftTotal,
        totalSharjah = if (a.totalSharjah != 0.0) a.totalSharjah else b.totalSharjah,
        totalTollways = if (a.totalTollways != 0.0) a.totalTollways else b.totalTollways,
        // যে সংখ্যাটা Bookings-এর চেয়ে কম নয় (সম্ভব) সেটাকে অগ্রাধিকার
        shiftTrips = run {
            val bk = maxOf(a.bookings, b.bookings)
            when {
                a.shiftTrips != 0 && a.shiftTrips >= bk -> a.shiftTrips
                b.shiftTrips != 0 && b.shiftTrips >= bk -> b.shiftTrips
                a.shiftTrips != 0 -> a.shiftTrips
                else -> b.shiftTrips
            }
        },
        bookings = if (a.bookings != 0) a.bookings else b.bookings,
        airportTrips = if (a.airportTrips != 0) a.airportTrips else b.airportTrips,
        kmHired = if (a.kmHired != 0.0) a.kmHired else b.kmHired,
        kmVacant = if (a.kmVacant != 0.0) a.kmVacant else b.kmVacant,
        paidInCareem = if (a.paidInCareem != 0.0) a.paidInCareem else b.paidInCareem,
        halaPeak = if (a.halaPeak != 0.0) a.halaPeak else b.halaPeak,
        tips = if (a.tips != 0.0) a.tips else b.tips
    )

    // ছবি বড় + সাদা-কালো + বেশি কনট্রাস্ট (ছোট সংখ্যা OCR-এ ভালো ওঠে)
    private fun enhanceForOcr(src: Bitmap): Bitmap {
        val scale = (2600f / maxOf(src.width, src.height)).coerceIn(1f, 2.5f)
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val k = 1.7f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, t,
            0f, k, 0f, 0f, t,
            0f, 0f, k, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        android.graphics.Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
        return out
    }

    // বক্সে ছুঁলেই পুরো সংখ্যা সিলেক্ট হয়, নতুন সংখ্যা লিখলেই বদলে যায়
    private fun selectAllOnTouch(vararg boxes: android.widget.EditText) {
        for (et in boxes) {
            et.setSelectAllOnFocus(true)
            et.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) (v as android.widget.EditText).post { v.selectAll() }
            }
            et.setOnClickListener { (it as android.widget.EditText).selectAll() }
        }
    }

    private fun fillForm(record: ShiftRecord) {
        selectAllOnTouch(
            binding.etPlateNo, binding.etShiftTotal, binding.etTotalSharjah, binding.etTotalTollways,
            binding.etShiftTrips, binding.etBookings, binding.etAirportTrips, binding.etKmHired,
            binding.etKmVacant, binding.etPaidInCareem, binding.etHalaPeak, binding.etTips
        )
        binding.etPlateNo.setText(record.driverNo)
        binding.etShiftTotal.setText(record.shiftTotal.toString())
        binding.etTotalSharjah.setText(record.totalSharjah.toString())
        binding.etTotalTollways.setText(record.totalTollways.toString())
        // রসিদে যা ছাপা আছে তাই বসে (Shift Trips ও Bookings আলাদাভাবে গোনা)
        // Shift Taxi: দুই ঘরেই রসিদের আসল সংখ্যা। Airport Taxi: রসিদের Bookings = Airport ট্রিপ, তাই সাধারণ ট্রিপ = Shift Trips − Bookings
        val shiftBox = if (isAirport) (record.shiftTrips - record.bookings).coerceAtLeast(0) else record.shiftTrips
        binding.etShiftTrips.setText(shiftBox.toString())
        // রসিদ পড়ায় সমস্যা: Shift Trips পড়া যায়নি, বা Bookings (+Airport) মোটের চেয়ে বেশি
        tripsMismatch = record.shiftTrips == 0 ||
                (if (isAirport) record.bookings else record.bookings + record.airportTrips) > record.shiftTrips
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
        val shiftTrips = binding.etShiftTrips.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val bookings = binding.etBookings.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val airport = binding.etAirportTrips.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0

        val msg = if (isAirport) {
            "Normal trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).normalTripRate)} AED each): $shiftTrips\n" +
                    "Airport trips (${rate(RateProvider.activeFor(TaxiType.AIRPORT_TAXI).airportTripRate)} AED each): $airport\n\n" +
                    "Are these correct?"
        } else {
            val normal = (shiftTrips - bookings - airport).coerceAtLeast(0)
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

        val enteredBookings = binding.etBookings.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val enteredAirport = binding.etAirportTrips.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val bookingsValue = if (isAirport) 0 else enteredBookings
        val airportValue = enteredAirport

        val record = ShiftRecord(
            dateString = dateString,
            driverNo = binding.etPlateNo.text.toString().trim(),
            shiftTotal = binding.etShiftTotal.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            totalSharjah = binding.etTotalSharjah.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            totalTollways = binding.etTotalTollways.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            shiftTrips = binding.etShiftTrips.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            bookings = bookingsValue,
            tripsNet = false,
            taxiType = (if (isAirport) TaxiType.AIRPORT_TAXI else TaxiType.SHIFT_TAXI).name,
            airportTrips = airportValue,
            kmHired = binding.etKmHired.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            kmVacant = binding.etKmVacant.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            paidInCareem = binding.etPaidInCareem.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            halaPeak = binding.etHalaPeak.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            tips = binding.etTips.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
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