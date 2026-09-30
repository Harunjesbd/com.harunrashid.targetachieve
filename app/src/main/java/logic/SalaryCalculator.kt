package com.harunrashid.targetachieve.logic

import com.harunrashid.targetachieve.models.ShiftRecord
import com.harunrashid.targetachieve.models.TaxiType
import java.text.SimpleDateFormat
import java.util.Locale

// একদিনের হিসাবের ফলাফল
data class DayResult(
    val record: ShiftRecord,
    val cleanMoney: Double,
    val runningTotal: Double,
    val runningAverage: Double,
    val slabPercent: Double,
    val dailyEarning: Double
)

// পুরো পিরিয়ডের (মাসের) সারসংক্ষেপ
data class PeriodSummary(
    val dayResults: List<DayResult>,
    val totalCleanMoney: Double,
    val baseSalary: Double,
    val extraAdd: Double,           // Waiting Charges + Total Sharjah + Tips (যোগফল)
    val fuelPenalty: Double,
    val finalSalary: Double,
    val currentTargetPercent: Double,
    val nextTargetPercent: Double?,
    val aedNeededPerDayForNextTarget: Double?
)

object SalaryCalculator {

    private const val FUEL_RATE_PER_KM = 0.66          // প্রতি কিমি জ্বালানি খরচ (AED)
    private const val BOOKING_RATE = 6.5                // প্রতি বুকিং (AED)
    private const val SHIFT_TRIP_RATE = 2.0             // প্রতি শিফট ট্রিপ (AED)
    private const val FUEL_PENALTY_RATE = 0.50          // km vacant > km hired হলে অতিরিক্ত প্রতি কিমি (AED)

    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

    // একদিনের Clean Money বের করে
    fun cleanMoneyFor(record: ShiftRecord, taxiType: TaxiType): Double {
        val add = record.shiftTotal + record.paidInCareem - record.halaPeak
        val minus = (record.kmHired * FUEL_RATE_PER_KM) +
                record.totalTollways +
                (record.bookings * BOOKING_RATE) +
                (record.shiftTrips * SHIFT_TRIP_RATE) +
                (record.airportTrips * taxiType.airportTripRate())
        return add - minus
    }

    // পুরো পিরিয়ডের (একটা মাসের সব দিনের রেকর্ড, তারিখ অনুযায়ী সাজানো) সম্পূর্ণ হিসাব
    fun calculatePeriod(records: List<ShiftRecord>, taxiType: TaxiType): PeriodSummary {
        // সঠিক তারিখ অনুসারে সাজানো (পুরনো থেকে নতুন)
        val sorted = records.sortedBy {
            try {
                dateFormat.parse(it.dateString)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        var runningTotal = 0.0
        val dayResults = mutableListOf<DayResult>()

        sorted.forEachIndexed { index, record ->
            val clean = cleanMoneyFor(record, taxiType)
            runningTotal += clean
            val dayNumber = index + 1
            val average = runningTotal / dayNumber
            val slabPercent = CommissionSlab.percentFor(average)
            val dailyEarning = clean * (slabPercent / 100.0)

            dayResults.add(
                DayResult(
                    record = record,
                    cleanMoney = clean,
                    runningTotal = runningTotal,
                    runningAverage = average,
                    slabPercent = slabPercent,
                    dailyEarning = dailyEarning
                )
            )
        }

        val totalCleanMoney = dayResults.sumOf { it.cleanMoney }
        val baseSalary = dayResults.sumOf { it.dailyEarning }

        val totalWaiting = sorted.sumOf { it.waitingCharges }
        val totalSharjah = sorted.sumOf { it.totalSharjah }
        val totalTips = sorted.sumOf { it.tips }
        val extraAdd = totalWaiting + totalSharjah + totalTips

        val totalKmHired = sorted.sumOf { it.kmHired }
        val totalKmVacant = sorted.sumOf { it.kmVacant }
        val fuelPenalty = if (totalKmVacant > totalKmHired) {
            (totalKmVacant - totalKmHired) * FUEL_PENALTY_RATE
        } else 0.0

        val finalSalary = baseSalary + extraAdd - fuelPenalty

        val latestAverage = dayResults.lastOrNull()?.runningAverage ?: 0.0
        val currentTargetPercent = CommissionSlab.percentFor(latestAverage)
        val nextSlab = CommissionSlab.nextSlab(latestAverage)

        // পরের স্ল্যাবে পৌঁছাতে দৈনিক গড়ে কত AED বেশি লাগবে (বাকি দিনসংখ্যা দিয়ে ভাগ করে)
        var aedPerDayNeeded: Double? = null
        if (nextSlab != null) {
            val (_, requiredAverage) = nextSlab
            val daysDone = dayResults.size
            val remainingDays = (30 - daysDone).coerceAtLeast(1) // ডিফল্ট ৩০ দিনের পিরিয়ড ধরে আনুমানিক হিসাব
            val currentTotal = runningTotal
            val requiredTotal = requiredAverage * (daysDone + remainingDays)
            val gap = (requiredTotal - currentTotal).coerceAtLeast(0.0)
            aedPerDayNeeded = gap / remainingDays
        }

        return PeriodSummary(
            dayResults = dayResults,
            totalCleanMoney = totalCleanMoney,
            baseSalary = baseSalary,
            extraAdd = extraAdd,
            fuelPenalty = fuelPenalty,
            finalSalary = finalSalary,
            currentTargetPercent = currentTargetPercent,
            nextTargetPercent = nextSlab?.first,
            aedNeededPerDayForNextTarget = aedPerDayNeeded
        )
    }
}