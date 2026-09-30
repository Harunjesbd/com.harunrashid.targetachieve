package com.harunrashid.targetachieve.models

// Firestore পাথ: serialKeys/{keyString}  (ডকুমেন্ট আইডি নিজেই key স্ট্রিং, যেমন "TA-4F82K1")
// অ্যাডমিন Firebase Console থেকে ম্যানুয়ালি এই ডকুমেন্ট বানাবে পেমেন্ট কনফার্ম হলে
data class SerialKey(
    val planDays: Int = 30,          // 30 / 180 / 365
    val used: Boolean = false,
    val usedByUid: String = "",
    val usedAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L
)