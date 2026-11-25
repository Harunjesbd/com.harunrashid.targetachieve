package com.harunrashid.targetachieve.logic

import com.harunrashid.targetachieve.models.ShiftRecord
import com.harunrashid.targetachieve.models.TaxiType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

// একদিনের হিসাবের ফলাফল
data class DayResult(
    val record: ShiftRecord,
    val cleanMoney: Double,
    val runningTotal: Double,
    val runningAverage: Double,
    val slabPercent: Double,
    val dailyEarning: Double,
    val deposit: Double = 0.0     // ওই দিনের Deposit = Shift Total − Tips + Paid in Careem − Hala Peak
)

// পুরো পিরিয়ডের সারসংক্ষেপ
data class PeriodSummary(
    val dayResults: List<DayResult>,
    val totalCleanMoney: Double,
    val baseSalary: Double,
    val extraAdd: Double,           // শুধু Tips
    val fuelPenalty: Double,
    val finalSalary: Double,
    val currentTargetPercent: Double,
    val nextTargetPercent: Double?,
    val aedNeededPerDayForNextTarget: Double?,
    val remainingWorkDays: Int = 0,  // হিসাবে ধরা বাকি কাজের দিন
    val averageClean: Double = 0.0,  // দৈনিক গড় Clean Money (মোট Clean ÷ কাজের দিন)
    val workingDays: Int = 0,        // কত দিন কাজ হয়েছে
    val totalDeposit: Double = 0.0   // পিরিয়ডের মোট Deposit
)

object SalaryCalculator {

    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

    /**
     * একদিনের Clean Money
     *  Add   = Shift Total + Paid in Careem − Hala Peak
     *  Minus = KmHired×জ্বালানি রেট + Tollways + Bookings×রেট + সাধারণ ট্রিপ×রেট + AirportTrips×রেট (Taxi type অনুযায়ী)
     *  Clean = Add − Minus
     */
    // প্রতিদিনের নিজের Taxi type ব্যবহার হয়; দিনে type লেখা না থাকলে fallback (বর্তমান type) ধরা হয়
    fun typeFor(record: ShiftRecord, fallback: TaxiType): TaxiType =
        try { TaxiType.valueOf(record.taxiType) } catch (e: Exception) { fallback }

    // Deposit = Shift Total − Tips + Paid in Careem − Hala Peak  (Clean Money-র "Add" অংশ, খরচ বাদ দেওয়ার আগে)
    fun depositFor(record: ShiftRecord): Double =
        (record.shiftTotal - record.tips.coerceAtLeast(0.0) + record.paidInCareem - record.halaPeak).coerceAtLeast(0.0)

    // রসিদের আসল Shift Trips (মোট)। মাঝের কিছুদিনে সেভ হওয়া tripsNet রেকর্ডে Bookings আবার যোগ করে ফেরানো হয়
    fun rawTripsOf(record: ShiftRecord): Int =
        if (record.tripsNet && record.taxiType != TaxiType.AIRPORT_TAXI.name) record.shiftTrips + record.bookings
        else record.shiftTrips

    // নিয়ম: Shift Trips = Bookings + Airport + Normal  ⇒  Normal = Shift Trips − Bookings − Airport (০-এর নিচে নামে না)
    fun shiftNormalTrips(record: ShiftRecord): Int =
        (rawTripsOf(record) - record.bookings - record.airportTrips).coerceAtLeast(0)

    // সাধারণ ট্রিপ: Airport Taxi-তে Shift Trips ঘরই সাধারণ ট্রিপ (Airport ট্রিপ আলাদা ঘরে)
    fun normalTripsOf(record: ShiftRecord): Int =
        if (record.taxiType == TaxiType.AIRPORT_TAXI.name) record.shiftTrips.coerceAtLeast(0)
        else shiftNormalTrips(record)

    // রিপোর্টের জন্য মোট ট্রিপ: Airport Taxi-তে সাধারণ + Airport ট্রিপ
    fun totalTripsOf(record: ShiftRecord): Int =
        if (record.taxiType == TaxiType.AIRPORT_TAXI.name) record.shiftTrips + record.airportTrips
        else rawTripsOf(record)

    fun cleanMoneyFor(record: ShiftRecord, fallbackType: TaxiType): Double {
        val taxiType = typeFor(record, fallbackType)
        // Shift Total-এর ভেতরে Tips থাকে; Tips সরাসরি বেতনে আলাদা যোগ হয়, তাই Clean Money থেকে বাদ
        val add = (record.shiftTotal - record.tips.coerceAtLeast(0.0) + record.paidInCareem - record.halaPeak).coerceAtLeast(0.0)
        val isAirport = taxiType == TaxiType.AIRPORT_TAXI

        val bookingsCharged: Int
        val airportTrips: Int
        val normalTrips: Int
        if (isAirport) {
            // Airport Taxi: Bookings সবসময় ০ (চার্জ নেই)।
            // "Shift Trips" ঘর = সাধারণ ৩ AED ট্রিপ, "Airport Trips" ঘর = ২০ AED ট্রিপ (ড্রাইভার ঠিক করে দেয়)
            bookingsCharged = 0
            normalTrips = record.shiftTrips.coerceAtLeast(0)
            airportTrips = record.airportTrips.coerceAtLeast(0)
        } else {
            // Shift Taxi: সাধারণ ট্রিপ = Shift Trips − Bookings; Airport ট্রিপ রসিদ থেকে
            bookingsCharged = record.bookings
            normalTrips = shiftNormalTrips(record)
            airportTrips = record.airportTrips
        }

        // রেট: Default (কোম্পানি) অথবা Custom (ড্রাইভারের নিজের), Settings অনুযায়ী
        val rates = RateProvider.activeFor(taxiType)
        val minus = (record.kmHired * rates.fuelPerKm) +
                record.totalTollways +
                (bookingsCharged * rates.bookingRate) +
                (normalTrips * rates.normalTripRate) +
                (airportTrips * rates.airportTripRate)
        // আয় ছাড়া কর্তন হয় না: Clean Money কখনো মাইনাস হবে না
        return (add - minus).coerceAtLeast(0.0)
    }

