package com.harunrashid.targetachieve.models

data class UserProfile(
    val uid: String = "",
    val mobileKey: String = "",
    val name: String = "",
    val driverId: String = "",
    val mobile: String = "",
    val taxiType: String = TaxiType.SHIFT_TAXI.name,
    val deviceId: String = "",
    val trialStartMillis: Long = 0L,
    val trialExpiryMillis: Long = 0L,
    val subscriptionExpiryMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val profilePicBase64: String = "",

    // ---- রেট সেটিংস ----
    // "DEFAULT" = কোম্পানির রেট, "CUSTOM" = ড্রাইভারের নিজের রেট
    val rateMode: String = "DEFAULT",
    // নিজের রেট, চাবি যেমন: "SHIFT_TAXI_fuel", "SHIFT_TAXI_booking", "SHIFT_TAXI_normal", "SHIFT_TAXI_airport"
    val customRates: Map<String, Double> = emptyMap()
) {
    fun taxiTypeEnum(): TaxiType =
        try { TaxiType.valueOf(taxiType) } catch (_: Exception) { TaxiType.SHIFT_TAXI }
}
