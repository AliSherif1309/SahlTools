package com.sahl.tools

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

// ───────────────────────── Speed test ─────────────────────────
/** Measures ping, download and upload against Cloudflare's nearest server (4 parallel streams, like big speed tests). */
class SpeedActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var pingV: TextView
    private lateinit var downV: TextView
    private lateinit var upV: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var go: TextView
    @Volatile private var running = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = sahlScreen(getString(R.string.speed_t))
        fun big(title: Int): TextView {
            val v = label("—", 34f, R.color.emerald, true)
            col.addSpaced(card().apply { addView(label(getString(title), 14f, R.color.sub)); addView(v) })
            return v
        }
        pingV = big(R.string.speed_ping); downV = big(R.string.speed_down); upV = big(R.string.speed_up)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        status = label(getString(R.string.speed_hint, network()), 14f, R.color.sub).apply { setPadding(0, dp(6), 0, dp(10)) }
        col.addView(status)
        go = label(getString(R.string.speed_start), 18f, R.color.card, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = rounded(R.color.emerald, 28)
            setOnClickListener { if (!running) start() }
        }
        col.addView(go, LinearLayout.LayoutParams(-1, -2))
    }

    private fun network(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return getString(R.string.speed_offline)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.speed_mobile)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> getString(R.string.speed_other)
        }
    }

    private fun ui(f: () -> Unit) = h.post(f)

    private fun start() {
        running = true; go.alpha = 0.5f
        pingV.text = "…"; downV.text = "—"; upV.text = "—"
        Thread {
            try {
                // 1. Ping: time for tiny requests (first one only warms up the connection)
                ui { status.text = getString(R.string.speed_testing_ping) }
                val times = ArrayList<Long>()
                for (i in 0..8) {
                    val t0 = System.nanoTime()
                    (URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000; readTimeout = 5000; useCaches = false
                        inputStream.use { it.readBytes() }; disconnect()
                    }
                    if (i > 0) times.add((System.nanoTime() - t0) / 1_000_000)
                    ui { bar.progress = i * 2 }
                }
                val ping = times.sorted().take(5).average()
                val jitter = times.zipWithNext { a, b -> abs(a - b) }.average()
                ui { pingV.text = String.format(Locale.US, "%.0f ms", ping); status.text = getString(R.string.speed_jitter, jitter.toInt()) }

                // 2. Download for 10 s
                val down = measure(10_000, 18, 60) { counter, stop ->
                    while (!stop()) {
                        val con = URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection() as HttpURLConnection
                        con.connectTimeout = 8000; con.readTimeout = 8000; con.useCaches = false
                        con.inputStream.use { inp ->
                            val buf = ByteArray(64 * 1024)
                            while (!stop()) { val n = inp.read(buf); if (n < 0) break; counter.addAndGet(n.toLong()) }
                        }
                        con.disconnect()
                    }
                } ?: return@Thread
                ui { downV.text = mbps(down) }

                // 3. Upload for 8 s
                val chunk = ByteArray(2 * 1024 * 1024).also { java.util.Random().nextBytes(it) }
                val up = measure(8_000, 60, 100) { counter, stop ->
                    while (!stop()) {
                        val con = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                        con.doOutput = true; con.requestMethod = "POST"; con.connectTimeout = 8000; con.readTimeout = 8000
                        con.setFixedLengthStreamingMode(chunk.size)
                        con.setRequestProperty("Content-Type", "application/octet-stream")
                        con.outputStream.use { out ->
                            var off = 0
                            while (off < chunk.size && !stop()) {
                                val n = minOf(64 * 1024, chunk.size - off); out.write(chunk, off, n); off += n; counter.addAndGet(n.toLong())
                            }
                        }
                        try { con.inputStream.use { it.readBytes() } } catch (_: Exception) { }
                        con.disconnect()
                    }
                } ?: return@Thread
                ui {
                    upV.text = mbps(up); bar.progress = 100
                    status.text = getString(R.string.speed_done, network())
                }
            } catch (e: Exception) {
                ui { status.text = getString(R.string.speed_error) }
            } finally {
                running = false; ui { go.alpha = 1f; go.text = getString(R.string.speed_again) }
            }
        }.start()
    }

    private fun mbps(bitsPerSec: Double) = String.format(Locale.US, "%.1f Mbps", bitsPerSec / 1_000_000)

    /** Runs 4 streams for [ms], showing the live speed; returns bits per second (ignoring the first second's ramp-up). */
    private fun measure(ms: Long, p0: Int, p1: Int, work: (AtomicLong, () -> Boolean) -> Unit): Double? {
        val bytes = AtomicLong()
        val start = System.currentTimeMillis()
        val stop = { System.currentTimeMillis() - start > ms }
        val threads = (1..4).map { Thread { try { work(bytes, stop) } catch (_: Exception) { } }.apply { start() } }
        var warm = 0L; var warmAt = 0L
        val target = if (p1 == 60) downV else upV
        while (threads.any { it.isAlive }) {
            Thread.sleep(250)
            val t = System.currentTimeMillis() - start
            if (warmAt == 0L && t >= 1000) { warm = bytes.get(); warmAt = t }
            val live = if (warmAt > 0 && t > warmAt) (bytes.get() - warm) * 8.0 / ((t - warmAt) / 1000.0) else bytes.get() * 8.0 / (t / 1000.0)
            ui { target.text = mbps(live); bar.progress = (p0 + (p1 - p0) * minOf(t, ms) / ms).toInt() }
        }
        val total = System.currentTimeMillis() - start
        if (bytes.get() == 0L) return null
        return if (warmAt > 0 && total > warmAt) (bytes.get() - warm) * 8.0 / ((total - warmAt) / 1000.0) else bytes.get() * 8.0 / (total / 1000.0)
    }
}

