package com.harunrashid.targetachieve

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class AdminActivity : AppCompatActivity() {

    private lateinit var adapter: UserAdapter
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private val allUsers = mutableListOf<UserModel>()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + 12, b.top + 12, b.right + 12, b.bottom + 12)
            insets
        }

        tvCount = findViewById(R.id.tvUserCount)
        etSearch = findViewById(R.id.etSearch)
        val rv = findViewById<RecyclerView>(R.id.recyclerViewUsers)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = UserAdapter(emptyList())
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        db.collection("users").addSnapshotListener { value, error ->
            if (error != null || value == null) {
                Toast.makeText(this, "Failed to load users", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }
            allUsers.clear()
            for (doc in value.documents) {
                allUsers.add(
                    UserModel(
                        id = doc.id,
                        name = doc.getString("name") ?: "-",
                        mobile = doc.getString("mobile") ?: "-",
                        driverId = doc.getString("driverId") ?: "-",
                        trialExpiryMillis = doc.getLong("trialExpiryMillis") ?: 0L,
                        subscriptionExpiryMillis = doc.getLong("subscriptionExpiryMillis") ?: 0L
                    )
                )
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = etSearch.text.toString().trim().lowercase()
        val filtered = if (q.isEmpty()) allUsers else allUsers.filter {
            it.name.lowercase().contains(q) || it.mobile.lowercase().contains(q) ||
                    it.driverId.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
        adapter.submit(filtered)
        tvCount.text = "Total users: ${allUsers.size} | Showing: ${filtered.size}"
    }
}