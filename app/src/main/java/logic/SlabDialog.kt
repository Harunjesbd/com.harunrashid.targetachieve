package com.harunrashid.targetachieve.logic

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Salary Slab তালিকা দেখানোর পপ-আপ। তালিকা আসে CommissionSlab.TABLE থেকে, তাই স্ল্যাব বদলালে এটাও বদলায়। */
object SlabDialog {

    private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else "%.2f".format(d)
    private fun pct(d: Double): String = if (d % 1.0 == 0.0) "${d.toInt()}%" else "$d%"

    fun show(activity: Activity, highlightPercent: Double? = null) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141B3D.toInt())
            setPadding(px(12), px(12), px(12), px(12))
        }

        fun addRow(left: String, right: String, bg: Int, fg: Int, bold: Boolean) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(bg)
                setPadding(px(12), px(9), px(12), px(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(4)) }
            }
            fun cell(text: String, w: Float, gravity: Int) = TextView(activity).apply {
                this.text = text
                setTextColor(fg)
                textSize = 15f
                this.gravity = gravity
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            }
            row.addView(cell(left, 0.8f, Gravity.START))
            row.addView(cell(right, 1.2f, Gravity.END))
            root.addView(row)
        }

        addRow("Commission", "Clean Money / day (AED)", 0xFF2A3566.toInt(), 0xFFE6CF9E.toInt(), true)
        CommissionSlab.TABLE.forEachIndexed { i, r ->
            val isMine = highlightPercent != null && r.percent == highlightPercent
            val bg = when {
                isMine -> 0xFFE6CF9E.toInt()
                i % 2 == 0 -> 0xFF232E63.toInt()
                else -> 0xFF1B2452.toInt()
            }
            val fg = if (isMine) Color.BLACK else Color.WHITE
            addRow(pct(r.percent), "${fmt(r.minAed)} – ${fmt(r.maxAed)}", bg, fg, true)
        }

        root.addView(TextView(activity).apply {
            text = "Daily average of Clean Money in the period (21st → 20th) decides your slab." +
                    (if (highlightPercent != null && highlightPercent > 0) "\nHighlighted row = your current slab." else "")
            setTextColor(0xFFCFD3E6.toInt())
            textSize = 12f
            setPadding(px(4), px(8), px(4), 0)
        })

        val scroll = ScrollView(activity).apply { addView(root) }
        AlertDialog.Builder(activity)
            .setTitle("Salary Slab")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }
}
