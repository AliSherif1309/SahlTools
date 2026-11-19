package com.sahl.tools

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import rikka.shizuku.Shizuku

// ───────────────────────── Shizuku shell ─────────────────────────
/** Runs shell commands (pm …) with Shizuku's permission. */
object Shell {
    fun ready() = WifiPasswords.state() == 2

    fun run(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor() to out.trim()
    } catch (e: Throwable) { -1 to (e.cause ?: e).toString() }
}

// ───────────────────────── Freeze logic ─────────────────────────
object Freezer {
    /** Apps that must never be frozen or removed, or the phone could stop working. */
    val protectedPkgs = setOf("android", "com.android.systemui", "com.android.phone", "com.android.settings", "com.sahl.tools",
        "moe.shizuku.privileged.api", "com.android.providers.settings", "com.android.shell", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.networkstack", "com.google.android.networkstack", "com.android.bluetooth", "com.android.nfc",
        "com.huawei.android.launcher", "com.hihonor.android.launcher", "com.android.server.telecom", "com.android.inputmethod.latin")

    fun list(c: Context): MutableSet<String> = Tools.prefs(c).getStringSet("freezeList", emptySet())!!.toMutableSet()
    fun save(c: Context, s: Set<String>) { Tools.prefs(c).edit().putStringSet("freezeList", s).apply(); refreshTile(c) }

    fun isFrozen(c: Context, pkg: String) = try {
        c.packageManager.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            c.packageManager.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { false }

    /** Blocking. */
    fun freeze(pkg: String) = pkg !in protectedPkgs && Shell.run("pm disable-user --user 0 $pkg").first == 0
    fun unfreeze(pkg: String) = Shell.run("pm enable $pkg").first == 0

    fun allFrozen(c: Context) = list(c).let { l -> l.isNotEmpty() && l.all { isFrozen(c, it) } }

    /** Blocking: freeze every app in the list. Returns how many were frozen. */
    fun freezeAll(c: Context): Int {
        var n = 0
        for (p in list(c)) if (!isFrozen(c, p) && freeze(p)) n++
        refreshTile(c)
        return n
    }

    fun unfreezeAll(c: Context): Int {
        var n = 0
        for (p in list(c)) if (isFrozen(c, p) && unfreeze(p)) n++
        refreshTile(c)
        return n
    }

    fun refreshTile(c: Context) = try { TileService.requestListeningState(c, ComponentName(c, FreezeTile::class.java)) } catch (_: Exception) { }

    /** Unfreezes (if needed) and opens an app; remembers it for auto-freeze. */
    fun launch(c: Context, pkg: String) {
        Thread {
            if (isFrozen(c, pkg)) unfreeze(pkg)
            Handler(Looper.getMainLooper()).post {
                val i = c.packageManager.getLaunchIntentForPackage(pkg)
                if (i == null) Toast.makeText(c, R.string.fr_no_launch, Toast.LENGTH_SHORT).show()
                else c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (Tools.prefs(c).getBoolean("autoFreeze", true)) c.startForegroundService(Intent(c, AutoFreezeService::class.java))
                refreshTile(c)
            }
        }.start()
    }

    /** Home-screen shortcut that unfreezes and opens the app in one tap. */
    fun pin(c: Context, pkg: String) {
        val pm = c.packageManager
        val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        val d = pm.getApplicationIcon(ai)
        val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 144, 144); d.draw(Canvas(bmp))
        val sm = c.getSystemService(ShortcutManager::class.java)
        sm.requestPinShortcut(ShortcutInfo.Builder(c, "freeze_$pkg").setShortLabel(pm.getApplicationLabel(ai).toString().take(20))
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(Intent(c, FreezeLaunchActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("pkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).build(), null)
    }
}

/** Opened by home-screen shortcuts: unfreeze + open. */
class FreezeLaunchActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pkg = intent.getStringExtra("pkg")
        if (pkg != null && Shell.ready()) Freezer.launch(applicationContext, pkg)
        else Toast.makeText(this, R.string.fr_need_shizuku_short, Toast.LENGTH_LONG).show()
        finish()
    }
}

/** Quick Settings tile: freeze all / unfreeze all. */
class FreezeTile : TileService() {
    override fun onStartListening() = refresh()

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Freezer.allFrozen(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(R.string.fr_tile_sub, Freezer.list(this).count { Freezer.isFrozen(this, it) }, Freezer.list(this).size)
        t.updateTile()
    }

    override fun onClick() {
        if (!Shell.ready()) { Toast.makeText(this, R.string.fr_need_shizuku_short, Toast.LENGTH_LONG).show(); return }
        val freeze = !Freezer.allFrozen(this)
        Thread {
            if (freeze) Freezer.freezeAll(this) else Freezer.unfreezeAll(this)
            Handler(Looper.getMainLooper()).post { refresh() }
        }.start()
    }
}

/** Auto-freeze: while an app from the list is unfrozen, refreeze it a little after the screen turns off. Stops itself when done. */
class AutoFreezeService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val job = Runnable {
        Thread {
            Freezer.freezeAll(this)
            h.post { stopSelf() }
        }.start()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { h.removeCallbacks(job); h.postDelayed(job, Tools.prefs(c).getInt("freezeDelay", 1) * 60_000L) }
                Intent.ACTION_SCREEN_ON -> h.removeCallbacks(job)
            }
        }
    }

    override fun onBind(i: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, f)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        Tools.foreground(this, Tools.SCREEN, 15, title = getString(R.string.fr_auto_running), icon = R.drawable.ic_freeze)
        if (i?.action == "stop") { h.removeCallbacks(job); job.run() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); unregisterReceiver(receiver); super.onDestroy() }
}

