package com.harunrashid.targetachieve.models

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import com.harunrashid.targetachieve.profileKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
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

    private fun iconBtn(symbol: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
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
            addView(cell("Clean", 1f, true))
            addView(cell("Salary", 1f, true))
            addView(cell("Target", 0.8f, true))
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(88), 1) })
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
                addView(cell(d.record.dateString, 1.3f, false, false))
                addView(cell("%.2f".format(Locale.ENGLISH, d.cleanMoney), 1f))
                addView(cell("%.2f".format(Locale.ENGLISH, d.dailyEarning), 1f))
                addView(cell("%.1f%%".format(Locale.ENGLISH, d.slabPercent), 0.8f))
                addView(iconBtn("✏️") { showEditDialog(d) })
                addView(iconBtn("🗑️") { confirmDelete(d) })
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
            Triple("Shift Trips", "shiftTrips", r.shiftTrips.toString()) to true,
            Triple("Bookings", "bookings", r.bookings.toString()) to true,
            Triple("Airport Trips", "airportTrips", r.airportTrips.toString()) to true,
            Triple("Tips", "tips", r.tips.toString()) to false
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val inputs = mutableListOf<Pair<EditText, Pair<String, Boolean>>>()
        for ((t, isInt) in fields) {
            box.addView(TextView(this).apply { text = t.first; textSize = 12f; setPadding(0, dp(8), 0, 0) })
            val et = EditText(this).apply {
                setText(t.third)
                inputType = if (isInt) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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
                    map[meta.first] = if (meta.second) (txt.toIntOrNull() ?: 0) else (txt.toDoubleOrNull() ?: 0.0)
                }
                saveEdit(r.dateString, map)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveEdit(dateString: String, map: Map<String, Any>) {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                db.collection("users").document(key)
                    .collection("shifts").document(dateString)
                    .update(map).await()
                Toast.makeText(this@PeriodDetailActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PeriodDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
