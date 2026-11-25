package com.harunrashid.targetachieve

data class UserModel(
    val id: String = "",
    val name: String = "",
    val mobile: String = "",
    val driverId: String = "",
    val email: String = "",
    val createdAtMillis: Long = 0L,
    val trialStartMillis: Long = 0L,
    val trialExpiryMillis: Long = 0L,
    val subscriptionExpiryMillis: Long = 0L,
    val lastActiveMillis: Long = 0L
) {
    // অ্যাকাউন্ট খোলার তারিখ (createdAt না থাকলে trialStart)
    fun openedMillis(): Long = if (createdAtMillis > 0) createdAtMillis else trialStartMillis
}
