package com.sahl.tools

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** What we know about common pre-installed apps. Only apps actually on the phone are shown. */
object Bloat {
    const val SAFE = 0      // removing it has no side effects for most people
    const val CAUTION = 1   // works, but you lose a feature (listed in the note)

    data class Info(val level: Int, val group: String, val note: String)

    val known: Map<String, Info> = mapOf(
        // Honor / MagicOS
        "com.hihonor.tips" to Info(SAFE, "Honor", "Tips app"),
        "com.hihonor.phoneservice" to Info(SAFE, "Honor", "My Honor (support & offers)"),
        "com.hihonor.appmarket" to Info(CAUTION, "Honor", "Honor App Market — also gives some system app updates"),
        "com.hihonor.browser" to Info(SAFE, "Honor", "Honor Browser"),
        "com.hihonor.android.totemweather" to Info(CAUTION, "Honor", "Weather app and weather widget"),
        "com.hihonor.gamecenter" to Info(SAFE, "Honor", "Game Center"),
        "com.hihonor.gameassistant" to Info(CAUTION, "Honor", "Game mode / game assistant"),
        "com.hihonor.search" to Info(SAFE, "Honor", "Honor search & suggestions"),
        "com.hihonor.wallet" to Info(SAFE, "Honor", "Honor Wallet"),
        "com.hihonor.health" to Info(CAUTION, "Honor", "Honor Health — needed for Honor watches/bands"),
        "com.hihonor.android.thememanager" to Info(CAUTION, "Honor", "Themes — can't change theme/wallpaper styles"),
        "com.hihonor.hnvideo" to Info(SAFE, "Honor", "Honor Video"),
        "com.hihonor.music" to Info(SAFE, "Honor", "Honor Music"),
        "com.hihonor.cloud" to Info(CAUTION, "Honor", "Honor Cloud backup"),
        "com.hihonor.findmydevice" to Info(CAUTION, "Honor", "Find My Device (Honor) — keep if you rely on it"),
        "com.hihonor.magicvoice" to Info(SAFE, "Honor", "YOYO voice assistant"),
        "com.hihonor.yoyo" to Info(SAFE, "Honor", "YOYO assistant"),
        "com.hihonor.hnoffice" to Info(SAFE, "Honor", "Honor Office"),
        "com.hihonor.email" to Info(SAFE, "Honor", "Honor Email"),
        "com.hihonor.notepad" to Info(CAUTION, "Honor", "Notes — your notes are inside it"),
        "com.hihonor.compass" to Info(SAFE, "Honor", "Compass"),
        "com.hihonor.android.mirror" to Info(SAFE, "Honor", "Mirror app"),
        "com.hihonor.android.fmradio" to Info(SAFE, "Honor", "FM Radio"),
        "com.hihonor.hiboard" to Info(CAUTION, "Honor", "Left-screen feed (swipe right on home)"),
        "com.hihonor.securitycenter" to Info(CAUTION, "Honor", "Phone Manager — battery/cleaner settings live here"),
        // Huawei-era names some Honor phones still use
        "com.huawei.appmarket" to Info(CAUTION, "Honor", "AppGallery"),
        "com.huawei.browser" to Info(SAFE, "Honor", "Browser"),
        "com.huawei.tips" to Info(SAFE, "Honor", "Tips"),
        // Google
        "com.google.android.apps.tachyon" to Info(SAFE, "Google", "Google Meet"),
        "com.google.android.videos" to Info(SAFE, "Google", "Google TV"),
        "com.google.android.apps.youtube.music" to Info(SAFE, "Google", "YouTube Music"),
        "com.google.android.apps.magazines" to Info(SAFE, "Google", "Google News"),
        "com.google.android.apps.subscriptions.red" to Info(SAFE, "Google", "Google One"),
        "com.google.android.apps.bard" to Info(SAFE, "Google", "Gemini"),
        "com.google.android.apps.googleassistant" to Info(CAUTION, "Google", "Google Assistant — \"Hey Google\" stops"),
        "com.google.android.feedback" to Info(SAFE, "Google", "Feedback sender"),
        "com.google.android.apps.wellbeing" to Info(CAUTION, "Google", "Digital Wellbeing / bedtime mode"),
        "com.google.android.apps.photos" to Info(CAUTION, "Google", "Google Photos — back up first"),
        "com.google.android.apps.docs" to Info(CAUTION, "Google", "Google Drive"),
        "com.google.android.youtube" to Info(CAUTION, "Google", "YouTube (you use a patched one)"),
        "com.google.android.apps.maps" to Info(CAUTION, "Google", "Google Maps"),
        "com.google.android.apps.messaging" to Info(CAUTION, "Google", "Google Messages — keep if it's your SMS app"),
        "com.google.android.apps.restore" to Info(SAFE, "Google", "Restore from old phone (setup only)"),
        "com.google.android.projection.gearhead" to Info(CAUTION, "Google", "Android Auto"),
        "com.google.android.apps.nbu.files" to Info(SAFE, "Google", "Files by Google"),
        "com.google.android.apps.safetyhub" to Info(CAUTION, "Google", "Personal Safety"),
        "com.google.android.calendar" to Info(CAUTION, "Google", "Google Calendar"),
        // Facebook / partners
        "com.facebook.appmanager" to Info(SAFE, "Partners", "Facebook App Manager (pre-installed updater)"),
        "com.facebook.services" to Info(SAFE, "Partners", "Facebook Services"),
        "com.facebook.system" to Info(SAFE, "Partners", "Facebook App Installer"),
        "com.netflix.partner.activation" to Info(SAFE, "Partners", "Netflix activation helper"),
        "com.booking" to Info(SAFE, "Partners", "Booking.com"),
        "com.microsoft.appmanager" to Info(CAUTION, "Partners", "Link to Windows"),
        "com.amazon.appmanager" to Info(SAFE, "Partners", "Amazon App Manager"),
        "com.tiktok.android" to Info(SAFE, "Partners", "TikTok"),
    )
}

