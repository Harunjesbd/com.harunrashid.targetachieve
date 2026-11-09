package com.harunrashid.targetachieve.models

import com.harunrashid.targetachieve.logic.AppRules

enum class TaxiType {
    SHIFT_TAXI,
    AIRPORT_TAXI;

    fun displayName(): String = when (this) {
        SHIFT_TAXI -> "Shift Taxi"
        AIRPORT_TAXI -> "Airport Taxi"
    }

    // প্রতি Airport trip এর চার্জ (AED) — বদলাতে হলে এখানে সংখ্যা বদলান
    fun airportTripRate(): Double = when (this) {
        SHIFT_TAXI -> 15.0
        AIRPORT_TAXI -> 20.0
    }

    // প্রতি কিমি জ্বালানি (রেট AppRules.kt এ)
    fun fuelRatePerKm(): Double = when (this) {
        SHIFT_TAXI -> AppRules.FUEL_RATE_SHIFT
        AIRPORT_TAXI -> AppRules.FUEL_RATE_AIRPORT
    }

    // প্রতি সাধারণ ট্রিপ (রেট AppRules.kt এ)
    fun normalTripRate(): Double = when (this) {
        SHIFT_TAXI -> AppRules.NORMAL_TRIP_RATE_SHIFT
        AIRPORT_TAXI -> AppRules.NORMAL_TRIP_RATE_AIRPORT
    }
}