// ───────────────────────── Data usage ─────────────────────────
/** Mobile and Wi-Fi data for today and this month, plus the apps that used the most. */
class DataActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.data_t))
    }

    override fun onResume() { super.onResume(); draw() }

    private fun allowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
                   else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        if (!allowed()) {
            col.addView(label(getString(R.string.data_need), 16f, R.color.sub))
            col.addView(sahlButton(getString(R.string.data_allow)) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
            return
        }
        val p = Tools.prefs(this)
        val cycleDay = p.getInt("cycleDay", 1)
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val cycle = (today.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_MONTH) < cycleDay) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, minOf(cycleDay, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        val loading = label(getString(R.string.working), 15f, R.color.sub)
        col.addView(loading)
        Thread {
            val nsm = getSystemService(NetworkStatsManager::class.java)
            fun total(type: Int, from: Long) = try { nsm.querySummaryForDevice(type, null, from, now).let { it.rxBytes + it.txBytes } } catch (_: Exception) { 0L }
            val mToday = total(ConnectivityManager.TYPE_MOBILE, today.timeInMillis)
            val wToday = total(ConnectivityManager.TYPE_WIFI, today.timeInMillis)
            val mCycle = total(ConnectivityManager.TYPE_MOBILE, cycle.timeInMillis)
            val wCycle = total(ConnectivityManager.TYPE_WIFI, cycle.timeInMillis)
            val perApp = HashMap<Int, LongArray>()   // uid → [mobile, wifi]
            for ((i, type) in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI).withIndex()) try {
                val st = nsm.querySummary(type, null, cycle.timeInMillis, now)
                val bk = NetworkStats.Bucket()
                while (st.hasNextBucket()) { st.getNextBucket(bk); perApp.getOrPut(bk.uid) { LongArray(2) }[i] += bk.rxBytes + bk.txBytes }
                st.close()
            } catch (_: Exception) { }
            val top = perApp.entries.sortedByDescending { it.value[0] + it.value[1] }.take(20)
            val pm = packageManager
            val rows = top.map { (uid, v) ->
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                val name = when (uid) {
                    -4 -> getString(R.string.data_removed); -5 -> getString(R.string.data_hotspot); 1000 -> "Android"
                    else -> pkg?.let { try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it } } ?: "UID $uid"
                }
                val icon = pkg?.let { try { pm.getApplicationIcon(it) } catch (_: Exception) { null } }
                Triple(name, icon, v)
            }
            runOnUiThread {
                col.removeView(loading)
                col.addSpaced(card().apply {
                    addView(label(getString(R.string.data_today), 14f, R.color.sub))
                    addView(label("📶  " + fmtBytes(mToday) + "     📡  " + fmtBytes(wToday), 22f, R.color.emerald, true))
                })
                col.addSpaced(card().apply {
                    addView(label(getString(R.string.data_cycle, cycleDay), 14f, R.color.sub))
                    addView(label("📶  " + fmtBytes(mCycle) + "     📡  " + fmtBytes(wCycle), 22f, R.color.emerald, true))
                    addView(label(getString(R.string.data_legend), 12f, R.color.sub))
                    addView(sahlButton(getString(R.string.data_change_day)) {
                        val next = if (cycleDay >= 28) 1 else cycleDay + 1
                        Tools.prefs(this@DataActivity).edit().putInt("cycleDay", next).apply(); draw()
                    })
                })
                col.addView(label(getString(R.string.data_top), 18f, R.color.emerald, true).apply { setPadding(dp(4), dp(8), 0, dp(6)) })
                val max = (rows.maxOfOrNull { it.third[0] + it.third[1] } ?: 1L).coerceAtLeast(1L)
                val list = card()
                for ((name, icon, v) in rows) {
                    val sum = v[0] + v[1]
                    if (sum == 0L) continue
                    val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                    r.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                    info.addView(label(name, 15f, bold = true))
                    info.addView(label("📶 " + fmtBytes(v[0]) + "   📡 " + fmtBytes(v[1]), 12f, R.color.sub))
                    info.addView(View(this).apply { background = rounded(R.color.gold, 3) },
                        LinearLayout.LayoutParams((dp(220) * sum / max).toInt().coerceAtLeast(dp(4)), dp(5)).apply { topMargin = dp(3) })
                    r.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(label(fmtBytes(sum), 14f, R.color.text, true))
                    list.addView(r)
                }
                col.addView(list)
            }
        }.start()
    }
}

