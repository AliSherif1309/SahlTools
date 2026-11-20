package com.sahl.tools

import android.app.Activity
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val switches = HashMap<String, Switch>()
    private var soundBtn: Button? = null
    private val sections = ArrayList<Pair<View, MutableList<Pair<View, String>>>>()
    private lateinit var scroller: ScrollView
    private lateinit var noMatch: TextView

    /** A section title; returns the list its tool cards register in (for search). */
    private fun section(parent: LinearLayout, title: String): MutableList<Pair<View, String>> {
        val h = text(title, 20f, R.color.emerald, true).apply { setPadding(dp(4), dp(14), 0, dp(8)) }
        parent.addView(h)
        val l = ArrayList<Pair<View, String>>()
        sections.add(h to l)
        return l
    }

    /** Shows only the tools whose name or description contains the search words. */
    private fun filter(q: String) {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        var any = false
        for ((h, cards) in sections) {
            var vis = false
            for ((v, t) in cards) {
                val ok = words.all { t.contains(it) }
                v.visibility = if (ok) View.VISIBLE else View.GONE
                if (ok) vis = true
            }
            h.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) any = true
        }
        noMatch.visibility = if (any) View.GONE else View.VISIBLE
    }
    private var linkOffBar: TextView? = null
    private lateinit var font: Typeface
    private lateinit var updateBar: TextView
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = sync()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(getColor(color))
        typeface = if (bold) Typeface.create(font, Typeface.BOLD) else font
    }

    private fun rounded(color: Int, r: Int = 20) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(getColor(color))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        font = resources.getFont(R.font.thmanyah)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(32)) }

        // Header
        list.addView(text(getString(R.string.app_name), 30f, R.color.emerald, true))
        list.addView(text(getString(R.string.tagline), 15f, R.color.sub).apply { setPadding(0, dp(2), 0, dp(12)) })
        updateBar = text("", 14f, R.color.emerald_dark, true).apply {
            visibility = View.GONE; background = rounded(R.color.gold, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER
        }
        list.addView(updateBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        linkOffBar = text("⚠  " + getString(R.string.links_off), 14f, R.color.card, true).apply {
            visibility = View.GONE; setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFFC62828.toInt()) }
            setOnClickListener { askBrowserRole() }
        }
        list.addView(linkOffBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        Tools.prefs(this).getString("crash", null)?.let { report ->
            val c = card()
            c.addView(text(getString(R.string.crash_t), 16f, R.color.text, true))
            c.addView(text(report.lineSequence().take(4).joinToString("\n"), 12f, R.color.sub))
            val r = LinearLayout(this)
            r.addView(button(getString(R.string.crash_copy)) { copy(report) })
            r.addView(button(getString(R.string.crash_dismiss)) { Tools.prefs(this).edit().remove("crash").apply(); c.visibility = View.GONE })
            c.addView(r)
            list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        // Search + category shortcuts
        val search = android.widget.EditText(this).apply {
            hint = "🔍  " + getString(R.string.search_tools); typeface = font; setSingleLine(); textSize = 16f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) = filter(e.toString())
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        list.addView(search, LinearLayout.LayoutParams(-1, -2))
        val chips = LinearLayout(this)
        list.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        noMatch = text(getString(R.string.search_none), 15f, R.color.sub).apply { visibility = View.GONE; setPadding(dp(4), dp(12), 0, dp(12)) }
        list.addView(noMatch)

        val quick = section(list, "⚡  " + getString(R.string.sec_quick))
        for (k in Tools.ALL) {
            val v = card(k)
            list.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            quick.add(v to (getString(Tools.title(k)) + " " + getString(Tools.desc(k))).lowercase())
        }
        utilityCards(list)
        for ((header, _) in sections) chips.addView(text((header as TextView).text, 14f, R.color.card, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(R.color.emerald, 18)
            setOnClickListener { search.setText(""); scroller.post { scroller.smoothScrollTo(0, header.top) } }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8); topMargin = dp(6) })

        list.addView(button(getString(R.string.check_updates) + "  ·  v" + BuildConfig.VERSION_NAME) { checkUpdate(true) })
        list.addView(text(getString(R.string.battery_hint), 13f, R.color.sub).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        scroller = ScrollView(this).apply { addView(list) }
        setContentView(scroller)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        checkUpdate()
        Updater.scheduleDaily(this)
    }

    private fun card(k: String): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(R.color.card)
            setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dp(2).toFloat()
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(Tools.icon(k)); background = rounded(R.color.emerald, 14)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        top.addView(text(getString(Tools.title(k)), 19f, R.color.text, true).apply { setPadding(dp(12), 0, dp(12), 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            setOnClickListener {
                if (!Tools.toggle(this@MainActivity, k)) {
                    Toast.makeText(this@MainActivity,
                        if (k == Tools.SCREEN) R.string.need_settings else R.string.need_overlay, Toast.LENGTH_LONG).show()
                    Tools.missingPermission(this@MainActivity, k)?.let { startActivity(it) }
                }
                sync()
            }
        }
        if (k == Tools.SOUND) sw.visibility = View.GONE else switches[k] = sw
        top.addView(sw)
        c.addView(top)
        c.addView(text(getString(Tools.desc(k)), 14f, R.color.sub).apply { setPadding(0, dp(8), 0, dp(4)) })

        if (k == Tools.TIMER) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val min = EditText(this).apply {
                hint = getString(R.string.minutes); inputType = InputType.TYPE_CLASS_NUMBER; typeface = font
                setText("5"); setTextColor(getColor(R.color.text))
            }
            row.addView(min, LinearLayout.LayoutParams(dp(90), -2))
            row.addView(button(getString(R.string.countdown)) {
                val m = min.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 5
                if (!Tools.toggle(this, k, m)) Tools.missingPermission(this, k)?.let { startActivity(it) }
                sync()
            })
            c.addView(row)
            c.addView(button("⏱  " + getString(R.string.stopwatch)) {
                if (!Tools.toggle(this, k, -1)) Tools.missingPermission(this, k)?.let { startActivity(it) }
                sync()
            })
        }
        if (k == Tools.SOUND) {
            soundBtn = button("") { Tools.toggle(this, k); sync() }
            c.addView(soundBtn)
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) c.addView(button(getString(R.string.allow_silent)) {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        }
        if (k == Tools.DIM) {
            val bar = android.widget.SeekBar(this).apply {
                max = 70; progress = Tools.prefs(this@MainActivity).getInt("dimLevel", 40) - 10
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, user: Boolean) {
                        Tools.prefs(this@MainActivity).edit().putInt("dimLevel", p + 10).apply()
                        if (Tools.isOn(this@MainActivity, k)) startForegroundService(Intent(this@MainActivity, DimService::class.java))
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            }
            c.addView(bar)
        }
        c.addView(button(getString(R.string.add_tile)) {
            addTile(Tools.tileClass(k), Tools.title(k), Tools.icon(k))
        })
        return c
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; typeface = Typeface.create(font, Typeface.BOLD)
        setTextColor(getColor(R.color.emerald)); background = null
        setOnClickListener { onTap() }
    }

    /** Asks Android to put this tool's tile in the Quick Settings panel (Android 13+). */
    private fun addTile(cls: Class<*>, title: Int, icon: Int) = addQuickTile(cls, title, icon)

    /** Makes Sahl Tools the link opener, so every tapped link is checked first. */
    private fun askBrowserRole() {
        Tools.prefs(this).edit().putBoolean("wantLinks", true).apply()
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                Toast.makeText(this, R.string.links_on, Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return
            }
            if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 5); return
            }
        }
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    /** True when Sahl Tools is the link opener (every tapped link is checked). */
    private fun linksChecked(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)

    private fun sync() {
        // Show the red bar only after the user turned link checking on once (then lost it, e.g. after an update)
        val wanted = Tools.prefs(this).getBoolean("wantLinks", false)
        if (linksChecked()) Tools.prefs(this).edit().putBoolean("wantLinks", true).apply()
        linkOffBar?.visibility = if (wanted && !linksChecked()) View.VISIBLE else View.GONE
        for ((k, s) in switches) s.isChecked = Tools.isOn(this, k)
        soundBtn?.text = getString(R.string.sound_now, SoundMode.name(this))
    }

    /** Scan & share tools: each opens its own screen, or works from Android's Share menu. */
    private fun utilityCards(col: LinearLayout) {
        var current = ArrayList<Pair<View, String>>() as MutableList<Pair<View, String>>
        fun add(title: Int, desc: Int, icon: Int, vararg actions: Pair<Int, () -> Unit>) {
            val c = card()
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(ImageView(this).apply {
                setImageResource(icon); background = rounded(R.color.emerald, 14); setPadding(dp(9), dp(9), dp(9), dp(9))
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            top.addView(text(getString(title), 19f, R.color.text, true).apply { setPadding(dp(12), 0, dp(12), 0) })
            c.addView(top)
            c.addView(text(getString(desc), 14f, R.color.sub).apply { setPadding(0, dp(8), 0, dp(4)) })
            c.addView(actions(*actions.map { (label, act) -> button(getString(label)) { act() } }.toTypedArray()))
            col.addSpaced(c)
            current.add(c to (getString(title) + " " + getString(desc)).lowercase())
        }
        current = section(col, "🔗  " + getString(R.string.sec_links))
        add(R.string.link_t, R.string.link_d, R.drawable.ic_link,
            R.string.open to { startActivity(Intent(this, LinkActivity::class.java)) },
            R.string.check_all_links to { askBrowserRole() },
            R.string.vt_key_title to { LinkSafety.askKey(this) {} },
            R.string.change_opener to { LinkActivity.forgetBrowser(this); Toast.makeText(this, R.string.opener_reset, Toast.LENGTH_SHORT).show() })
        add(R.string.scan_t, R.string.scan_d, R.drawable.ic_scan,
            R.string.open to { startActivity(Intent(this, ScanActivity::class.java)) },
            R.string.add_tile to { addTile(ScanTile::class.java, R.string.scan_t, R.drawable.ic_scan) })
        add(R.string.cg_t, R.string.cg_d, R.drawable.ic_shield,
            R.string.open to { startActivity(Intent(this, CallGuardActivity::class.java)) })
        current = section(col, "📱  " + getString(R.string.sec_apps))
        add(R.string.fr_t, R.string.fr_d, R.drawable.ic_freeze,
            R.string.open to { startActivity(Intent(this, FreezerActivity::class.java)) },
            R.string.add_tile to { addTile(FreezeTile::class.java, R.string.fr_t, R.drawable.ic_freeze) })
        add(R.string.db_t, R.string.db_d, R.drawable.ic_debloat,
            R.string.open to { startActivity(Intent(this, DebloatActivity::class.java)) })
        add(R.string.launcher_t, R.string.launcher_d, R.drawable.ic_apps,
            R.string.open to { startActivity(Intent(this, LauncherActivity::class.java)) })
        add(R.string.deeplink_t, R.string.deeplink_d, R.drawable.ic_link,
            R.string.open to { startActivity(Intent(this, DeepLinkActivity::class.java)) })
        current = section(col, "📶  " + getString(R.string.sec_net))
        add(R.string.wifi_t, R.string.wifi_d, R.drawable.ic_wifi,
            R.string.open to { startActivity(Intent(this, WifiActivity::class.java)) })
        add(R.string.wpass_t, R.string.wpass_d, R.drawable.ic_wifi,
            R.string.open to { startActivity(Intent(this, WifiPassActivity::class.java)) })
        add(R.string.speed_t, R.string.speed_d, R.drawable.ic_speed,
            R.string.open to { startActivity(Intent(this, SpeedActivity::class.java)) })
        add(R.string.data_t, R.string.data_d, R.drawable.ic_data,
            R.string.open to { startActivity(Intent(this, DataActivity::class.java)) })
        current = section(col, "🖼  " + getString(R.string.sec_files))
        add(R.string.photo_t, R.string.photo_d, R.drawable.ic_photo,
            R.string.pick_photos to { startActivity(Intent(this, PickPhotosActivity::class.java)) })
        add(R.string.save_t, R.string.save_d, R.drawable.ic_save)
        current = section(col, "✍  " + getString(R.string.sec_writing))
        add(R.string.pr_t, R.string.pr_d, R.drawable.ic_proof,
            R.string.open to { startActivity(Intent(this, ProofActivity::class.java)) })
        current = section(col, "🎯  " + getString(R.string.sec_focus))
        add(R.string.fo_t, R.string.fo_d, R.drawable.ic_focus,
            R.string.open to { startActivity(Intent(this, FocusActivity::class.java)) })
        add(R.string.light_t, R.string.light_d, R.drawable.ic_light,
            R.string.open to { startActivity(Intent(this, ScreenLightActivity::class.java)) })
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(Tools.CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, f)
        sync()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    /** Checks GitHub for a newer version (Sahl Adhan way): gold card with notes, "Update now" downloads in the browser. */
    private fun checkUpdate(manual: Boolean = false) {
        if (manual) { updateBar.text = Updater.checkingText(this); updateBar.visibility = View.VISIBLE; updateBar.setOnClickListener(null) }
        Thread {
            val r = Updater.fetchNewer()
            runOnUiThread {
                if (r == null) {
                    if (manual) {
                        updateBar.text = Updater.upToDateText(this)
                        Toast.makeText(this, Updater.upToDateText(this), Toast.LENGTH_LONG).show()
                    } else updateBar.visibility = View.GONE
                    return@runOnUiThread
                }
                val notes = if (r.notes.isNotBlank()) "\n" + r.notes else ""
                updateBar.text = Updater.availableText(this, r) + notes + "\n\n⬇  " + Updater.updateNowText(this)
                updateBar.visibility = View.VISIBLE
                updateBar.setOnClickListener {
                    Updater.downloadAndInstall(this, r)
                    Toast.makeText(this, Updater.downloadingText(this), Toast.LENGTH_LONG).show()
                }
                if (manual) {
                    Toast.makeText(this, Updater.availableText(this, r), Toast.LENGTH_LONG).show()
                    (updateBar.parent?.parent as? ScrollView)?.smoothScrollTo(0, 0)
                }
            }
        }.start()
    }
}
