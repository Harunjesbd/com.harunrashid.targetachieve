package com.harunrashid.targetachieve

data class UserModel(
    val id: String = "",
    val name: String = "",
    val mobile: String = "",
    val driverId: String = "",
    val trialExpiryMillis: Long = 0L,
    val subscriptionExpiryMillis: Long = 0L
)