// ───────────────────────── Activity launcher ─────────────────────────
/** Every installed app and its hidden screens; tap to open one, hold to put it on the home screen. */
class LauncherActivity : Activity() {
    private data class App(val label: String, val pkg: String, val icon: Drawable)
    private val apps = ArrayList<App>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = sahlScreen(getString(R.string.launcher_t))
        val search = EditText(this).apply {
            hint = getString(R.string.launcher_search); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = show(s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(search, LinearLayout.LayoutParams(-1, -2))
        count = label(getString(R.string.working), 13f, R.color.sub).apply { setPadding(dp(4), dp(4), 0, dp(8)) }
        col.addView(count)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        Thread {
            val pm = packageManager
            val all = pm.getInstalledApplications(0).map { App(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { apps.addAll(all); show("") }
        }.start()
    }

    private fun show(q: String) {
        list.removeAllViews()
        val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
        count.text = getString(R.string.launcher_count, shown.size)
        for (a in shown.take(150)) list.addView(appRow(a))
    }

    private fun appRow(a: App): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)); isClickable = true
            background = getDrawable(android.R.drawable.list_selector_background)
        }
        r.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        t.addView(label(a.label, 15f, bold = true)); t.addView(label(a.pkg, 11f, R.color.sub))
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = label("▾", 18f, R.color.emerald, true)
        r.addView(arrow)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(48), 0, 0, dp(6)) }
        r.setOnClickListener {
            if (inner.visibility == View.VISIBLE) { inner.visibility = View.GONE; arrow.text = "▾"; return@setOnClickListener }
            if (inner.childCount == 0) fill(inner, a)
            inner.visibility = View.VISIBLE; arrow.text = "▴"
        }
        wrap.addView(r); wrap.addView(inner)
        return wrap
    }

    private fun fill(inner: LinearLayout, a: App) {
        val acts = try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_ACTIVITIES).activities?.filter { it.exported && it.enabled } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        if (acts.isEmpty()) { inner.addView(label(getString(R.string.launcher_none), 13f, R.color.sub)); return }
        inner.addView(label(getString(R.string.launcher_hint), 12f, R.color.sub))
        for (ai in acts) {
            val short = ai.name.removePrefix(a.pkg).removePrefix(".")
            val title = ai.loadLabel(packageManager).toString().takeIf { it != a.label } ?: short.substringAfterLast('.')
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true
                background = getDrawable(android.R.drawable.list_selector_background)
                addView(label(title, 14f, R.color.emerald, true)); addView(label(short, 11f, R.color.sub))
                setOnClickListener {
                    try { startActivity(Intent().setComponent(ComponentName(a.pkg, ai.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Toast.makeText(this@LauncherActivity, getString(R.string.launcher_fail), Toast.LENGTH_SHORT).show() }
                }
                setOnLongClickListener { pin(a, ai.name, title); true }
            }
            inner.addView(v)
        }
    }

    /** Puts a shortcut to this exact screen on the home screen. */
    private fun pin(a: App, cls: String, title: String) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) { Toast.makeText(this, R.string.launcher_fail, Toast.LENGTH_SHORT).show(); return }
        val bmp = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888)
        a.icon.setBounds(0, 0, 144, 144); a.icon.draw(android.graphics.Canvas(bmp))
        val info = ShortcutInfo.Builder(this, "$cls#${System.currentTimeMillis()}")
            .setShortLabel(title.take(24)).setIcon(Icon.createWithBitmap(bmp))
            .setIntent(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(a.pkg, cls))).build()
        sm.requestPinShortcut(info, null)
    }
}

// ───────────────────────── Screen light ─────────────────────────
/** Full-brightness screen as a soft light. Tap to change colour, back to close. */
class ScreenLightActivity : Activity() {
    private val colors = intArrayOf(Color.WHITE, 0xFFFFE0B2.toInt(), 0xFFFFB74D.toInt(), 0xFFB71C1C.toInt())
    private var i = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        val hint = label(getString(R.string.light_hint), 14f, R.color.sub).apply { gravity = Gravity.CENTER; setTextColor(0x88000000.toInt()) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(colors[0])
            addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            setOnClickListener { i = (i + 1) % colors.size; setBackgroundColor(colors[i]); hint.visibility = View.GONE }
        }
        setContentView(root)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
