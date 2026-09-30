package com.harunrashid.targetachieve.models

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
    val generatedAtMillis: Long = 0L
)