/** Remove (for this user) or disable pre-installed apps — with suggestions, safety ratings, tick-to-select and one-tap undo. */
class DebloatActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var bar: TextView
    private var tab = 0          // 0 suggested, 1 all system, 2 removed/disabled, 3 my apps
    private var query = ""
    private var all: List<ApplicationInfo> = emptyList()
    private val selected = LinkedHashSet<String>()
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
        col.addView(EditText(this).apply {
            hint = getString(R.string.launcher_search); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); show() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, LinearLayout.LayoutParams(-1, -2))
        val tabs = listOf(R.string.db_suggested, R.string.db_system, R.string.db_removed, R.string.db_user)
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabRow.addView(row(*tabs.take(2).mapIndexed { i, t -> sahlButton(getString(t)) { tab = i; selected.clear(); show() } }.toTypedArray()))
        tabRow.addView(row(*tabs.drop(2).mapIndexed { i, t -> sahlButton(getString(t)) { tab = i + 2; selected.clear(); show() } }.toTypedArray()))
        col.addView(tabRow)
        // Action bar for ticked apps
        bar = label("", 15f, R.color.card, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(R.color.emerald, 14); visibility = View.GONE
            setOnClickListener { batchMenu() }
        }
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        load()
    }

    private fun load() {
        list.removeAllViews(); list.addView(label(getString(R.string.working), 14f, R.color.sub))
        Thread {
            val a = packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
            runOnUiThread { all = a; show() }
        }.start()
    }

    private fun installed(i: ApplicationInfo) = i.flags and ApplicationInfo.FLAG_INSTALLED != 0
    private fun system(i: ApplicationInfo) = i.flags and ApplicationInfo.FLAG_SYSTEM != 0
    private fun off(i: ApplicationInfo) = !installed(i) || Freezer.isFrozen(this, i.packageName)

    private fun show() {
        list.removeAllViews()
        val pm = packageManager
        val shown = all.filter {
            when (tab) {
                0 -> it.packageName in Bloat.known && !off(it)
                1 -> system(it) && !off(it)
                2 -> off(it) && !Freezer.list(this).contains(it.packageName)
                else -> !system(it) && !off(it)
            } && (query.isBlank() || pm.getApplicationLabel(it).contains(query, true) || it.packageName.contains(query, true))
        }.sortedBy { Bloat.known[it.packageName]?.level ?: 9 }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(getString(R.string.launcher_count, shown.size), 13f, R.color.sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (tab == 0) head.addView(sahlButton(getString(R.string.db_select_safe)) {
            shown.filter { Bloat.known[it.packageName]?.level == Bloat.SAFE }.forEach { selected.add(it.packageName) }; show()
        })
        if (tab == 2 && shown.isNotEmpty()) head.addView(sahlButton("↩  " + getString(R.string.db_restore_all)) {
            run(shown.map { restoreCmd(it) })
        })
        list.addView(head)
        if (tab == 0 && shown.isEmpty()) list.addView(label(getString(R.string.db_nothing), 14f, R.color.sub))
        var lastGroup = ""
        for (a in shown.take(300)) {
            val g = Bloat.known[a.packageName]?.group ?: ""
            if (tab == 0 && g != lastGroup) { list.addView(label(g, 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(10), 0, dp(4)) }); lastGroup = g }
            list.addView(appRow(a))
        }
        updateBar()
    }

    private fun chip(text: String, color: Int) = TextView(this).apply {
        this.text = text; textSize = 11f; typeface = sahlFont(); setTextColor(android.graphics.Color.WHITE)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
    }

    private fun appRow(a: ApplicationInfo): View {
        val pm = packageManager
        val pkg = a.packageName
        val info = Bloat.known[pkg]
        val locked = pkg in Freezer.protectedPkgs
        val c = card()
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val box = CheckBox(this).apply {
            isChecked = pkg in selected; isEnabled = !locked
            setOnCheckedChangeListener { _, on -> if (on) selected.add(pkg) else selected.remove(pkg); updateBar() }
        }
        r.addView(box)
        r.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(a)); alpha = if (off(a)) 0.4f else 1f },
            LinearLayout.LayoutParams(dp(34), dp(34)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        t.addView(label(pm.getApplicationLabel(a), 15f, bold = true))
        t.addView(label(pkg, 10f, R.color.sub))
        val chips = LinearLayout(this).apply { setPadding(0, dp(3), 0, 0) }
        when {
            locked -> chips.addView(chip("🔒 " + getString(R.string.db_keep), 0xFF616161.toInt()))
            info?.level == Bloat.SAFE -> chips.addView(chip("✓ " + getString(R.string.db_safe), 0xFF2E7D32.toInt()))
            info?.level == Bloat.CAUTION -> chips.addView(chip("⚠ " + getString(R.string.db_caution), 0xFFE65100.toInt()))
        }
        if (!installed(a)) chips.addView(chip(getString(R.string.db_is_removed), 0xFF6A1B9A.toInt()).apply { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(4) })
        else if (Freezer.isFrozen(this, pkg)) chips.addView(chip(getString(R.string.db_is_disabled), 0xFF455A64.toInt()))
        t.addView(chips)
        info?.let { t.addView(label(it.note, 12f, R.color.sub)) }
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        r.setOnClickListener { if (!locked) box.isChecked = !box.isChecked }
        c.addView(r)
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addSpaced(c) }
    }

    private fun updateBar() {
        bar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        bar.text = getString(R.string.db_selected, selected.size)
    }

    private fun restoreCmd(a: ApplicationInfo) =
        if (!installed(a)) "cmd package install-existing --user 0 ${a.packageName}" else "pm enable ${a.packageName}; pm unsuspend --user 0 ${a.packageName}"

    /** What to do with the ticked apps. */
    private fun batchMenu() {
        val apps = all.filter { it.packageName in selected }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(apps.joinToString(", ") { packageManager.getApplicationLabel(it) }, 13f, R.color.sub))
        val caution = apps.count { Bloat.known[it.packageName]?.level == Bloat.CAUTION || it.packageName !in Bloat.known }
        if (caution > 0 && tab != 2) box.addView(label("⚠  " + getString(R.string.db_batch_caution, caution), 13f).apply { setPadding(0, dp(8), 0, 0) })
        val buttons = if (tab == 2) arrayOf(
            getString(android.R.string.cancel) to { d: android.app.AlertDialog -> d.dismiss() },
            getString(R.string.db_restore) to { d: android.app.AlertDialog -> d.dismiss(); run(apps.map { restoreCmd(it) }) })
        else arrayOf(
            getString(android.R.string.cancel) to { d: android.app.AlertDialog -> d.dismiss() },
            getString(R.string.db_disable) to { d: android.app.AlertDialog -> d.dismiss(); run(apps.map { "pm disable-user --user 0 ${it.packageName}" }) },
            getString(R.string.db_uninstall) to { d: android.app.AlertDialog -> d.dismiss(); run(apps.map { "pm uninstall -k --user 0 ${it.packageName}" }) })
        sahlDialog(getString(R.string.db_selected, apps.size), box, *buttons)
    }

    private fun run(cmds: List<String>) {
        bar.visibility = View.VISIBLE; bar.text = getString(R.string.working)
        Thread {
            var ok = 0
            for (c in cmds) if (!c.contains(Regex("\\b(" + Freezer.protectedPkgs.joinToString("|") { Regex.escape(it) } + ")$")) && Shell.run(c).first == 0) ok++
            runOnUiThread {
                Toast.makeText(this, getString(R.string.db_batch_done, ok, cmds.size), Toast.LENGTH_LONG).show()
                selected.clear(); load()
            }
        }.start()
    }
}
