package com.harunrashid.targetachieve.models

// রিপোর্টের একদিনের সারি (পুরো মাসের তালিকার জন্য)
data class ReportDay(
    val date: String = "",
    val clean: Double = 0.0,
    val tips: Double = 0.0,
    val avg: Double = 0.0,    // ওই দিন পর্যন্ত দৈনিক গড় Clean Money
    val deposit: Double = 0.0 // ওই দিনের Deposit
)

data class Report(
    val reportId: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val userName: String = "",
    val totalCleanMoney: Double = 0.0,
    val baseSalary: Double = 0.0,
    val extraAdd: Double = 0.0,
    val fuelPenalty: Double = 0.0,
    val finalSalary: Double = 0.0,
    val achievedTargetPercent: Double = 0.0,
    val nextTargetPercent: Double = 0.0,
    val aedNeededPerDayForNextTarget: Double = 0.0,
    val generatedAtMillis: Long = 0L,
    // নতুন (পুরনো রিপোর্টে ফাঁকা থাকে)
    val workingDays: Int = 0,
    val averageClean: Double = 0.0,
    val days: List<ReportDay> = emptyList(),
    // মাসের মোট ট্রিপ (পুরনো রিপোর্টে ০)
    val shiftTrips: Int = 0,
    val bookings: Int = 0,
    val airportTrips: Int = 0,
    // মাসের মোট Deposit (পুরনো রিপোর্টে ০)
    val totalDeposit: Double = 0.0,
    // মাসের মোট Km Hired (পুরনো রিপোর্টে ০)
    val totalKmHired: Double = 0.0,
    // মাসের মোট সাধারণ (Normal) ট্রিপ = Shift Trips − Bookings − Airport (পুরনো রিপোর্টে ০)
    val normalTrips: Int = 0,
    // কোম্পানির জরিমানা/কর্তন (ড্রাইভার হাতে বসায়) ও কারণ; finalSalary এটা বাদ দিয়ে (পুরনো রিপোর্টে ০)
    val penalty: Double = 0.0,
    val penaltyNote: String = ""
)
