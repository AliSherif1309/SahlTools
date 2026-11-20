package com.sahl.tools

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Focus sessions: open-ended timer per activity, daily goal, streak and statistics. All stored on the phone only. */
object Focus {
    data class Session(val start: Long, val end: Long, val act: String) { val ms get() = end - start }

    fun p(c: Context) = Tools.prefs(c)
    fun running(c: Context) = p(c).getLong("focus_start", 0L)
    fun activity(c: Context) = p(c).getString("focus_act", null) ?: c.getString(R.string.fo_general)
    fun goalMin(c: Context) = p(c).getInt("focus_goal", 120)
    fun acts(c: Context) = (listOf(c.getString(R.string.fo_general)) +
        p(c).getString("focus_acts", "")!!.split('\n').filter { it.isNotBlank() }).distinct()

    fun sessions(c: Context): List<Session> {
        val a = try { JSONArray(p(c).getString("focus_sessions", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Session(o.getLong("s"), o.getLong("e"), o.optString("a")) } }
    }

    private fun save(c: Context, l: List<Session>) = p(c).edit().putString("focus_sessions",
        JSONArray(l.takeLast(3000).map { JSONObject().put("s", it.start).put("e", it.end).put("a", it.act) }).toString()).apply()

    fun start(c: Context) {
        if (running(c) == 0L) p(c).edit().putLong("focus_start", System.currentTimeMillis()).apply()
        notify(c)
    }

    /** Stops and saves the session (sessions under 1 minute are dropped). */
    fun stop(c: Context, keep: Boolean = true) {
        val s = running(c); if (s == 0L) return
        val now = System.currentTimeMillis()
        if (keep && now - s >= 60_000) save(c, sessions(c) + Session(s, now, activity(c)))
        p(c).edit().putLong("focus_start", 0L).apply()
        c.getSystemService(NotificationManager::class.java).cancel(31)
    }

    fun delete(c: Context, s: Session) = save(c, sessions(c).filterNot { it.start == s.start })

    fun dayStart(offset: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -offset)
    }.timeInMillis

    /** Minutes worked on a given day (0 = today), including the running session. */
    fun minutesOn(c: Context, offset: Int): Long {
        val from = dayStart(offset); val to = from + 86_400_000L
        var ms = sessions(c).sumOf { maxOf(0L, minOf(it.end, to) - maxOf(it.start, from)) }
        val r = running(c)
        if (r != 0L) ms += maxOf(0L, minOf(System.currentTimeMillis(), to) - maxOf(r, from))
        return ms / 60_000
    }

    /** Days in a row (ending today or yesterday) where the daily goal was reached. */
    fun streak(c: Context): Int {
        val g = goalMin(c)
        var n = 0
        var d = if (minutesOn(c, 0) >= g) 0 else 1
        while (minutesOn(c, d) >= g && n < 3650) { n++; d++ }
        return n
    }

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
               else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun hm(min: Long) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

    /** A notification with a live timer while a session runs (no background service needed). */
    fun notify(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", c.getString(R.string.fo_t), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 31, Intent(c, FocusActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(31, Notification.Builder(c, "focus").setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(c.getString(R.string.fo_running, activity(c)))
            .setWhen(running(c)).setUsesChronometer(true).setShowWhen(true).setOngoing(true).setContentIntent(open).build())
    }
}

class FocusActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private lateinit var timer: TextView
    private lateinit var goalBar: ProgressBar
    private lateinit var goalText: TextView
    private val tick = object : Runnable { override fun run() { update(); h.postDelayed(this, 1000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.fo_t))
    }

    override fun onResume() { super.onResume(); draw(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        val running = Focus.running(this) != 0L
        if (running && Tools.prefs(this).getBoolean("focus_awake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Timer card
        col.addSpaced(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(if (running) "● " + getString(R.string.fo_now, Focus.activity(this@FocusActivity)) else getString(R.string.fo_ready),
                15f, if (running) R.color.emerald else R.color.sub, true).apply { gravity = Gravity.CENTER })
            timer = label("00:00", 56f, R.color.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
            addView(timer, LinearLayout.LayoutParams(-1, -2))
            val big = label(getString(if (running) R.string.fo_stop else R.string.fo_start), 20f, R.color.card, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = rounded(if (running) R.color.gold else R.color.emerald, 28)
                setOnClickListener {
                    if (running) Focus.stop(this@FocusActivity) else Focus.start(this@FocusActivity)
                    draw()
                }
            }
            addView(big, LinearLayout.LayoutParams(-1, -2))
            if (running) addView(sahlButton(getString(R.string.fo_discard)) { Focus.stop(this@FocusActivity, keep = false); draw() })
            addView(label(getString(R.string.fo_hint), 12f, R.color.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        })

        // Activity picker
        col.addView(label(getString(R.string.fo_activity), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        col.addSpaced(card().apply {
            val current = Focus.activity(this@FocusActivity)
            val wrap = LinearLayout(this@FocusActivity).apply { orientation = LinearLayout.VERTICAL }
            var line = LinearLayout(this@FocusActivity)
            for ((i, a) in Focus.acts(this@FocusActivity).withIndex()) {
                if (i % 3 == 0) { line = LinearLayout(this@FocusActivity); wrap.addView(line) }
                line.addView(label((if (a == current) "● " else "") + a, 14f, if (a == current) R.color.card else R.color.text, a == current).apply {
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(if (a == current) R.color.emerald else R.color.bg, 16)
                    setOnClickListener {
                        // switching activity while running: save what was done so far and continue on the new one
                        if (Focus.running(this@FocusActivity) != 0L && a != current) { Focus.stop(this@FocusActivity); Tools.prefs(this@FocusActivity).edit().putString("focus_act", a).apply(); Focus.start(this@FocusActivity) }
                        else Tools.prefs(this@FocusActivity).edit().putString("focus_act", a).apply()
                        draw()
                    }
                    setOnLongClickListener {
                        if (a != getString(R.string.fo_general)) {
                            val l = Focus.acts(this@FocusActivity).drop(1) - a
                            Tools.prefs(this@FocusActivity).edit().putString("focus_acts", l.joinToString("\n")).apply(); draw()
                        }
                        true
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); bottomMargin = dp(6) })
            }
            addView(wrap)
            val input = EditText(this@FocusActivity).apply { hint = getString(R.string.fo_new_hint); typeface = sahlFont(); setSingleLine() }
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(sahlButton("＋ " + getString(R.string.fo_add)) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    val l = Focus.acts(this@FocusActivity).drop(1) + t
                    Tools.prefs(this@FocusActivity).edit().putString("focus_acts", l.joinToString("\n")).putString("focus_act", t).apply(); draw()
                }
            })
        })

        // Daily goal
        val today = Focus.minutesOn(this, 0)
        val goal = Focus.goalMin(this)
        col.addView(label(getString(R.string.fo_goal_title), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        col.addSpaced(card().apply {
            goalText = label("", 18f, R.color.text, true)
            addView(goalText)
            goalBar = ProgressBar(this@FocusActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; scaleY = 2f }
            addView(goalBar, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(label("🔥 " + getString(R.string.fo_streak, Focus.streak(this@FocusActivity)), 15f))
            addView(sahlButton(getString(R.string.fo_change_goal, Focus.hm(goal.toLong()))) {
                val next = when (goal) { 30 -> 60; 60 -> 90; 90 -> 120; 120 -> 180; 180 -> 240; 240 -> 300; 300 -> 360; 360 -> 480; else -> 30 }
                Tools.prefs(this@FocusActivity).edit().putInt("focus_goal", next).apply(); draw()
            })
            addView(android.widget.Switch(this@FocusActivity).apply {
                text = getString(R.string.fo_awake); typeface = sahlFont(); setTextColor(getColor(R.color.text))
                isChecked = Tools.prefs(this@FocusActivity).getBoolean("focus_awake", false)
                setOnCheckedChangeListener { _, on -> Tools.prefs(this@FocusActivity).edit().putBoolean("focus_awake", on).apply(); draw() }
            })
        })

        // Last 7 days
        col.addView(label(getString(R.string.fo_week), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        val days = (6 downTo 0).map { it to Focus.minutesOn(this, it) }
        val max = maxOf(goal.toLong(), days.maxOf { it.second }, 1L)
        col.addSpaced(card().apply {
            val names = java.text.SimpleDateFormat("EEE", Locale.getDefault())
            for ((off, m) in days) {
                val r = LinearLayout(this@FocusActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
                r.addView(label(if (off == 0) getString(R.string.data_today) else names.format(Focus.dayStart(off)), 13f, R.color.sub),
                    LinearLayout.LayoutParams(dp(56), -2))
                r.addView(View(this@FocusActivity).apply { background = rounded(if (m >= goal) R.color.emerald else R.color.gold, 4) },
                    LinearLayout.LayoutParams(maxOf(dp(3), (dp(190) * m / max).toInt()), dp(12)))
                r.addView(label("  " + Focus.hm(m), 13f))
                addView(r)
            }
            val week = days.sumOf { it.second }
            addView(label(getString(R.string.fo_week_total, Focus.hm(week), Focus.hm(week / 7)), 14f, R.color.text, true).apply { setPadding(0, dp(8), 0, 0) })
        })

        // By activity (last 30 days)
        val from = Focus.dayStart(29)
        val byAct = Focus.sessions(this).filter { it.end > from }.groupBy { it.act }.mapValues { e -> e.value.sumOf { it.ms } / 60_000 }
            .entries.sortedByDescending { it.value }
        if (byAct.isNotEmpty()) {
            col.addView(label(getString(R.string.fo_by_act), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
            col.addSpaced(card().apply {
                val top = byAct.first().value.coerceAtLeast(1)
                for ((a, m) in byAct) {
                    addView(label(a + "  ·  " + Focus.hm(m), 14f, bold = true))
                    addView(View(this@FocusActivity).apply { background = rounded(R.color.emerald, 4) },
                        LinearLayout.LayoutParams(maxOf(dp(3), (dp(260) * m / top).toInt()), dp(8)).apply { topMargin = dp(3); bottomMargin = dp(8) })
                }
            })
        }

        // Recent sessions
        val recent = Focus.sessions(this).takeLast(15).reversed()
        if (recent.isNotEmpty()) {
            col.addView(label(getString(R.string.fo_recent), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
            val f = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            col.addSpaced(card().apply {
                for (s in recent) {
                    val r = LinearLayout(this@FocusActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                    val t = LinearLayout(this@FocusActivity).apply { orientation = LinearLayout.VERTICAL }
                    t.addView(label(s.act + "  ·  " + Focus.hm(s.ms / 60_000), 14f, bold = true))
                    t.addView(label(f.format(java.util.Date(s.start)), 12f, R.color.sub))
                    r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(sahlButton("✕") { Focus.delete(this@FocusActivity, s); draw() })
                    addView(r)
                }
            })
        }
        update()
    }

    private fun update() {
        val r = Focus.running(this)
        timer.text = Focus.fmt(if (r == 0L) 0 else System.currentTimeMillis() - r)
        val today = Focus.minutesOn(this, 0); val goal = Focus.goalMin(this)
        goalBar.progress = (today * 100 / goal.coerceAtLeast(1)).toInt().coerceAtMost(100)
        goalText.text = if (today >= goal) "✓ " + getString(R.string.fo_goal_done, Focus.hm(today))
                        else getString(R.string.fo_goal_left, Focus.hm(today), Focus.hm(goal.toLong()), Focus.hm(goal - today))
    }
}
