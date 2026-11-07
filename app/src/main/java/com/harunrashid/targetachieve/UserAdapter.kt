package com.harunrashid.targetachieve

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class UserAdapter(private var items: List<UserModel>) : RecyclerView.Adapter<UserAdapter.VH>() {

    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvAdminName)
        val mobile: TextView = v.findViewById(R.id.tvAdminMobile)
        val driver: TextView = v.findViewById(R.id.tvAdminDriver)
        val status: TextView = v.findViewById(R.id.tvAdminStatus)
        val expires: TextView = v.findViewById(R.id.tvAdminExpires)
        val add30: Button = v.findViewById(R.id.btnAdd30)
    }

    fun submit(list: List<UserModel>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_user, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = items[position]
        val now = System.currentTimeMillis()
        h.name.text = "👤 ${u.name}"
        h.mobile.text = "📱 ${u.mobile}"
        h.driver.text = "🚕 Driver No: ${u.driverId}   |   ID: ${u.id}"

        val subActive = u.subscriptionExpiryMillis > now
        val trialActive = u.trialExpiryMillis > now
        val (label, expiry) = when {
            subActive -> "PAID" to u.subscriptionExpiryMillis
            trialActive -> "TRIAL" to u.trialExpiryMillis
            else -> "EXPIRED" to maxOf(u.trialExpiryMillis, u.subscriptionExpiryMillis)
        }
        h.status.text = "Status: $label"
        h.status.setTextColor(
            when (label) {
                "PAID" -> 0xFF2E7D32.toInt()
                "TRIAL" -> 0xFF1565C0.toInt()
                else -> 0xFFD32F2F.toInt()
            }
        )
        h.expires.text = "Expires: " + (if (expiry > 0) sdf.format(expiry) else "-")

        h.add30.setOnClickListener {
            val base = maxOf(u.subscriptionExpiryMillis, now)
            val newExpiry = base + 30L * 24 * 60 * 60 * 1000
            FirebaseFirestore.getInstance().collection("users").document(u.id)
                .update("subscriptionExpiryMillis", newExpiry)
                .addOnSuccessListener {
                    Toast.makeText(h.itemView.context, "30 days added ✅", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(h.itemView.context, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}