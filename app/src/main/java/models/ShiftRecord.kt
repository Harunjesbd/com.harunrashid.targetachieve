package com.harunrashid.targetachieve.models

data class ShiftRecord(
    val dateString: String = "",
    val plateNo: String = "",
    val startTime: String = "",
    val finishTime: String = "",

    val waitingCharges: Double = 0.0,
    val creditCards: Double = 0.0,
    val cash: Double = 0.0,
    val totalSharjah: Double = 0.0,
    val totalTollways: Double = 0.0,
    val totalAmount: Double = 0.0,
    val shiftTotal: Double = 0.0,

    val shiftTrips: Int = 0,
    val bookings: Int = 0,
    val airportTrips: Int = 0,

    val kmHired: Double = 0.0,
    val kmVacant: Double = 0.0,

    val paidInCareem: Double = 0.0,
    val halaPeak: Double = 0.0,
    val tips: Double = 0.0,

    val createdAtMillis: Long = 0L
)