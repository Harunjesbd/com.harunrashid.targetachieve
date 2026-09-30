package com.harunrashid.targetachieve

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.harunrashid.targetachieve.databinding.ActivityPeriodDetailBinding
import com.harunrashid.targetachieve.logic.DayResult

class PeriodDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPeriodDetailBinding

    companion object {
        var daysList: List<DayResult> = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        val periodStart = intent.getStringExtra("periodStart") ?: "-"
        val periodEnd = intent.getStringExtra("periodEnd") ?: "-"
        val totalClean = intent.getDoubleExtra("totalClean", 0.0)
        val finalSalary = intent.getDoubleExtra("finalSalary", 0.0)
        val targetPercent = intent.getDoubleExtra("targetPercent", 0.0)

        binding.tvPeriodTitle.text = "$periodStart  →  $periodEnd"
        binding.tvSummary.text = "Total Clean: %.0f AED  |  Salary: %.0f AED  |  Target: %.1f%%"
            .format(totalClean, finalSalary, targetPercent)

        showDays(daysList)
    }

    private fun showDays(days: List<DayResult>) {
        binding.daysContainer.removeAllViews()

        if (days.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No shifts in this period"
                setPadding(16, 40, 16, 16)
                textSize = 14f
            }
            binding.daysContainer.addView(empty)
            return
        }

        days.sortedByDescending { it.record.dateString }.forEach { day ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 14, 12, 14)
                setBackgroundColor(0xFFF8F9FC.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 6) }
            }

            fun makeTv(text: String, weight: Float, end: Boolean = false): TextView {
                return TextView(this).apply {
                    this.text = text
                    textSize = 13f
                    if (end) gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                }
            }

            row.addView(makeTv(day.record.dateString, 1.3f))
            row.addView(makeTv("%.0f".format(day.cleanMoney), 1f, true))
            row.addView(makeTv(day.record.shiftTrips.toString(), 0.7f, true))
            row.addView(makeTv("%.1f".format(day.record.kmHired), 1f, true))

            binding.daysContainer.addView(row)
        }
    }
}