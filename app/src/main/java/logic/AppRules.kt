package com.harunrashid.targetachieve.logic

/**
 * ===============================================================
 *  AppRules.kt  —  কোম্পানির সব নিয়ম (Policy) এক জায়গায়
 * ===============================================================
 *  কোম্পানি নিয়ম বদলালে শুধু এই ফাইলের সংখ্যা বদলান।
 *  (কমিশন স্ল্যাবের টেবিল: CommissionSlab.kt, Airport trip রেট: models/TaxiType.kt)
 */
object AppRules {

    // ---------- জ্বালানি (প্রতি কিমি Km Hired) ----------
    const val FUEL_RATE_SHIFT = 0.70         // Shift Taxi, AED প্রতি কিমি
    const val FUEL_RATE_AIRPORT = 0.85       // Airport Taxi, AED প্রতি কিমি

    // পুরনো নাম (অন্য ফাইল ব্যবহার করলে যেন error না আসে) — Shift Taxi এর রেটই
    const val FUEL_RATE_PER_KM = FUEL_RATE_SHIFT

    // ---------- Booking ----------
    // শুধু Shift Taxi এর জন্য। Airport Taxi তে Booking গোনা হয় না (Airport Trips এর সাথে যোগ হয়)
    const val BOOKING_RATE = 6.5

    // ---------- সাধারণ ট্রিপ (বুকিং/এয়ারপোর্ট বাদে বাকি ট্রিপ) ----------
    const val NORMAL_TRIP_RATE_SHIFT = 2.0   // Shift Taxi, প্রতি ট্রিপ AED
    const val NORMAL_TRIP_RATE_AIRPORT = 3.0 // Airport Taxi, প্রতি ট্রিপ AED

    // পুরনো নাম (Shift Taxi এর রেট)
    const val NORMAL_TRIP_RATE = NORMAL_TRIP_RATE_SHIFT

    // Airport trip এর রেট আছে models/TaxiType.kt ফাইলে:
    //   SHIFT_TAXI -> 15.0   AIRPORT_TAXI -> 20.0

    // ---------- জ্বালানি জরিমানা ----------
    // 0.0 মানে: জরিমানা নেই
    const val FUEL_PENALTY_RATE = 0.0

    // ---------- বেতন হিসাব ----------
    // true  = দৈনিক অংশ (গড় × স্ল্যাব%) পূর্ণ সংখ্যায় রাউন্ড হবে, তারপর × দিন
    const val ROUND_DAILY_PART = true

    // ---------- "পরের টার্গেটে পৌঁছাতে দৈনিক কত লাগবে" ----------
    // 1.0 = প্রতিদিন কাজ (ছুটি নেই); 0.85 = সপ্তাহে প্রায় ১ দিন ছুটি
    const val EXPECTED_WORK_RATIO = 1.0

    // ---------- পিরিয়ড ----------
    const val PERIOD_START_DAY = 21          // প্রতি মাসের ২১ তারিখ থেকে শুরু (২০ তারিখে শেষ)
}
