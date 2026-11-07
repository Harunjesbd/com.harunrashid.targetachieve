package com.harunrashid.targetachieve.logic

import com.harunrashid.targetachieve.models.ShiftRecord

/**
 * RTA Shift Summary রিসিটের OCR টেক্সট থেকে সংখ্যা বের করে।
 * কৌশল:
 *  1) প্রতিটি লাইনের স্পেস মুছে ছোট হাতের করা হয় ("Dr iver No" -> "driverno")
 *  2) লেবেলের বানান-ভুল OCR-এ যেমন হয় (o/0, l/1/i, m/n, e/c ...) সব মিলিয়ে খোঁজা হয়
 *  3) সংখ্যা একই লাইনে না থাকলে পরের লাইনে খোঁজা হয়
 *  4) "159,50" কে 159.50 ধরা হয়; AED/km/ka/kn ইত্যাদি বাদ যায়
 */
object ShiftTextParser {

    private val ALT = mapOf(
        'o' to "[o0]", '0' to "[o0]", 'l' to "[li1|]", 'i' to "[il1|]", '1' to "[1il|]",
        'e' to "[ec]", 'c' to "[ce]", 'h' to "[hn]", 'n' to "[nhmr]", 'm' to "[mn]",
        'a' to "[ao]", 's' to "[s5]", 't' to "[tf]", 'r' to "[rn]", 'u' to "[uv]",
        'v' to "[vu]", 'd' to "[do]", 'k' to "[kx]", 'b' to "[bh]", 'g' to "[g9q]"
    )

    private fun fuzzy(label: String): Regex =
        Regex(label.lowercase().map { ALT[it] ?: Regex.escape(it.toString()) }.joinToString(""))

    private val AED = Regex("a[ef][do0]")
    private val KM = Regex("k[mna]/?h?")
    private val AMOUNT = Regex("""(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+[.,]\d{1,2}|\d+)""")

    private fun cleanValue(s: String): String {
        val t = KM.replace(AED.replace(s, ""), "")
        return t.map {
            when (it) { 'o' -> '0'; 'l' -> '1'; 'i' -> '1'; '|' -> '1'; 's' -> '5'; 'z' -> '2'; else -> it }
        }.joinToString("")
    }

    private fun toDouble(s: String): Double? {
        val x = if (s.contains(',') && s.contains('.')) s.replace(",", "") else s.replace(',', '.')
        return x.toDoubleOrNull()
    }

    private enum class Kind { DRIVER, INT, AMOUNT, TIME }

    private fun numberFrom(rest: String, kind: Kind): String? {
        val c = cleanValue(rest)
        return when (kind) {
            Kind.DRIVER -> Regex("""\d{4,10}""").find(c)?.value
            Kind.INT -> Regex("""\d+""").find(c)?.value
            Kind.TIME -> Regex("""(\d{1,2})[:.;](\d{2})""").find(c)
                ?.let { it.groupValues[1].padStart(2, '0') + ":" + it.groupValues[2] }
            Kind.AMOUNT -> AMOUNT.find(c)?.groupValues?.get(1)?.let { toDouble(it)?.toString() }
        }
    }

    private fun valueOnly(line: String): Boolean {
        val s = KM.replace(AED.replace(line, ""), "")
        return s.isNotEmpty() && s.any { it.isDigit() } && s.all { it in "0123456789.,:-|" }
    }

    private fun find(lines: List<String>, labels: List<Regex>, kind: Kind): String? {
        for (rx in labels) {
            for ((i, line) in lines.withIndex()) {
                val m = rx.find(line) ?: continue
                var v = numberFrom(line.substring(m.range.last + 1), kind)
                if (v == null && i + 1 < lines.size && valueOnly(lines[i + 1])) {
                    v = numberFrom(lines[i + 1], kind)
                }
                if (v != null) return v
            }
        }
        return null
    }

    private fun d(lines: List<String>, vararg labels: String): Double =
        find(lines, labels.map { fuzzy(it) }, Kind.AMOUNT)?.toDoubleOrNull() ?: 0.0

    private fun n(lines: List<String>, vararg labels: String): Int =
        find(lines, labels.map { fuzzy(it) }, Kind.INT)?.toIntOrNull() ?: 0

    fun parse(rawText: String, dateString: String): ShiftRecord {
        // "159 , 50" বা "159 .50" -> "159.50" (দশমিকের চারপাশের ফাঁকা সরানো)
        val normalized = rawText.replace("\r", "\n")
            .replace(Regex("(\\d)[ \\t]*([.,])[ \\t]*(\\d)"), "$1$2$3")
        val lines = normalized.split("\n")
            .map { it.trim().lowercase().replace(Regex("\\s+"), "") }
            .filter { it.isNotEmpty() }

        val driver = find(lines, listOf(fuzzy("driverno"), fuzzy("river")), Kind.DRIVER) ?: ""
        val start = find(lines, listOf(fuzzy("starttime"), fuzzy("tarttime"), fuzzy("starttim")), Kind.TIME) ?: ""
        val finish = find(lines, listOf(fuzzy("finishtime"), fuzzy("inishtime"), fuzzy("finishtim")), Kind.TIME) ?: ""

        var shiftTotal = d(lines, "shifttotal", "hifttotal", "ifttotal")
        val totalAmount = d(lines, "totalamount", "otalamount")
        val shiftDiscount = d(lines, "shiftdiscount", "hiftdiscount")
        // Shift Total পড়া না গেলে: Total Amount − Shift Discount
        if (shiftTotal == 0.0 && totalAmount > 0.0) {
            shiftTotal = Math.round((totalAmount - shiftDiscount) * 100.0) / 100.0
        }

        var shiftTrips = n(lines, "shifttrips", "hifttrip", "ifttrips")
        var bookings = n(lines, "bookings", "bookin", "ooking")
        // Bookings কখনো Shift Trips এর বেশি হতে পারে না (OCR ভুল ধরা)
        if (shiftTrips > 0 && bookings > shiftTrips) bookings = 0

        return ShiftRecord(
            dateString = dateString,
            driverNo = driver,
            startTime = start,
            finishTime = finish,
            waitingCharges = d(lines, "waitingcharges", "aitingcharges", "itingcharges"),
            creditCards = d(lines, "creditcards", "reditcard"),
            cash = d(lines, "cash"),
            totalSharjah = d(lines, "sharjah", "harjah"),
            totalTollways = d(lines, "tollways", "tollway", "ollway"),
            totalAmount = totalAmount,
            shiftTotal = shiftTotal,
            shiftTrips = shiftTrips,
            bookings = bookings,
            airportTrips = n(lines, "airporttrips", "irporttrip", "airportt"),
            kmHired = d(lines, "metershired", "shired", "hired"),
            kmVacant = d(lines, "metersvacant", "vacant", "vaca"),
            paidInCareem = d(lines, "paidincareem", "idincareem", "incareem", "paidinc"),
            halaPeak = d(lines, "halapeak", "alapeak", "lapeak"),
            tips = find(lines, listOf(Regex("^.{0,2}" + fuzzy("tips").pattern)), Kind.AMOUNT)
                ?.toDoubleOrNull() ?: 0.0
        )
    }
}
