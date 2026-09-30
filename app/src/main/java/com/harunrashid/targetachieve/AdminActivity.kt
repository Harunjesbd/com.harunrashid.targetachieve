package com.harunrashid.targetachieve

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class AdminActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: UserAdapter
    private val userList = mutableListOf<UserModel>()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.recyclerViewUsers)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = UserAdapter(userList)
        recyclerView.adapter = adapter

        loadUsers()
    }

    private fun loadUsers() {
        db.collection("users").addSnapshotListener { value, error ->
            if (error != null || value == null) {
                Toast.makeText(this, "Failed to load users", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }

            userList.clear()
            for (doc in value.documents) {
                val id = doc.id
                val trial = doc.getLong("trialExpiryMillis") ?: 0L
                val sub = doc.getLong("subscriptionExpiryMillis") ?: 0L

                userList.add(UserModel(id, trial, sub))
            }
            adapter.notifyDataSetChanged()
        }
    }
}