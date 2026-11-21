package com.sahl.tools

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sahl Search — one box that searches every Sahl app through Sahl Union:
 * people (Sahl Contacts), calls and call notes (Sahl Phone), message text (Sahl Messages), files (Sahl Files).
 */
class SearchActivity : SahlActivity() {
    private data class Hit(val kind: String, val title: String, val sub: String, val date: Long, val action: String, val key: String, val value: String, val pkg: String)
    private data class Source(val authority: String, val label: Int, val icon: Int)

    private val sources = listOf(
        Source("com.sahl.contacts.union", R.string.s_people, R.drawable.ic_people),
        Source("com.sahl.messages.union", R.string.s_messages, R.drawable.ic_chat),
        Source("com.sahl.phone.union", R.string.s_calls, R.drawable.ic_call),
        Source("com.sahl.files.union", R.string.s_files, R.drawable.ic_note))

    private val main = Handler(Looper.getMainLooper())
    private lateinit var results: LinearLayout
    private lateinit var hint: TextView
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.s_title), false))
        val (box, et) = searchBox(getString(R.string.s_hint)) { q -> main.removeCallbacksAndMessages(null); main.postDelayed({ run(q) }, 250) }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(8)) })
        hint = text(getString(R.string.s_intro), 14f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(px(24), px(40), px(24), 0) }
        root.addView(hint)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, px(14), px(30)) }
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        et.requestFocus()
        et.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(et, 0) }, 150)
        intent.getStringExtra("q")?.let { et.setText(it) }
    }

    private fun run(q: String) {
        val gen = ++generation
        results.removeAllViews()
        if (q.trim().length < 2) { hint.visibility = View.VISIBLE; return }
        hint.visibility = View.GONE
        Thread {
            val all = sources.map { s -> s to query(s.authority, q) }.filter { it.second.isNotEmpty() }
            runOnUiThread { if (gen == generation) show(all) }
        }.start()
    }

    private fun query(authority: String, q: String): List<Hit> {
        val out = ArrayList<Hit>()
        try {
            contentResolver.query(Uri.parse("content://$authority/search?q=" + Uri.encode(q.trim())), null, null, null, null)?.use { c: Cursor ->
                while (c.moveToNext()) out.add(Hit(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3),
                    c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: ""))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun show(groups: List<Pair<Source, List<Hit>>>) {
        results.removeAllViews()
        if (groups.isEmpty()) {
            results.addView(text(getString(R.string.s_none), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(40), 0, 0) },
                LinearLayout.LayoutParams(-1, -2))
            return
        }
        for ((src, hits) in groups) {
            results.addView(label(getString(src.label) + "  ·  " + hits.size).apply { setPaddingRelative(px(6), px(14), 0, px(4)) })
            val c = card()
            for ((i, h) in hits.withIndex()) {
                if (i > 0) c.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = px(36) })
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(px(2), px(10), px(2), px(10))
                    setOnClickListener { open(h) }
                }.press(12f)
                row.addView(icon(src.icon, col(R.color.emerald), 20))
                val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
                t.addView(text(h.title, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (h.sub.isNotBlank()) t.addView(text(h.sub, 13f, col(R.color.sub)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                if (h.date > 0) row.addView(text(stamp(h.date), 12f, col(R.color.sub)))
                c.addView(row)
            }
            results.addView(c, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun stamp(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
        val loc = resources.configuration.locales[0]
        return if (d == LocalDate.now()) Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", loc))
        else d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d MMM" else "d MMM yyyy", loc))
    }

    private fun open(h: Hit) {
        try {
            startActivity(Intent(h.action).setPackage(h.pkg).putExtra(h.key, h.value)
                .apply { if (h.key == "id") h.value.toLongOrNull()?.let { putExtra("id", it) } })
        } catch (e: Exception) {
            try { packageManager.getLaunchIntentForPackage(h.pkg)?.let { startActivity(it) } } catch (x: Exception) { }
        }
    }
}

/** Quick Settings tile: open Sahl Search from anywhere. */
class SearchTile : TileService() {
    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}
