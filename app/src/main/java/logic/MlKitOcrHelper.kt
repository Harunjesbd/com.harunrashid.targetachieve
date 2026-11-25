package com.harunrashid.targetachieve.logic

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

object MlKitOcrHelper {

    /**
     * ML Kit এর টেক্সট ব্লকগুলো থেকে পজিশন (Y-coordinate) অনুযায়ী
     * লাইনগুলোকে আবার সঠিকভাবে রিকনস্ট্রাক্ট (Reconstruct) করে।
     */
    fun reconstructRows(visionText: Text): String {
        // ১. ML Kit এর সমস্ত লাইন (Text.Line) একটা লিস্টে সংগ্রহ করা
        val allLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            allLines.addAll(block.lines)
        }

        if (allLines.isEmpty()) return ""

        // ২. লাইনগুলোকে উপর থেকে নিচে (Y-axis) সাজানো
        allLines.sortBy { line -> line.boundingBox?.top ?: 0 }

        val rows = mutableListOf<MutableList<Text.Line>>()
        val rowCenters = mutableListOf<Double>()   // সারির গড় Y (রসিদ হালকা বাঁকা হলেও সারি ঠিক থাকে)
        val rowHeights = mutableListOf<Double>()

        for (line in allLines) {
            val box = line.boundingBox ?: continue
            val c = (box.top + box.bottom) / 2.0
            val h = box.height().coerceAtLeast(1).toDouble()

            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in rows.indices) {
                val dist = Math.abs(rowCenters[i] - c)
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            if (best >= 0 && bestDist < ((rowHeights[best] + h) / 2.0) * 0.45) {
                val n = rows[best].size
                rows[best].add(line)
                rowCenters[best] = (rowCenters[best] * n + c) / (n + 1)
                rowHeights[best] = (rowHeights[best] * n + h) / (n + 1)
            } else {
                rows.add(mutableListOf(line))
                rowCenters.add(c)
                rowHeights.add(h)
            }
        }

        // ৪. প্রতিটি সারির ভেতরে টেক্সটগুলোকে বাম থেকে ডানে (X-axis) সাজিয়ে লাইন তৈরি করা
        val reconstructedText = StringBuilder()

        for (row in rows) {
            // X-axis (Left) অনুযায়ী সাজানো
            row.sortBy { it.boundingBox?.left ?: 0 }

            val rowText = row.joinToString(" ") { it.text }
            reconstructedText.append(rowText).append("\n")
        }

        return reconstructedText.toString()
    }

    /**
     * দুইটা লাইন একই সারিতে (Row) আছে কিনা তা Y-coordinates এর পার্থক্য দিয়ে চেক করে।
     */
    private fun isSameRow(box1: Rect, box2: Rect): Boolean {
        val center1 = (box1.top + box1.bottom) / 2
        val center2 = (box2.top + box2.bottom) / 2
        val heightAvg = ((box1.height() + box2.height()) / 2).coerceAtLeast(1)

        // যদি লাইনের সেন্টারের পার্থক্য ফ্রন্টের গড় উচ্চতার ৫০% এর কম হয়, তবে তারা একই লাইনের
        return Math.abs(center1 - center2) < (heightAvg * 0.6)
    }
}