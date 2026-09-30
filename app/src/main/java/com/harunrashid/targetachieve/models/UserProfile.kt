package com.harunrashid.targetachieve.models

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val driverId: String = "",
    val mobile: String = "",
    val taxiType: String = TaxiType.SHIFT_TAXI.name,
    val deviceId: String = "",
    val trialStartMillis: Long = 0L,
    val trialExpiryMillis: Long = 0L,
    val subscriptionExpiryMillis: Long = 0L,
    val createdAtMillis: Long = 0L
) {
    fun taxiTypeEnum(): TaxiType =
        try {
            TaxiType.valueOf(taxiType)
        } catch (_: Exception) {
            TaxiType.SHIFT_TAXI
        }
}
