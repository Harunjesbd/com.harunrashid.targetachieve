package com.harunrashid.targetachieve.models

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityPeriodDetailBinding
import com.harunrashid.targetachieve.logic.DayResult
import com.harunrashid.targetachieve.logic.SalaryCalculator
import com.harunrashid.targetachieve.profileKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PeriodDetailActivity : AppCompatActivity() {

    companion object {
        // MainActivity এখানে দিনের তালিকা রেখে Activity খোলে
        var daysList: List<DayResult> = emptyList()
    }

    private lateinit var binding: ActivityPeriodDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        val sorted = sortedDesc()
        val total = sorted.sumOf { it.cleanMoney }
        val first = sorted.lastOrNull()?.record?.dateString ?: "-"
        val last = sorted.firstOrNull()?.record?.dateString ?: "-"
        binding.tvPeriodTitle.text = intent.getStringExtra("title") ?: "$first  →  $last"
        binding.tvSummary.text = intent.getStringExtra("summary")
            ?: "Days: ${sorted.size}   |   Total Clean: %.2f AED".format(Locale.ENGLISH, total)

        val totalDeposit = sorted.sumOf { it.deposit }
        binding.tvTotalDeposit.text = "Total Deposit: %.2f AED".format(Locale.ENGLISH, totalDeposit)

        val avg = if (sorted.isEmpty()) 0.0 else total / sorted.size
        binding.tvAvgClean.text = "%.0f".format(Locale.ENGLISH, avg)

        showDays()
    }

    private fun sortedDesc(): List<DayResult> = daysList.sortedByDescending {
        try { dateFmt.parse(it.record.dateString)?.time ?: 0L } catch (e: Exception) { 0L }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cell(text: String, weight: Float, bold: Boolean = false, end: Boolean = true): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = if (end) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    // প্রতিটি সারির শেষে ⋮ বোতাম: চাপলে Edit / Remove মেনু আসে (জায়গা বাঁচাতে)
    private fun menuBtn(d: DayResult): TextView =
        TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E6CF9E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { v ->
                val pm = PopupMenu(this@PeriodDetailActivity, v)
                pm.menu.add(0, 1, 0, "✏️  Edit")
                pm.menu.add(0, 2, 1, "🗑️  Remove")
                pm.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> showEditDialog(d)
                        2 -> confirmDelete(d)
                    }
                    true
                }
                pm.show()
            }
        }

    private fun showDays() {
        val c = binding.daysContainer
        c.removeAllViews()

        // একটাই হেডার সারি (কোড থেকে)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#232E63"))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            addView(cell("Date", 1.3f, true, false).apply { setTextColor(Color.parseColor("#E6CF9E")) })
            addView(cell("Deposit", 1.05f, true).apply { setTextColor(Color.parseColor("#E6CF9E")) })
            addView(cell("Clean", 1f, true).apply { setTextColor(Color.parseColor("#E6CF9E")) })
            addView(cell("Salary", 0.95f, true).apply { setTextColor(Color.parseColor("#E6CF9E")) })
            addView(cell("Target", 0.8f, true).apply { setTextColor(Color.parseColor("#E6CF9E")) })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(36), 1) })
        }
        c.addView(header)

        val list = sortedDesc()
        if (list.isEmpty()) {
            c.addView(TextView(this).apply { text = "No shifts"; setPadding(dp(8), dp(16), dp(8), 0) })
            return
        }

        for (d in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(cell(d.record.dateString, 1.3f, true, false).apply { setTextColor(Color.WHITE) })
                addView(cell("%.2f".format(Locale.ENGLISH, d.deposit), 1.05f, true).apply { setTextColor(Color.parseColor("#E6CF9E")) })
                addView(cell("%.2f".format(Locale.ENGLISH, d.cleanMoney), 1f, true).apply { setTextColor(Color.WHITE) })
                addView(cell("%.2f".format(Locale.ENGLISH, d.dailyEarning), 0.95f, true).apply { setTextColor(Color.WHITE) })
                addView(cell("%.1f%%".format(Locale.ENGLISH, d.slabPercent), 0.8f, true).apply { setTextColor(Color.WHITE) })
                addView(menuBtn(d))
            }
            c.addView(row)
            c.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#2A3566"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    // ---------- Delete ----------
    private fun confirmDelete(d: DayResult) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${d.record.dateString}?")
            .setMessage("This day will be removed permanently.")
            .setPositiveButton("Delete") { _, _ -> deleteDay(d) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDay(d: DayResult) {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                db.collection("users").document(key)
                    .collection("shifts").document(d.record.dateString)
                    .delete().await()
                Toast.makeText(this@PeriodDetailActivity, "Deleted", Toast.LENGTH_SHORT).show()
                finish() // Home (onResume) নতুন করে হিসাব দেখাবে; আবার খুললে হালনাগাদ তালিকা
            } catch (e: Exception) {
                Toast.makeText(this@PeriodDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Edit ----------
    private fun showEditDialog(d: DayResult) {
        val r = d.record
        // (লেবেল, Firestore ফিল্ড, বর্তমান মান, পূর্ণসংখ্যা কি না)
        val fields = listOf(
            Triple("Shift Total", "shiftTotal", r.shiftTotal.toString()) to false,
            Triple("Paid in Careem", "paidInCareem", r.paidInCareem.toString()) to false,
            Triple("Hala Peak", "halaPeak", r.halaPeak.toString()) to false,
            Triple("Km Hired", "kmHired", r.kmHired.toString()) to false,
            Triple("Km Vacant", "kmVacant", r.kmVacant.toString()) to false,
            Triple("Total Tollways", "totalTollways", r.totalTollways.toString()) to false,
            Triple("Shift Trips", "shiftTrips", (if (r.taxiType == "AIRPORT_TAXI") r.shiftTrips else SalaryCalculator.rawTripsOf(r)).toString()) to true,
            Triple("Bookings", "bookings", r.bookings.toString()) to true,
            Triple("Airport Trips", "airportTrips", r.airportTrips.toString()) to true,
            Triple("Tips", "tips", r.tips.toString()) to false
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val inputs = mutableListOf<Pair<EditText, Pair<String, Boolean>>>()

        // তারিখ ঠিক করার ঘর: চাপলে ক্যালেন্ডার খোলে
        box.addView(TextView(this).apply { text = "Date"; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        val etDate = EditText(this).apply {
            setText(r.dateString)
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            setOnClickListener {
                val cal = Calendar.getInstance()
                try { dateFmt.parse(text.toString())?.let { cal.time = it } } catch (_: Exception) {}
                DatePickerDialog(this@PeriodDetailActivity, { _, y, m, day ->
                    val c2 = Calendar.getInstance(); c2.set(y, m, day, 0, 0, 0)
                    setText(dateFmt.format(c2.time))
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        box.addView(etDate)

        for ((t, isInt) in fields) {
            box.addView(TextView(this).apply { text = t.first; textSize = 12f; setPadding(0, dp(8), 0, 0) })
            val et = EditText(this).apply {
                setText(t.third)
                inputType = if (isInt) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                // ছুঁলেই পুরো সংখ্যা সিলেক্ট হয়
                setSelectAllOnFocus(true)
                setOnFocusChangeListener { v, hasFocus -> if (hasFocus) (v as EditText).post { v.selectAll() } }
                setOnClickListener { (it as EditText).selectAll() }
            }
            box.addView(et)
            inputs.add(et to (t.second to isInt))
        }
        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Edit ${r.dateString}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val map = HashMap<String, Any>()
                for ((et, meta) in inputs) {
                    val txt = et.text.toString().trim().replace(',', '.')
                    map[meta.first] = if (meta.second) (txt.toIntOrNull() ?: 0).coerceAtLeast(0) else (txt.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                }
                map["tripsNet"] = false  // Shift Trips ঘর = রসিদের আসল সংখ্যা
                val newDate = etDate.text.toString().trim()
                val ok = try {
                    val f = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH); f.isLenient = false; f.parse(newDate) != null
                } catch (e: Exception) { false }
                if (!ok) {
                    Toast.makeText(this, "Invalid date (use dd-MM-yyyy)", Toast.LENGTH_LONG).show()
                } else {
                    saveEdit(r.dateString, newDate, map)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // তারিখ বদলালে: নতুন তারিখে ডকুমেন্ট বানিয়ে পুরনোটা মুছে ফেলা হয় (ওই তারিখে আগে থেকে শিফট থাকলে বন্ধ)
    private fun saveEdit(oldDate: String, newDate: String, map: Map<String, Any>) {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                val col = db.collection("users").document(key).collection("shifts")
                if (newDate == oldDate) {
                    col.document(oldDate).update(map).await()
                } else {
                    val exists = col.document(newDate).get().await().exists()
                    if (exists) {
                        Toast.makeText(this@PeriodDetailActivity,
                            "$newDate তারিখে আগে থেকেই শিফট আছে। অন্য তারিখ দিন।", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val old = col.document(oldDate).get().await()
                    val data = HashMap<String, Any?>(old.data ?: emptyMap())
                    data.putAll(map)
                    data["dateString"] = newDate
                    val batch = db.batch()
                    batch.set(col.document(newDate), data)
                    batch.delete(col.document(oldDate))
                    batch.commit().await()
                }
                Toast.makeText(this@PeriodDetailActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PeriodDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
