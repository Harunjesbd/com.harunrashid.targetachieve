package com.harunrashid.targetachieve

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.harunrashid.targetachieve.databinding.ActivityReportsBinding
import com.harunrashid.targetachieve.models.Report
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ReportsActivity : AppCompatActivity() {

    companion object {
        // সর্বশেষ কয়টি রিপোর্ট রাখা হবে (পুরনোগুলো নিজে থেকে মুছে যাবে)
        const val MAX_REPORTS = 12
    }

    private lateinit var binding: ActivityReportsBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TargetAchieve_Dark)
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        loadReports()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadReports() {
        val uid = profileKey() ?: return
        binding.reportsContainer.removeAllViews()
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users").document(uid)
                    .collection("reports")
                    .orderBy("generatedAtMillis", Query.Direction.DESCENDING)
                    .get()
                    .await()

                val docs = snapshot.documents
                // ১২টির পুরনো রিপোর্ট মুছে ফেলা
                if (docs.size > MAX_REPORTS) {
                    for (old in docs.drop(MAX_REPORTS)) {
                        try { old.reference.delete().await() } catch (_: Exception) {}
                    }
                }
                val keep = docs.take(MAX_REPORTS)
                binding.progressBar.visibility = View.GONE

                if (keep.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    return@launch
                }
                binding.tvEmpty.visibility = View.GONE

                for (doc in keep) {
                    val report = doc.toObject(Report::class.java) ?: continue
                    addReportCard(report, doc.id)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReportsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addReportCard(report: Report, docId: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card_gold)
            setPadding(dp(16), dp(14), dp(8), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            isClickable = true
            isFocusable = true
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "${report.periodStart}  →  ${report.periodEnd}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = "Final Salary: %.0f AED   |   Target: %.1f%%".format(
                java.util.Locale.ENGLISH, report.finalSalary, report.achievedTargetPercent
            )
            textSize = 13f
            setTextColor(Color.parseColor("#E6CF9E"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(texts)

        card.addView(TextView(this).apply {
            text = "🗑️"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { confirmDelete(report, docId) }
        })

        card.setOnClickListener {
            val intent = Intent(this, ReportDetailActivity::class.java)
            intent.putExtra("reportId", report.reportId.ifBlank { docId })
            startActivity(intent)
        }

        binding.reportsContainer.addView(card)
    }

    private fun confirmDelete(report: Report, docId: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete report?")
            .setMessage("${report.periodStart} → ${report.periodEnd}\nThis report will be removed permanently.")
            .setPositiveButton("Delete") { _, _ ->
                val uid = profileKey() ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        db.collection("users").document(uid)
                            .collection("reports").document(docId).delete().await()
                        Toast.makeText(this@ReportsActivity, "Deleted", Toast.LENGTH_SHORT).show()
                        loadReports()
                    } catch (e: Exception) {
                        Toast.makeText(this@ReportsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
