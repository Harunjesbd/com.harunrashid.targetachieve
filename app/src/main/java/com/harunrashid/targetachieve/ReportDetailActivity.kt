package com.harunrashid.targetachieve

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityReportDetailBinding
import com.harunrashid.targetachieve.logic.CommissionSlab
import com.harunrashid.targetachieve.logic.ExportUtils
import com.harunrashid.targetachieve.models.Report
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class ReportDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportDetailBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reportId = intent.getStringExtra("reportId") ?: return
        loadReport(reportId)

        binding.btnExportJpeg.setOnClickListener {
            ExportUtils.exportViewAsJpeg(this, binding.captureLayout, "Report_$reportId")
        }
        binding.btnExportPdf.setOnClickListener {
            ExportUtils.exportViewAsPdf(this, binding.captureLayout, "Report_$reportId")
        }
    }

    private fun loadReport(reportId: String) {
        val key = profileKey() ?: return
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(key)
                    .collection("reports").document(reportId)
                    .get().await()
                val report = doc.toObject(Report::class.java) ?: return@launch
                bindReport(report)
            } catch (e: Exception) {
                Toast.makeText(this@ReportDetailActivity, "Could not load report: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun f0(v: Double) = String.format(Locale.ENGLISH, "%.0f", v)
    private fun f2(v: Double) = String.format(Locale.ENGLISH, "%.2f", v)

    private fun bindReport(r: Report) {
        binding.tvUserName.text = "Driver: ${r.userName}"
        binding.tvPeriod.text = "Period: ${r.periodStart} → ${r.periodEnd}"
        binding.tvWorkingDays.text = if (r.workingDays > 0) "Working Days: ${r.workingDays}" else ""
        binding.tvWorkingDays.visibility = if (r.workingDays > 0) View.VISIBLE else View.GONE

        if (r.generatedAtMillis > 0) {
            val fmt = java.text.SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
            val gen = fmt.format(java.util.Date(r.generatedAtMillis))
            val end = try { fmt.parse(r.periodEnd) } catch (e: Exception) { null }
            val partial = end != null && r.generatedAtMillis < end.time
            binding.tvGenerated.text = "Generated: $gen" + if (partial) " (month not finished)" else ""
        }
        binding.tvFinalBig.text = "${f0(r.finalSalary)} AED"
        binding.tvCleanMoney.text = "${f0(r.totalCleanMoney)} AED"

        val avg = if (r.averageClean > 0) r.averageClean
        else if (r.workingDays > 0) r.totalCleanMoney / r.workingDays else 0.0
        binding.tvAvgClean.text = if (avg > 0) "${f0(avg)} AED" else "-"

        // ট্রিপের মোট (পুরনো রিপোর্টে তথ্য না থাকলে কার্ড লুকানো)
        val hasTrips = r.shiftTrips + r.bookings + r.airportTrips > 0
        binding.cardTrips.visibility = if (hasTrips) View.VISIBLE else View.GONE
        binding.tvTripsShift.text = r.shiftTrips.toString()
        binding.tvTripsNormal.text = (if (r.normalTrips > 0) r.normalTrips else (r.shiftTrips - r.bookings - r.airportTrips).coerceAtLeast(0)).toString()
        binding.tvTripsBookings.text = r.bookings.toString()
        binding.tvTripsAirport.text = r.airportTrips.toString()

        // মোট Deposit (পুরনো রিপোর্টে তথ্য না থাকলে কার্ড লুকানো)
        val dep = if (r.totalDeposit > 0) r.totalDeposit else r.days.sumOf { it.deposit }
        binding.tvTotalDeposit.text = if (dep > 0) "${f0(dep)} AED" else "-"
        binding.tvTotalKm.text = if (r.totalKmHired > 0) "${f0(r.totalKmHired)} km" else "-"

        binding.tvAchievedTarget.text = String.format(Locale.ENGLISH, "%.1f%%", r.achievedTargetPercent)
        val top = CommissionSlab.TABLE.last().percent
        binding.progressTarget.progress = ((r.achievedTargetPercent / top) * 100).toInt().coerceIn(0, 100)
        binding.tvNextTarget.text = if (r.nextTargetPercent > 0)
            "Next target ${String.format(Locale.ENGLISH, "%.1f", r.nextTargetPercent)}% → need ${f0(r.aedNeededPerDayForNextTarget)} AED Clean Money per day"
        else "You are at the highest target level"

        binding.tvBaseSalary.text = "Base Salary (Clean Money × Target %): ${f0(r.baseSalary)} AED"
        binding.tvExtraAdd.text = "+ Tips: ${f0(r.extraAdd)} AED"
        binding.tvFuelPenalty.text = "- Fuel Penalty (excess vacant km): ${f0(r.fuelPenalty)} AED"
        binding.tvFinalSalary.text = "Final Salary: ${f0(r.finalSalary)} AED"

        showDays(r)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cell(text: String, weight: Float, bold: Boolean, color: String, start: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = if (start) Gravity.START else Gravity.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    private fun line(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#E3E6EE"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun showDays(r: Report) {
        val c = binding.daysContainer
        c.removeAllViews()

        if (r.days.isEmpty()) {
            c.addView(TextView(this).apply {
                text = "Daily breakdown is not available for reports saved before this update."
                textSize = 12f
                setTextColor(Color.parseColor("#445566"))
            })
            return
        }

        fun row(a: String, dep: String, b: String, d: String, e: String, bold: Boolean, color: String) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                addView(cell(a, 1.35f, bold, color, true))
                addView(cell(dep, 1f, bold, color))
                addView(cell(b, 1f, bold, color))
                addView(cell(d, 0.6f, bold, color))
                addView(cell(e, 0.9f, bold, color))
            }

        c.addView(row("Date", "Deposit", "Clean", "Tips", "Avg", true, "#8A6D10"))
        c.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#D4AF37"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        for (d in r.days) {
            c.addView(row(d.date, if (d.deposit > 0) f2(d.deposit) else "-", f2(d.clean), f0(d.tips), f0(d.avg), false, "#10204F"))
            c.addView(line())
        }
        val totalTips = r.days.sumOf { it.tips }
        val depSum = r.days.sumOf { it.deposit }
        c.addView(row("TOTAL", if (depSum > 0) f2(depSum) else "-", f2(r.days.sumOf { it.clean }), f0(totalTips), f0(avg(r)), true, "#10204F"))
    }

    private fun avg(r: Report): Double =
        if (r.averageClean > 0) r.averageClean
        else if (r.days.isNotEmpty()) r.days.sumOf { it.clean } / r.days.size else 0.0
}
