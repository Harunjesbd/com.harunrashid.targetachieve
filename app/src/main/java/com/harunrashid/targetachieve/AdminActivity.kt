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
import androidx.lifecycle.lifecycleScope
import com.harunrashid.targetachieve.logic.ServerTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AdminActivity : AppCompatActivity() {

    companion object {
        // কত দিনের মধ্যে অ্যাপ খুললে "Active" ধরা হবে (বদলাতে চাইলে এই সংখ্যা বদলান)
        const val ACTIVE_DAYS = 7L

        fun isActive(u: UserModel, now: Long): Boolean =
            u.lastActiveMillis > 0L && (now - u.lastActiveMillis) <= ACTIVE_DAYS * 24L * 60 * 60 * 1000
    }

    // 0 = All, 1 = Active, 2 = Inactive
    private var filterMode = 0

    private lateinit var adapter: UserAdapter
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private val allUsers = mutableListOf<UserModel>()
    private var serverNow: Long = System.currentTimeMillis()
    private var freeUntil: Long = 0L
    private val db = FirebaseFirestore.getInstance()

    // অ্যাডমিন লগ-ইনের ইমেইল (Firebase Authentication এ এই ইমেইলে অ্যাকাউন্ট থাকতে হবে)
    private val adminEmail = "admininfo.bd@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // নিরাপত্তা: অ্যাডমিন পাসওয়ার্ড দিয়ে লগ-ইন না করলে এই স্ক্রিন বন্ধ
        val email = FirebaseAuth.getInstance().currentUser?.email
        if (email == null || !email.equals(adminEmail, ignoreCase = true)) {
            Toast.makeText(this, "Access Denied", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + 12, b.top + 12, b.right + 12, b.bottom + 12)
            insets
        }

        tvCount = findViewById(R.id.tvUserCount)
        etSearch = findViewById(R.id.etSearch)
        val btnAll = findViewById<android.widget.Button>(R.id.btnFilterAll)
        val btnActive = findViewById<android.widget.Button>(R.id.btnFilterActive)
        val btnInactive = findViewById<android.widget.Button>(R.id.btnFilterInactive)
        fun setMode(m: Int) {
            filterMode = m
            btnAll.alpha = if (m == 0) 1f else 0.45f
            btnActive.alpha = if (m == 1) 1f else 0.45f
            btnInactive.alpha = if (m == 2) 1f else 0.45f
            applyFilter()
        }
        btnAll.setOnClickListener { setMode(0) }
        btnActive.setOnClickListener { setMode(1) }
        btnInactive.setOnClickListener { setMode(2) }
        btnAll.alpha = 1f; btnActive.alpha = 0.45f; btnInactive.alpha = 0.45f
        findViewById<android.widget.Button>(R.id.btnExport).setOnClickListener { showExportDialog() }
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
                        email = doc.getString("email") ?: "",
                        createdAtMillis = doc.getLong("createdAtMillis") ?: 0L,
                        trialStartMillis = doc.getLong("trialStartMillis") ?: 0L,
                        trialExpiryMillis = doc.getLong("trialExpiryMillis") ?: 0L,
                        subscriptionExpiryMillis = doc.getLong("subscriptionExpiryMillis") ?: 0L,
                        lastActiveMillis = doc.getLong("lastActiveMillis") ?: 0L
                    )
                )
            }
            // সবচেয়ে নতুন অ্যাকাউন্ট আগে
            allUsers.sortByDescending { it.openedMillis() }
            lifecycleScope.launch {
                serverNow = ServerTime.now(this@AdminActivity)
                freeUntil = try {
                    db.collection("config").document("app").get().await()
                        .getTimestamp("freeUntil")?.toDate()?.time ?: 0L
                } catch (e: Exception) { 0L }
                applyFilter()
            }
        }
    }

    // ---------- Export Contacts ----------
    private fun showExportDialog() {
        if (allUsers.isEmpty()) {
            Toast.makeText(this, "No users loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf(
            "📋 Copy mobile numbers only",
            "📋 Copy Name + Mobile + Email",
            "📄 Share as CSV file (Excel / Google Contacts)"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export ${allUsers.size} users")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyText(numbersOnly(), "Mobile numbers copied ✅")
                    1 -> copyText(fullList(), "Name + Mobile + Email copied ✅")
                    2 -> shareCsv()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sortedUsers() = allUsers.sortedBy { it.name.lowercase() }
    private fun cleanMobile(m: String) = m.replace(" ", "").replace("-", "")

    private fun numbersOnly(): String =
        sortedUsers().map { cleanMobile(it.mobile) }.filter { it.isNotBlank() && it != "-" }
            .distinct().joinToString("\n")

    private fun fullList(): String =
        sortedUsers().joinToString("\n") {
            "${it.name} | ${cleanMobile(it.mobile)} | ${if (it.email.isBlank()) "-" else it.email}"
        }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("contacts", text))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun csvCell(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

    private fun shareCsv() {
        try {
            val sb = StringBuilder("\uFEFFName,Driver No,Mobile,Email\n")
            for (u in sortedUsers()) {
                sb.append(csvCell(u.name)).append(',')
                    .append(csvCell(u.driverId)).append(',')
                    .append(csvCell(cleanMobile(u.mobile))).append(',')
                    .append(csvCell(u.email)).append('\n')
            }
            val dir = java.io.File(cacheDir, "reports").apply { mkdirs() }
            val file = java.io.File(dir, "TargetAchieve_Contacts.csv")
            file.writeText(sb.toString(), Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share contacts CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyFilter() {
        val q = etSearch.text.toString().trim().lowercase()
        val searched = if (q.isEmpty()) allUsers else allUsers.filter {
            it.name.lowercase().contains(q) || it.mobile.lowercase().contains(q) ||
                    it.driverId.lowercase().contains(q) || it.id.lowercase().contains(q) ||
                    it.email.lowercase().contains(q)
        }
        val filtered = when (filterMode) {
            1 -> searched.filter { isActive(it, serverNow) }
            // Inactive: ট্র্যাক হয়েছে কিন্তু ৭ দিনের বেশি খোলেনি (সবচেয়ে বেশি দিন বন্ধ আগে)
            2 -> searched.filter { it.lastActiveMillis > 0L && !isActive(it, serverNow) }
                .sortedBy { it.lastActiveMillis }
            else -> searched
        }
        adapter.submit(filtered, serverNow)
        val activeCount = allUsers.count { isActive(it, serverNow) }
        val untracked = allUsers.count { it.lastActiveMillis <= 0L }
        val inactiveCount = allUsers.size - activeCount - untracked
        var text = "Total users: ${allUsers.size} | Showing: ${filtered.size}\n" +
                "🟢 Active: $activeCount   🔴 Inactive: $inactiveCount   ⚪ Not tracked: $untracked"
        if (freeUntil > serverNow) {
            text += "\n🎁 Free for ALL users until " +
                    java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.ENGLISH).format(freeUntil)
        }
        tvCount.text = text
    }
}