    // আজ থেকে পিরিয়ড শেষ (২০ তারিখ) পর্যন্ত বাকি দিন (আজকে ধরে)
    private fun remainingCalendarDays(): Int {
        val today = Calendar.getInstance()
        val end = Calendar.getInstance()
        if (today.get(Calendar.DAY_OF_MONTH) >= AppRules.PERIOD_START_DAY) end.add(Calendar.MONTH, 1)
        end.set(Calendar.DAY_OF_MONTH, AppRules.PERIOD_START_DAY - 1)
        for (c in listOf(today, end)) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        }
        val diff = ((end.timeInMillis - today.timeInMillis) / 86_400_000L).toInt() + 1
        return diff.coerceAtLeast(1)
    }

    fun calculatePeriod(records: List<ShiftRecord>, taxiType: TaxiType): PeriodSummary {
        val sorted = records.sortedBy {
            try { dateFormat.parse(it.dateString)?.time ?: 0L } catch (e: Exception) { 0L }
        }

        var runningTotal = 0.0
        val dayResults = mutableListOf<DayResult>()

        sorted.forEachIndexed { index, record ->
            val clean = cleanMoneyFor(record, taxiType)
            runningTotal += clean
            val average = runningTotal / (index + 1)
            val slabPercent = CommissionSlab.percentFor(average)
            dayResults.add(
                DayResult(
                    record = record,
                    cleanMoney = clean,
                    runningTotal = runningTotal,
                    runningAverage = average,
                    slabPercent = slabPercent,
                    dailyEarning = clean * (slabPercent / 100.0),
                    deposit = depositFor(record)
                )
            )
        }

        val days = dayResults.size
        val totalCleanMoney = runningTotal

        // পুরো পিরিয়ডের হিসাব: (মোট Clean ÷ দিন) × স্ল্যাব% = দৈনিক অংশ; দৈনিক অংশ × দিন = বেতন
        val averageAll = if (days > 0) totalCleanMoney / days else 0.0
        val slabAll = CommissionSlab.percentFor(averageAll)
        var perDay = averageAll * slabAll / 100.0
        if (AppRules.ROUND_DAILY_PART) perDay = perDay.roundToLong().toDouble()
        val baseSalary = perDay * days

        val extraAdd = sorted.sumOf { it.tips }

        val totalKmHired = sorted.sumOf { it.kmHired }
        val totalKmVacant = sorted.sumOf { it.kmVacant }
        val fuelPenalty = if (totalKmVacant > totalKmHired) {
            (totalKmVacant - totalKmHired) * AppRules.FUEL_PENALTY_RATE
        } else 0.0

        val finalSalary = baseSalary + extraAdd - fuelPenalty

        // পরের স্ল্যাবে (গড় >= সর্বনিম্ন) পৌঁছাতে বাকি কাজের দিনগুলোয় দৈনিক কত Clean Money লাগবে:
        //   (পরের স্ল্যাবের গড় × (এ পর্যন্ত দিন + বাকি দিন) − এ পর্যন্ত মোট) ÷ বাকি দিন
        val nextSlab = CommissionSlab.nextSlab(averageAll)
        var aedPerDayNeeded: Double? = null
        val remainingWork = Math.max(1, Math.round(remainingCalendarDays() * AppRules.EXPECTED_WORK_RATIO).toInt())
        if (nextSlab != null && days > 0) {
            val requiredAverage = nextSlab.second
            val requiredTotal = requiredAverage * (days + remainingWork)
            aedPerDayNeeded = (requiredTotal - totalCleanMoney).coerceAtLeast(0.0) / remainingWork
        }

        return PeriodSummary(
            dayResults = dayResults,
            totalCleanMoney = totalCleanMoney,
            baseSalary = baseSalary,
            extraAdd = extraAdd,
            fuelPenalty = fuelPenalty,
            finalSalary = finalSalary,
            currentTargetPercent = slabAll,
            nextTargetPercent = nextSlab?.first,
            aedNeededPerDayForNextTarget = aedPerDayNeeded,
            remainingWorkDays = remainingWork,
            averageClean = averageAll,
            workingDays = days,
            totalDeposit = dayResults.sumOf { it.deposit }
        )
    }
}