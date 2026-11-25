package com.harunrashid.targetachieve

import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class UserAdapter(private var items: List<UserModel>) : RecyclerView.Adapter<UserAdapter.VH>() {

    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
    private val DAY = 24L * 60 * 60 * 1000

    // সার্ভারের সময় (AdminActivity থেকে দেওয়া হয়)
    var nowMillis: Long = System.currentTimeMillis()

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvAdminName)
        val mobile: TextView = v.findViewById(R.id.tvAdminMobile)
        val email: TextView = v.findViewById(R.id.tvAdminEmail)
        val driver: TextView = v.findViewById(R.id.tvAdminDriver)
        val opened: TextView = v.findViewById(R.id.tvAdminOpened)
        val lastSeen: TextView = v.findViewById(R.id.tvAdminLastSeen)
        val status: TextView = v.findViewById(R.id.tvAdminStatus)
        val expires: TextView = v.findViewById(R.id.tvAdminExpires)
        val add30: Button = v.findViewById(R.id.btnAdd30)
        val addCustom: Button = v.findViewById(R.id.btnAddCustom)
    }

    fun submit(list: List<UserModel>, now: Long) {
        items = list
        nowMillis = now
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_user, parent, false))

    override fun getItemCount() = items.size

    private fun statusOf(u: UserModel, now: Long): String = when {
        u.subscriptionExpiryMillis > now -> "PAID"
        u.trialExpiryMillis > now -> "TRIAL"
        else -> "EXPIRED"
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = items[position]
        val now = nowMillis

        h.name.text = "👤 ${u.name}"
        h.mobile.text = "📱 ${u.mobile}"
        h.email.text = "✉️ " + (if (u.email.isBlank()) "—" else u.email)
        h.driver.text = "🚕 Driver No: ${u.driverId}   |   ID: ${u.id}"
        val opened = u.openedMillis()
        h.opened.text = "📅 Opened: " + (if (opened > 0) sdf.format(opened) else "—")

        // শেষ কবে অ্যাপ খুলেছে
        when {
            u.lastActiveMillis <= 0L -> {
                h.lastSeen.text = "⚪ Last seen: not tracked yet"
                h.lastSeen.setTextColor(0xFF9E9E9E.toInt())
            }
            AdminActivity.isActive(u, now) -> {
                val d = (now - u.lastActiveMillis) / DAY
                h.lastSeen.text = "🟢 Active" + (if (d <= 0L) " (today)" else " ($d days ago)")
                h.lastSeen.setTextColor(0xFF4CAF50.toInt())
            }
            else -> {
                val d = (now - u.lastActiveMillis) / DAY
                h.lastSeen.text = "🔴 Inactive: $d days (last " + sdf.format(u.lastActiveMillis) + ")"
                h.lastSeen.setTextColor(0xFFEF5350.toInt())
            }
        }

        val label = statusOf(u, now)
        val expiry = when (label) {
            "PAID" -> u.subscriptionExpiryMillis
            "TRIAL" -> u.trialExpiryMillis
            else -> maxOf(u.trialExpiryMillis, u.subscriptionExpiryMillis)
        }
        val daysLeft = if (expiry > now) ((expiry - now + DAY - 1) / DAY) else 0L

        h.status.text = "Status: $label   |   Days left: $daysLeft"
        h.status.setTextColor(
            when (label) {
                "PAID" -> 0xFF4CAF50.toInt()
                "TRIAL" -> 0xFF64B5F6.toInt()
                else -> 0xFFEF5350.toInt()
            }
        )
        h.expires.text = "Expires: " + (if (expiry > 0) sdf.format(expiry) else "-")

        h.add30.setOnClickListener { extend(h, u, 30) }
        h.addCustom.setOnClickListener {
            val input = EditText(h.itemView.context).apply {
                hint = "Days (e.g. 90)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(h.itemView.context)
                .setTitle("Add days to ${u.name}")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val d = input.text.toString().trim().toIntOrNull()
                    if (d == null || d < 1 || d > 730) {
                        Toast.makeText(h.itemView.context, "1 থেকে 730 এর মধ্যে সংখ্যা দিন", Toast.LENGTH_SHORT).show()
                    } else extend(h, u, d)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // TRIAL ব্যবহারকারীর ট্রায়াল বাড়ে; PAID/EXPIRED ব্যবহারকারীর সাবস্ক্রিপশন বাড়ে
    private fun extend(h: VH, u: UserModel, days: Int) {
        val now = nowMillis
        val update: Pair<String, Long> = if (statusOf(u, now) == "TRIAL") {
            "trialExpiryMillis" to (u.trialExpiryMillis + days * DAY)
        } else {
            "subscriptionExpiryMillis" to (maxOf(u.subscriptionExpiryMillis, now) + days * DAY)
        }
        FirebaseFirestore.getInstance().collection("users").document(u.id)
            .update(update.first, update.second)
            .addOnSuccessListener {
                Toast.makeText(h.itemView.context, "$days days added ✅", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(h.itemView.context, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}
