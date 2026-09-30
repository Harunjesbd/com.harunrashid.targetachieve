package com.harunrashid.targetachieve

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.harunrashid.targetachieve.databinding.ActivityReportsBinding
import com.harunrashid.targetachieve.models.Report
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ReportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportsBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadReports()
    }

    private fun loadReports() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users").document(uid)
                    .collection("reports")
                    .orderBy("generatedAtMillis", Query.Direction.DESCENDING)
                    .get()
                    .await()

                val reports = snapshot.toObjects(Report::class.java)
                binding.progressBar.visibility = View.GONE

                if (reports.isEmpty()) {
                    val empty = TextView(this@ReportsActivity).apply {
                        text = "এখনো কোনো রিপোর্ট সেভ করা হয়নি"
                        textSize = 14f
                        setPadding(16, 40, 16, 16)
                    }
                    binding.reportsContainer.addView(empty)
                    return@launch
                }

                reports.forEach { report -> addReportCard(report) }

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReportsActivity, "রিপোর্ট লোড করতে সমস্যা: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addReportCard(report: Report) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFFF5F7FF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
            isClickable = true
            isFocusable = true
        }

        card.addView(TextView(this).apply {
            text = "${report.periodStart}  →  ${report.periodEnd}"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = "Final Salary: %.0f AED   |   Target: %.1f%%".format(report.finalSalary, report.achievedTargetPercent)
            textSize = 13f
            setPadding(0, 6, 0, 0)
        })

        card.setOnClickListener {
            val intent = Intent(this, ReportDetailActivity::class.java)
            intent.putExtra("reportId", report.reportId)
            startActivity(intent)
        }

        binding.reportsContainer.addView(card)
    }
}