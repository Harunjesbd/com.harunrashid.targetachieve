package com.harunrashid.targetachieve.models

enum class TaxiType {
    SHIFT_TAXI,
    AIRPORT_TAXI;

    fun displayName(): String = when (this) {
        SHIFT_TAXI -> "Shift Taxi"
        AIRPORT_TAXI -> "Airport Taxi"
    }

    fun airportTripRate(): Double = when (this) {
        SHIFT_TAXI -> 15.0
        AIRPORT_TAXI -> 20.0
    }
}