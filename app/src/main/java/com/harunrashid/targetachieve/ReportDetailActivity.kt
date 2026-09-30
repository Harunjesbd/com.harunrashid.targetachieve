package com.harunrashid.targetachieve

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.databinding.ActivityReportDetailBinding
import com.harunrashid.targetachieve.logic.ExportUtils
import com.harunrashid.targetachieve.models.Report
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ReportDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportDetailBinding
    private val auth = FirebaseAuth.getInstance()
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
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(uid)
                    .collection("reports").document(reportId)
                    .get().await()

                val report = doc.toObject(Report::class.java) ?: return@launch
                bindReport(report)

            } catch (e: Exception) {
                Toast.makeText(this@ReportDetailActivity, "রিপোর্ট লোড করতে সমস্যা: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindReport(report: Report) {
        binding.tvUserName.text = "User Name: ${report.userName}"
        binding.tvPeriod.text = "Current Month: ${report.periodStart} → ${report.periodEnd}"

        binding.tvAchievedTarget.text =
            "➤ Achieved Target %%: %.1f%% (%.0f AED)".format(report.achievedTargetPercent, report.finalSalary)

        binding.tvNextTarget.text = if (report.nextTargetPercent > 0) {
            "For the next target (%.1f%%) you need per day: +%.0f AED".format(
                report.nextTargetPercent, report.aedNeededPerDayForNextTarget
            )
        } else {
            "You reached the highest target level!"
        }

        binding.tvCleanMoney.text = "Total Clean Money: %.0f AED".format(report.totalCleanMoney)
        binding.tvBaseSalary.text = "Base Salary (Clean Money × Target %%): %.0f AED".format(report.baseSalary)
        binding.tvExtraAdd.text = "+ Tips: %.0f AED".format(report.extraAdd)
        binding.tvFuelPenalty.text = "- Fuel Penalty (excess vacant km): %.0f AED".format(report.fuelPenalty)
        binding.tvFinalSalary.text = "Final Salary: %.0f AED".format(report.finalSalary)
    }
}