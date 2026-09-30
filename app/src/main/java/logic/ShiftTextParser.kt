package com.harunrashid.targetachieve.logic

import com.harunrashid.targetachieve.models.ShiftRecord

object ShiftTextParser {

    fun parse(rawText: String, dateString: String): ShiftRecord {
        val text = rawText
            .replace("\r", "\n")
            .replace(Regex("\\s+"), " ")
            .trim()

        // সব গুরুত্বপূর্ণ নাম্বার বের করা
        val shiftTotal = findNumberNear(text, listOf("Shift Total", "SHIFT TOTAL", "ShiftTotal"))
        val waiting = findNumberNear(text, listOf("Waiting Charges", "Mait ing Char ges", "Waiting"))
        val creditCards = findNumberNear(text, listOf("Credit Cards"))
        val cash = findNumberNear(text, listOf("Cash"))
        val tollways = findNumberNear(text, listOf("Total Tollways", "Tollways"))
        val sharjah = findNumberNear(text, listOf("Total Sharjah", "Sharjah"))
        val paidCareem = findNumberNear(text, listOf("Paid in Careem", "Paid in Caree", "Careem"))
        val tips = findNumberNear(text, listOf("Tips", "Iips"))
        val halaPeak = findNumberNear(text, listOf("Hala Peak", "Peak"))

        val trips = findIntNear(text, listOf("Shift Trips", "Shift Trips"))
        val bookings = findIntNear(text, listOf("Bookings", "Book ings"))
        val kmHired = findNumberNear(text, listOf("Kilometers Hired", "Hired"))
        val kmVacant = findNumberNear(text, listOf("Kilometers Vacant", "Vacant"))

        val plate = findPlate(text)

        return ShiftRecord(
            dateString = dateString,
            plateNo = plate,
            waitingCharges = waiting,
            creditCards = creditCards,
            cash = cash,
            totalSharjah = sharjah,
            totalTollways = tollways,
            shiftTotal = shiftTotal,
            shiftTrips = trips,
            bookings = bookings,
            kmHired = kmHired,
            kmVacant = kmVacant,
            paidInCareem = paidCareem,
            halaPeak = halaPeak,
            tips = tips
        )
    }

    private fun findPlate(text: String): String {
        // DH625 / OH625 / DX334 টাইপ
        val match = Regex("""\b([A-Z]{1,3}\d{3,5})\b""").find(text)
        return match?.groupValues?.get(1)?.uppercase() ?: ""
    }

    private fun findNumberNear(text: String, labels: List<String>): Double {
        for (label in labels) {
            // লেবেলের পরে আসা প্রথম নাম্বার খোঁজা
            val pattern = Regex(
                """(?i)${Regex.escape(label)}[^0-9]{0,30}([0-9]+(?:[.,][0-9]+)?)"""
            )
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            }
        }
        return 0.0
    }

    private fun findIntNear(text: String, labels: List<String>): Int {
        for (label in labels) {
            val pattern = Regex(
                """(?i)${Regex.escape(label)}[^0-9]{0,20}([0-9]+)"""
            )
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return 0
    }
}