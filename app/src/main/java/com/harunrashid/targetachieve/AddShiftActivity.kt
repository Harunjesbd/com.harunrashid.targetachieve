package com.harunrashid.targetachieve

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.harunrashid.targetachieve.logic.ShiftTextParser
import com.harunrashid.targetachieve.models.ShiftRecord
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
                val mlKitText = visionText.text
                val dateString = sdf.format(selectedDate.time)

                // ===== TEMPORARY DEBUG =====
                Log.d("OCR_RAW", "===== START =====\n$mlKitText\n===== END =====")

                AlertDialog.Builder(this)
                    .setTitle("OCR Raw Text (Debug)")
                    .setMessage(mlKitText.take(2000))
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", mlKitText))
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Continue") { _, _ ->
                        val parsed = ShiftTextParser.parse(mlKitText, dateString)
                        fillForm(parsed)
                        binding.progressBar.visibility = View.GONE
                        binding.tvOcrStatus.text = "Data loaded - please check and edit if needed"
                        binding.formContainer.visibility = View.VISIBLE
                    }
                    .setCancelable(false)
                    .show()
                // ===== END TEMPORARY =====
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
        binding.etPlateNo.setText(record.plateNo)
        binding.etShiftTotal.setText(if (record.shiftTotal != 0.0) record.shiftTotal.toString() else "")
        binding.etTotalSharjah.setText(if (record.totalSharjah != 0.0) record.totalSharjah.toString() else "")
        binding.etTotalTollways.setText(if (record.totalTollways != 0.0) record.totalTollways.toString() else "")
        binding.etShiftTrips.setText(if (record.shiftTrips != 0) record.shiftTrips.toString() else "")
        binding.etBookings.setText(if (record.bookings != 0) record.bookings.toString() else "")
        binding.etAirportTrips.setText(if (record.airportTrips != 0) record.airportTrips.toString() else "")
        binding.etKmHired.setText(if (record.kmHired != 0.0) record.kmHired.toString() else "")
        binding.etKmVacant.setText(if (record.kmVacant != 0.0) record.kmVacant.toString() else "")
        binding.etPaidInCareem.setText(if (record.paidInCareem != 0.0) record.paidInCareem.toString() else "")
        binding.etHalaPeak.setText(if (record.halaPeak != 0.0) record.halaPeak.toString() else "")
        binding.etTips.setText(if (record.tips != 0.0) record.tips.toString() else "")
    }

    private fun saveShift() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Login problem, please try again", Toast.LENGTH_SHORT).show()
            return
        }

        val dateString = sdf.format(selectedDate.time)

        val record = ShiftRecord(
            dateString = dateString,
            plateNo = binding.etPlateNo.text.toString().trim(),
            shiftTotal = binding.etShiftTotal.text.toString().toDoubleOrNull() ?: 0.0,
            totalSharjah = binding.etTotalSharjah.text.toString().toDoubleOrNull() ?: 0.0,
            totalTollways = binding.etTotalTollways.text.toString().toDoubleOrNull() ?: 0.0,
            shiftTrips = binding.etShiftTrips.text.toString().toIntOrNull() ?: 0,
            bookings = binding.etBookings.text.toString().toIntOrNull() ?: 0,
            airportTrips = binding.etAirportTrips.text.toString().toIntOrNull() ?: 0,
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