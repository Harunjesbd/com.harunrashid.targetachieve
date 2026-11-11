package com.harunrashid.targetachieve.logic

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.harunrashid.targetachieve.models.TaxiType
import com.harunrashid.targetachieve.models.UserProfile
import kotlinx.coroutines.tasks.await

/** একটি Taxi type-এর চারটি রেট (AED) */
data class Rates(
    val fuelPerKm: Double,       // KM Hired Rate (Each)
    val bookingRate: Double,     // Bookings Rate
    val normalTripRate: Double,  // Shift Trips Rate (সাধারণ ট্রিপ)
    val airportTripRate: Double  // Airport Trips Rate
)

/**
 * কোন রেটে হিসাব হবে তা এখান থেকে আসে:
 *  1) Default = Firestore config/rates (থাকলে) নইলে অ্যাপের ভেতরের রেট (AppRules.kt / TaxiType.kt)
 *  2) Custom  = ড্রাইভারের নিজের রেট (Settings এ সেভ করা), না থাকলে Default
 */
object RateProvider {

    private const val PREFS = "rates_cache"

    private var remote: Map<String, Double> = emptyMap()
    private var customMode = false
    private var custom: Map<String, Double> = emptyMap()

    /** অ্যাপের ভেতরে বসানো রেট (AppRules.kt ও TaxiType.kt) */
    fun builtIn(t: TaxiType) = Rates(
        fuelPerKm = t.fuelRatePerKm(),
        bookingRate = AppRules.BOOKING_RATE,
        normalTripRate = t.normalTripRate(),
        airportTripRate = t.airportTripRate()
    )

    /** কোম্পানির Default রেট: Firestore এ থাকলে সেটা, নইলে অ্যাপের ভেতরেরটা */
    fun defaultFor(t: TaxiType): Rates {
        val b = builtIn(t)
        val p = if (t == TaxiType.AIRPORT_TAXI) "airport" else "shift"
        return Rates(
            fuelPerKm = remote["${p}Fuel"] ?: b.fuelPerKm,
            bookingRate = remote["booking"] ?: b.bookingRate,
            normalTripRate = remote["${p}NormalTrip"] ?: b.normalTripRate,
            airportTripRate = remote["${p}AirportTrip"] ?: b.airportTripRate
        )
    }

    /** এখন যে রেটে হিসাব চলছে (Default অথবা Custom) */
    fun activeFor(t: TaxiType): Rates {
        val d = defaultFor(t)
        if (!customMode) return d
        return Rates(
            fuelPerKm = custom["${t.name}_fuel"] ?: d.fuelPerKm,
            bookingRate = custom["${t.name}_booking"] ?: d.bookingRate,
            normalTripRate = custom["${t.name}_normal"] ?: d.normalTripRate,
            airportTripRate = custom["${t.name}_airport"] ?: d.airportTripRate
        )
    }

    fun isCustom(): Boolean = customMode

    /** প্রোফাইল লোড হলে (বা Settings সেভ হলে) এটা ডাকা হয় */
    fun applyProfile(p: UserProfile) {
        customMode = p.rateMode == "CUSTOM"
        custom = p.customRates
    }

    /** Firestore config/rates থেকে কোম্পানির Default রেট আনে; ইন্টারনেট না থাকলে আগের সেভ করা কপি */
    suspend fun refreshRemote(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val doc = FirebaseFirestore.getInstance().collection("config").document("rates").get().await()
            if (doc.exists()) {
                val m = HashMap<String, Double>()
                for ((k, v) in doc.data ?: emptyMap<String, Any>()) {
                    if (v is Number) m[k] = v.toDouble()
                }
                remote = m
                val e = prefs.edit().clear()
                m.forEach { (k, v) -> e.putString(k, v.toString()) }
                e.apply()
            } else {
                remote = emptyMap()
                prefs.edit().clear().apply()
            }
        } catch (e: Exception) {
            val m = HashMap<String, Double>()
            prefs.all.forEach { (k, v) -> (v as? String)?.toDoubleOrNull()?.let { m[k] = it } }
            remote = m
        }
    }
}