// ───────────────────────── Shared Shizuku gate ─────────────────────────
/** Shows "start Shizuku" / "allow" cards; returns true when ready. */
fun Activity.shizukuGate(col: LinearLayout, explain: Int): Boolean {
    when (WifiPasswords.state()) {
        0 -> col.addSpaced(card().apply {
            addView(label(getString(explain), 15f, R.color.sub))
            addView(sahlButton(getString(R.string.wpass_open_shizuku)) {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    ?: openLink("https://shizuku.rikka.app/download/")
            })
        })
        1 -> col.addSpaced(card().apply {
            addView(label(getString(R.string.fr_allow), 15f, R.color.sub))
            addView(sahlButton(getString(R.string.wpass_allow)) { try { Shizuku.requestPermission(8) } catch (_: Throwable) { } })
        })
        else -> return true
    }
    return false
}

// ───────────────────────── Freezer screen ─────────────────────────
class FreezerActivity : Activity() {
    private lateinit var col: LinearLayout
    private val listener = Shizuku.OnRequestPermissionResultListener { _, r -> runOnUiThread { if (r == PackageManager.PERMISSION_GRANTED) draw() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.fr_t))
        try { Shizuku.addRequestPermissionResultListener(listener) } catch (_: Throwable) { }
    }
    override fun onDestroy() { try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Throwable) { }; super.onDestroy() }
    override fun onResume() { super.onResume(); draw() }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        if (!shizukuGate(col, R.string.fr_need_shizuku)) return
        val pm = packageManager
        val list = Freezer.list(this).filter { try { pm.getApplicationInfo(it, PackageManager.MATCH_DISABLED_COMPONENTS); true } catch (_: Exception) { false } }
        val frozen = list.count { Freezer.isFrozen(this, it) }

        col.addSpaced(card().apply {
            addView(label(getString(R.string.fr_summary, frozen, list.size), 18f, R.color.emerald, true))
            addView(actions(
                sahlButton("❄  " + getString(R.string.fr_freeze_all)) { work { Freezer.freezeAll(this@FreezerActivity) } },
                sahlButton("☀  " + getString(R.string.fr_unfreeze_all)) { work { Freezer.unfreezeAll(this@FreezerActivity) } },
                sahlButton("＋  " + getString(R.string.fr_add)) { startActivity(Intent(this@FreezerActivity, AppPickerActivity::class.java)) }))
            // Auto-freeze settings
            val p = Tools.prefs(this@FreezerActivity)
            val auto = android.widget.Switch(this@FreezerActivity).apply {
                text = getString(R.string.fr_auto, p.getInt("freezeDelay", 1)); typeface = sahlFont(); setTextColor(getColor(R.color.text))
                isChecked = p.getBoolean("autoFreeze", true)
                setOnCheckedChangeListener { _, on -> p.edit().putBoolean("autoFreeze", on).apply() }
            }
            addView(auto)
            addView(sahlButton(getString(R.string.fr_delay)) {
                val next = when (p.getInt("freezeDelay", 1)) { 0 -> 1; 1 -> 5; 5 -> 15; 15 -> 30; else -> 0 }
                p.edit().putInt("freezeDelay", next).apply(); auto.text = getString(R.string.fr_auto, next)
            })
            if (Build.VERSION.SDK_INT >= 33) addView(sahlButton(getString(R.string.add_tile)) {
                getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                    ComponentName(this@FreezerActivity, FreezeTile::class.java), getString(R.string.fr_t),
                    Icon.createWithResource(this@FreezerActivity, R.drawable.ic_freeze), mainExecutor) { }
            })
        })

        if (list.isEmpty()) { col.addView(label(getString(R.string.fr_empty), 15f, R.color.sub)); return }
        for (pkg in list.sortedBy { appName(it).lowercase() }) col.addSpaced(appCard(pkg))
    }

    private fun appName(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)).toString()
    } catch (_: Exception) { pkg }

    private fun appCard(pkg: String) = card().apply {
        val pm = packageManager
        val frozen = Freezer.isFrozen(this@FreezerActivity, pkg)
        val r = LinearLayout(this@FreezerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(ImageView(this@FreezerActivity).apply {
            setImageDrawable(try { pm.getApplicationIcon(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)) } catch (_: Exception) { null })
            alpha = if (frozen) 0.35f else 1f
            if (frozen) colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val t = LinearLayout(this@FreezerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        t.addView(label(appName(pkg), 16f, bold = true))
        t.addView(label(if (frozen) "❄  " + getString(R.string.fr_frozen) else "☀  " + getString(R.string.fr_running), 13f,
            if (frozen) R.color.emerald else R.color.sub))
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        r.setOnClickListener { Freezer.launch(this@FreezerActivity, pkg) }
        addView(r)
        addView(actions(
            sahlButton(getString(R.string.fr_open)) { Freezer.launch(this@FreezerActivity, pkg) },
            sahlButton(getString(if (frozen) R.string.fr_unfreeze else R.string.fr_freeze)) {
                work { if (frozen) Freezer.unfreeze(pkg) else Freezer.freeze(pkg) }
            },
            sahlButton(getString(R.string.fr_shortcut)) { Freezer.pin(this@FreezerActivity, pkg) },
            sahlButton(getString(R.string.fr_remove)) {
                val s = Freezer.list(this@FreezerActivity); s.remove(pkg); Freezer.save(this@FreezerActivity, s)
                work { if (frozen) Freezer.unfreeze(pkg) }
            }))
    }

    private fun work(f: () -> Unit) = Thread { f(); runOnUiThread { draw() } }.start()
}

/** Pick apps to add to the freeze list. */
class AppPickerActivity : Activity() {
    private lateinit var list: LinearLayout
    private var apps: List<ApplicationInfo> = emptyList()
    private var showSystem = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = sahlScreen(getString(R.string.fr_add))
        val search = EditText(this).apply {
            hint = getString(R.string.launcher_search); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = show(s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(search, LinearLayout.LayoutParams(-1, -2))
        col.addView(android.widget.Switch(this).apply {
            text = getString(R.string.fr_show_system); typeface = sahlFont(); setTextColor(getColor(R.color.text))
            setOnCheckedChangeListener { _, on -> showSystem = on; show(search.text.toString()) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        Thread {
            val a = packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .filter { it.packageName !in Freezer.protectedPkgs }
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
            runOnUiThread { apps = a; show("") }
        }.start()
    }

    private fun show(q: String) {
        list.removeAllViews()
        val chosen = Freezer.list(this)
        val pm = packageManager
        for (a in apps.filter { (showSystem || isUser(it)) && (q.isBlank() || pm.getApplicationLabel(it).contains(q, true) || it.packageName.contains(q, true)) }.take(200)) {
            val box = android.widget.CheckBox(this).apply {
                isChecked = a.packageName in chosen; text = pm.getApplicationLabel(a); typeface = sahlFont(); textSize = 15f
                setTextColor(getColor(R.color.text))
                setOnCheckedChangeListener { _, on ->
                    val s = Freezer.list(this@AppPickerActivity); if (on) s.add(a.packageName) else s.remove(a.packageName)
                    Freezer.save(this@AppPickerActivity, s)
                }
            }
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(a)) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            r.addView(box)
            list.addView(r)
        }
    }

    private fun isUser(i: ApplicationInfo) = i.flags and ApplicationInfo.FLAG_SYSTEM == 0
}

// ───────────────────────── Debloater ─────────────────────────
/** Remove (for this user) or disable pre-installed apps you don't use — and bring them back any time. */
class DebloatActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var list: LinearLayout
    private var filter = 0      // 0 = system apps, 1 = removed/disabled, 2 = user apps
    private var query = ""
    private var all: List<ApplicationInfo> = emptyList()
    private val listener = Shizuku.OnRequestPermissionResultListener { _, r -> runOnUiThread { if (r == PackageManager.PERMISSION_GRANTED) draw() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.db_t))
        try { Shizuku.addRequestPermissionResultListener(listener) } catch (_: Throwable) { }
        draw()
    }
    override fun onDestroy() { try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Throwable) { }; super.onDestroy() }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        if (!shizukuGate(col, R.string.db_need_shizuku)) return
        col.addView(label(getString(R.string.db_warning), 13f, R.color.sub).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        val search = EditText(this).apply {
            hint = getString(R.string.launcher_search); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); show() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(search, LinearLayout.LayoutParams(-1, -2))
        val tabs = listOf(R.string.db_system, R.string.db_removed, R.string.db_user)
        col.addView(row(*tabs.mapIndexed { i, t -> sahlButton(getString(t)) { filter = i; show() } }.toTypedArray()))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        load()
    }

    private fun load() {
        list.removeAllViews(); list.addView(label(getString(R.string.working), 14f, R.color.sub))
        Thread {
            @Suppress("DEPRECATION")
            val a = packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
            runOnUiThread { all = a; show() }
        }.start()
    }

    private fun installed(i: ApplicationInfo) = i.flags and ApplicationInfo.FLAG_INSTALLED != 0
    private fun system(i: ApplicationInfo) = i.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun show() {
        list.removeAllViews()
        val pm = packageManager
        val shown = all.filter {
            when (filter) {
                0 -> system(it) && installed(it) && !Freezer.isFrozen(this, it.packageName)
                1 -> !installed(it) || Freezer.isFrozen(this, it.packageName)
                else -> !system(it) && installed(it)
            } && (query.isBlank() || pm.getApplicationLabel(it).contains(query, true) || it.packageName.contains(query, true))
        }
        list.addView(label(getString(R.string.launcher_count, shown.size), 13f, R.color.sub).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        for (a in shown.take(250)) list.addView(appRow(a))
    }

    private fun appRow(a: ApplicationInfo): View {
        val pm = packageManager
        val pkg = a.packageName
        val gone = !installed(a)
        val off = Freezer.isFrozen(this, pkg)
        val locked = pkg in Freezer.protectedPkgs
        val c = card()
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(a)); alpha = if (gone || off) 0.4f else 1f },
            LinearLayout.LayoutParams(dp(34), dp(34)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        t.addView(label(pm.getApplicationLabel(a), 15f, bold = true))
        t.addView(label(pkg + when { gone -> "  · " + getString(R.string.db_is_removed); off -> "  · " + getString(R.string.db_is_disabled); else -> "" },
            11f, R.color.sub))
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(r)
        when {
            locked -> c.addView(label("🔒  " + getString(R.string.db_protected), 12f, R.color.sub))
            gone || off -> c.addView(sahlButton("↩  " + getString(R.string.db_restore)) {
                run(if (gone) "cmd package install-existing --user 0 $pkg" else "pm enable $pkg")
            })
            else -> c.addView(actions(
                sahlButton(getString(R.string.db_disable)) { run("pm disable-user --user 0 $pkg") },
                sahlButton(getString(R.string.db_uninstall)) {
                    sahlDialog(getString(R.string.db_confirm_t, pm.getApplicationLabel(a)), label(getString(R.string.db_confirm), 14f, R.color.sub),
                        getString(android.R.string.cancel) to { d: android.app.AlertDialog -> d.dismiss() },
                        getString(R.string.db_uninstall) to { d: android.app.AlertDialog -> d.dismiss(); run("pm uninstall -k --user 0 $pkg") })
                }))
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addSpaced(c) }
    }

    private fun run(cmd: String) = Thread {
        val (code, out) = Shell.run(cmd)
        runOnUiThread {
            Toast.makeText(this, if (code == 0) getString(R.string.db_done) else getString(R.string.db_failed) + " " + out.take(120), Toast.LENGTH_LONG).show()
            load()
        }
    }.start()
}
