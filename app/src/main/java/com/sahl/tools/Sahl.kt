package com.sahl.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sahl Core — shared by all Sahl apps (theme, language, union messaging).
 * Keep this file identical across Sahl apps so they behave as one family.
 */
object Sahl {
    /** Signature permission: only apps signed with the Sahl key hold it. */
    const val PERMISSION = "com.sahl.permission.UNION"
    /** Broadcast every Sahl app can listen to. Extras: event, from. */
    const val ACTION_EVENT = "com.sahl.action.EVENT"
    const val EXTRA_EVENT = "event"
    const val EXTRA_FROM = "from"
    const val EVENT_CONTACTS_CHANGED = "contacts_changed"

    // Sahl Union data doors (only Sahl-signed apps can open them)
    const val CONTACTS_UNION = "content://com.sahl.contacts.union"
    const val PHONE_UNION = "content://com.sahl.phone.union"
    const val ADHAN_UNION = "content://com.sahl.adhan.union"

    fun prefs(c: Context) = c.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    /** Applies the user's language and theme choice to a context. */
    fun wrap(base: Context): Context {
        val p = prefs(base)
        val lang = p.getString("lang", "system") ?: "system"
        val theme = p.getString("theme", "system") ?: "system"
        val cfg = Configuration(base.resources.configuration)
        if (lang != "system") {
            val l = Locale(lang)
            Locale.setDefault(l)
            cfg.setLocale(l)
            cfg.setLayoutDirection(l)
        }
        val mask = Configuration.UI_MODE_NIGHT_MASK
        when (theme) {
            "light" -> cfg.uiMode = (cfg.uiMode and mask.inv()) or Configuration.UI_MODE_NIGHT_NO
            "dark" -> cfg.uiMode = (cfg.uiMode and mask.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        return base.createConfigurationContext(cfg)
    }

    /** Tell every other Sahl app that something happened. */
    fun announce(c: Context, event: String, extras: Bundle? = null) {
        val i = Intent(ACTION_EVENT).putExtra(EXTRA_EVENT, event).putExtra(EXTRA_FROM, c.packageName)
        if (extras != null) i.putExtras(extras)
        c.sendBroadcast(i, PERMISSION)
    }

    fun countryCode(c: Context) = prefs(c).getString("cc", "974") ?: "974"

    /** The Sahl chime — the shared notification sound of every Sahl app (res/raw/sahl_chime). */
    fun chime(c: Context): Uri = Uri.parse("android.resource://${c.packageName}/raw/sahl_chime")

    /** A notification channel that plays the Sahl chime. */
    fun chimeChannel(c: Context, id: String, name: String, importance: Int = android.app.NotificationManager.IMPORTANCE_HIGH) =
        android.app.NotificationChannel(id, name, importance).apply {
            setSound(chime(c), android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        }

    /** Gentle Sahl chime for silenced incoming calls (res/raw/sahl_silent_call). */
    fun silentCallChime(c: Context): Uri = Uri.parse("android.resource://${c.packageName}/raw/sahl_silent_call")

    /** Dedicated high-priority notification channel for silenced incoming calls with its own gentle Sahl sound. */
    fun silentCallChannel(c: Context, id: String, name: String) =
        android.app.NotificationChannel(id, name, android.app.NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(silentCallChime(c), android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

    /** Saves any crash so the app can show it on the next start (shared Sahl debug helper). */
    fun catchCrashes(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is SahlCrash) return
        Thread.setDefaultUncaughtExceptionHandler(SahlCrash(app, prev))
    }

    fun lastCrash(c: Context): String? = prefs(c).getString("crash", null)
    fun clearCrash(c: Context) = prefs(c).edit().remove("crash").apply()
}

class SahlCrash(private val app: Context, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val v = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" }
            val log = "v$v\n" + android.util.Log.getStackTraceString(e).take(6000)
            Sahl.prefs(app).edit().putString("crash", log).commit()
            // Show the report in its own screen (works even when the main screen crashes on open)
            app.startActivity(Intent(app, CrashActivity::class.java).putExtra("log", log)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        } catch (x: Exception) { prev?.uncaughtException(t, e) }
    }
}

/** Plain crash-report screen (no Sahl UI code, so it cannot crash itself). */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = intent.getStringExtra("log") ?: Sahl.lastCrash(this) ?: "No report"
        Sahl.clearCrash(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        box.addView(TextView(this).apply { text = "Sahl crash report"; textSize = 22f })
        box.addView(android.widget.Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                android.widget.Toast.makeText(this@CrashActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(android.widget.ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply { text = log; textSize = 11f; setTextIsSelectable(true) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(box)
    }
}

/** Arabic-aware text and phone-number normalisation. */
object Norm {
    private val marks = Regex("[ً-ْٰـ]")

    fun text(s: String): String {
        val t = marks.replace(s, "").lowercase()
        val sb = StringBuilder(t.length)
        for (ch in t) sb.append(
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                else -> ch
            }
        )
        return sb.toString()
    }

    fun digits(s: String) = text(s).filter { it in '0'..'9' }

    /** Same number written as +974 5555 1234, 0097455551234 or 55551234 gives the same key. */
    fun phoneKey(s: String): String {
        val d = digits(s)
        return if (d.length > 8) d.takeLast(8) else d
    }

    /** International digits for WhatsApp links. */
    fun international(s: String, cc: String): String {
        var d = digits(s)
        val plus = text(s).trim().startsWith("+")
        if (d.startsWith("00")) return d.drop(2)
        if (plus) return d
        if (d.startsWith("0")) d = d.drop(1)
        return if (d.length <= 9) cc + d else d
    }

    fun section(name: String): String {
        val ch = text(name).trimStart().firstOrNull() ?: return "#"
        return when {
            ch in 'a'..'z' -> ch.uppercaseChar().toString()
            ch in 'ء'..'ي' -> ch.toString()
            else -> "#"
        }
    }

    fun group(section: String) = when {
        section[0] in 'A'..'Z' -> 0
        section == "#" -> 2
        else -> 1
    }
}

/** Small async thumbnail loader with a memory cache. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(c: Context, uri: String?, iv: ImageView, letter: TextView) {
        iv.tag = uri
        if (uri == null) { iv.setImageDrawable(null); letter.visibility = View.VISIBLE; return }
        val hit = cache.get(uri)
        if (hit != null) { iv.setImageBitmap(hit); letter.visibility = View.GONE; return }
        iv.setImageDrawable(null); letter.visibility = View.VISIBLE
        val app = c.applicationContext
        pool.execute {
            val b = try {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (b != null) {
                cache.put(uri, b)
                main.post { if (iv.tag == uri) { iv.setImageBitmap(b); letter.visibility = View.GONE } }
            }
        }
    }

    fun clear() = cache.evictAll()
}

/** Base for every screen: Sahl theme, font and small view helpers. */
/** Base for every screen: modern Sahl look, font and view helpers. Shared by all Sahl apps. */
abstract class SahlActivity : Activity() {
    val dp get() = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    fun col(id: Int) = getColor(id)
    val font: Typeface? by lazy { try { resources.getFont(R.font.thmanyah) } catch (e: Exception) { null } }
    val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** True while the crash report is on screen — screens wait before loading data. */
    var crashOpen = false
    open fun onCrashClosed() {}

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Sahl.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Sahl.catchCrashes(this)
        super.onCreate(savedInstanceState)
        Sahl.lastCrash(this)?.let { log ->
            Sahl.clearCrash(this)
            crashOpen = true
            android.app.AlertDialog.Builder(this).setTitle("Sahl crash report")
                .setMessage(log.take(1500))
                .setPositiveButton("Copy") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                }
                .setNegativeButton("Close", null)
                .setOnDismissListener { crashOpen = false; onCrashClosed() }
                .show()
        }
        systemBars(col(R.color.bg), !night)
    }

    /** Colour the status/navigation bars and pick dark or light icons. */
    @Suppress("DEPRECATION")
    fun systemBars(color: Int, lightIcons: Boolean) {
        window.statusBarColor = color
        window.navigationBarColor = color
        val decor = window.decorView   // makes sure the window's decor exists before touching its insets
        if (Build.VERSION.SDK_INT >= 30) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            try { decor.windowInsetsController?.setSystemBarsAppearance(if (lightIcons) f else 0, f) } catch (e: Exception) { }
        } else {
            var v = window.decorView.systemUiVisibility
            v = if (lightIcons) v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else v and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.decorView.systemUiVisibility = v
        }
    }

    fun text(s: CharSequence, size: Float = 15f, color: Int = col(R.color.text), bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun round(color: Int, radius: Float = 20f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        if (stroke != 0) setStroke(px(1), stroke)
    }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Touch ripple on any view (rounded or circular). */
    fun <T : View> T.press(radius: Float = 20f, circle: Boolean = false): T {
        val mask = if (circle) oval(0xFF000000.toInt()) else round(0xFF000000.toInt(), radius)
        foreground = RippleDrawable(ColorStateList.valueOf(col(R.color.ripple)), null, mask)
        isClickable = true; isFocusable = true
        return this
    }

    fun icon(res: Int, color: Int = col(R.color.text), sizeDp: Int = 24) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** Round icon button: tonal circle with an icon. */
    fun iconButton(res: Int, bg: Int = col(R.color.tonal), tint: Int = col(R.color.emerald), sizeDp: Int = 44,
                   iconDp: Int = 22, go: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = oval(bg)
        addView(ImageView(this@SahlActivity).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        }, FrameLayout.LayoutParams(px(iconDp), px(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
        setOnClickListener { go() }
        press(circle = true)
    }

    /** Card container with soft background. */
    fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(col(R.color.card), 22f)
        setPadding(px(16), px(14), px(16), px(14))
        elevation = 1f * dp
    }

    /** Small gold section title. */
    fun label(s: String) = text(s, 13f, col(R.color.emerald), true).apply { setPaddingRelative(px(4), px(2), 0, px(8)) }

    /** Calm avatar colour picked from the name. */
    fun avatarColor(name: String): Int {
        val palette = intArrayOf(0xFF2F6F5E.toInt(), 0xFF5B7C6F.toInt(), 0xFF7A6A43.toInt(),
            0xFF4E6A80.toInt(), 0xFF6D5A73.toInt(), 0xFF3F7A7A.toInt())
        return palette[(name.hashCode() and 0x7fffffff) % palette.size]
    }

    /** Round avatar: photo if any, otherwise the first letter. */
    fun avatar(sizeDp: Int): Triple<FrameLayout, ImageView, TextView> {
        val f = FrameLayout(this)
        val letter = text("", sizeDp * 0.4f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(letter, FrameLayout.LayoutParams(-1, -1))
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        f.clipToOutline = true
        f.layoutParams = ViewGroup.LayoutParams(px(sizeDp), px(sizeDp))
        return Triple(f, iv, letter)
    }

    /**
     * Modern top bar: optional back button, large title, icon actions.
     * Actions are (drawable id or text) to click handler.
     */
    fun header(title: String, back: Boolean, actions: List<Pair<Any, () -> Unit>> = emptyList(), large: Boolean = !back): LinearLayout {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(if (back) 8 else 16), px(14), px(12), px(8))
        }
        if (back) {
            bar.addView(iconButton(R.drawable.ic_back, 0, col(R.color.text)) { finish() })
        } else {
            val seal = text("۞", 19f, col(R.color.gold), true).apply {
                gravity = Gravity.CENTER
                background = round(col(R.color.tonal), 13f, col(R.color.gold))
                setOnClickListener { openSahlSuiteSwitcher() }
            }.press(13f)
            bar.addView(seal, LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(10) })
        }
        val cleanTitle = title.removePrefix("۞").trimStart()
        bar.addView(text(cleanTitle, if (large) 28f else 20f, col(R.color.text), true).apply {
            setPadding(px(if (back) 6 else 0), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        for ((a, go) in actions) {
            val v = if (a is Int) iconButton(a, 0, col(R.color.text), 44, 22, go)
            else text(a.toString(), 20f).apply { setPadding(px(12), px(8), px(12), px(8)); setOnClickListener { go() } }.press(16f)
            bar.addView(v)
        }
        val occ = if (!back) sahlOccasionText() else null
        if (occ == null) return bar
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(text(occ, 12.5f, col(R.color.emerald), true).apply {
                gravity = Gravity.CENTER
                background = round(col(R.color.tonal), 12f, col(R.color.gold))
                setPadding(px(12), px(6), px(12), px(6))
            }, LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = px(16); marginEnd = px(16); bottomMargin = px(6)
            })
        }
    }

    private fun sahlOccasionText(): String? {
        val ar = resources.configuration.locales[0].language == "ar"
        try {
            val h = java.time.chrono.HijrahDate.now()
            val m = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
            val d = h.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
            when {
                m == 9 -> return if (ar) "🌙  رمضان مبارك · تقبل الله صيامكم" else "🌙  Ramadan Mubarak"
                m == 10 && d in 1..3 -> return if (ar) "✨  عيد فطر مبارك · تقبل الله منا ومنكم" else "✨  Eid al-Fitr Mubarak"
                m == 12 && d == 9 -> return if (ar) "🤲  يوم عرفة المبارك" else "🤲  Blessed Day of Arafah"
                m == 12 && d in 10..13 -> return if (ar) "✨  عيد أضحى مبارك" else "✨  Eid al-Adha Mubarak"
                m == 1 && d == 1 -> return if (ar) "۞  عام هجري مبارك" else "۞  Blessed Hijri New Year"
                m == 1 && d == 10 -> return if (ar) "۞  عاشوراء المبارك" else "۞  Day of Ashura"
            }
        } catch (_: Exception) {}
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY) {
            return if (ar) "۞  جمعة مباركة · لا تنسَ سورة الكهف" else "۞  Jumu'ah Mubarak · Blessed Friday"
        }
        return null
    }

    /** Opens SahlHome's floating Sahl Apps popup, or falls back to an in-app Sahl Suite bottom sheet. */
    fun openSahlSuiteSwitcher() {
        try {
            val i = Intent().apply {
                setClassName("com.sahl.home", "com.sahl.home.SahlPopupActivity")
                putExtra("folder", "all_sahl")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (_: Exception) {
            showSahlSuiteSheet()
        }
    }

    private fun showSahlSuiteSheet() {
        val pkgs = listOf(
            "com.sahl.home",
            "com.sahl.phone",
            "com.sahl.contacts",
            "com.sahl.messages",
            "com.sahl.adhan",
            "com.aly.trueqibla",
            "com.sahl.qibla",
            "com.sahl.files",
            "com.sahl.gallery",
            "com.sahl.camera",
            "com.sahl.pdf",
            "com.sahl.tools",
            "com.sahl.timer",
            "com.aly.sahlsync"
        )
        val pm = packageManager
        val ar = resources.configuration.locales[0].language == "ar"
        val installed = pkgs.mapNotNull { pkg ->
            if (pkg == packageName) return@mapNotNull null
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            val appIcon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            Triple(label, appIcon, launch)
        }
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(col(R.color.card), 26f, col(R.color.gold))
            setPadding(px(16), px(18), px(16), px(16))
        }
        box.addView(text(if (ar) "۞  تطبيقات سهل" else "۞  Sahl Suite", 19f, col(R.color.emerald), true).apply {
            setPadding(px(6), 0, px(6), px(12))
        })
        if (installed.isEmpty()) {
            box.addView(text(if (ar) "لا توجد تطبيقات سهل أخرى مثبتة" else "No other Sahl apps installed", 14f, col(R.color.sub)).apply {
                setPadding(px(6), px(4), px(6), px(8))
            })
        } else {
            val scroll = android.widget.ScrollView(this)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for ((label, appIcon, launch) in installed) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(10), px(9), px(10), px(9))
                    setOnClickListener {
                        d.dismiss()
                        try { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }
                }.press(16f)
                if (appIcon != null) {
                    row.addView(ImageView(this).apply { setImageDrawable(appIcon) },
                        LinearLayout.LayoutParams(px(34), px(34)).apply { marginEnd = px(12) })
                }
                row.addView(text(label, 15.5f, col(R.color.text), true), LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(row)
            }
            scroll.addView(list)
            box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(-1, -2)
            attributes.y = px(16)
        }
        d.show()
    }

    /** Pill search box with a magnifier icon. */
    fun searchBox(hintText: String, onChange: (String) -> Unit): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(col(R.color.tonal), 26f)
            setPadding(px(16), 0, px(8), 0)
        }
        box.addView(icon(R.drawable.ic_search, col(R.color.sub), 20))
        val et = EditText(this).apply {
            hint = hintText; textSize = 16f; typeface = font; isSingleLine = true; background = null
            setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub))
            setPadding(px(10), px(13), px(10), px(13))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
            })
        }
        box.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = iconButton(R.drawable.ic_close, 0, col(R.color.sub), 36, 18) { et.setText("") }
        clear.visibility = View.GONE
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
        box.addView(clear)
        return box to et
    }

    /** Floating round action button. */
    fun fab(res: Int, go: () -> Unit) = iconButton(res, col(R.color.emerald), 0xFFFFFFFF.toInt(), 58, 26, go).apply { elevation = 6f * dp }

    /** Bottom sheet with icon actions. Items: (icon, label, action). */
    fun sheet(title: String?, items: List<Triple<Int, String, () -> Unit>>) {
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(col(R.color.card)); val r = 26f * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(px(8), px(10), px(8), px(24))
        }
        box.addView(View(this).apply { background = round(col(R.color.line), 3f) },
            LinearLayout.LayoutParams(px(36), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(10) })
        if (title != null) box.addView(text(title, 16f, col(R.color.sub), true).apply {
            setPadding(px(16), px(4), px(16), px(10)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        for ((ic, label, go) in items) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(14), px(16), px(14))
                setOnClickListener { d.dismiss(); go() }
            }.press(16f)
            row.addView(icon(ic, col(R.color.emerald), 22))
            row.addView(text(label, 16f).apply { setPadding(px(18), 0, 0, 0) })
            box.addView(row)
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.attributes = w.attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        d.show()
    }

    fun toast(id: Int) = android.widget.Toast.makeText(this, id, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * List row you can swipe: right = first action (e.g. call), left = second (e.g. message).
 * Works inside ListView/ScrollView without blocking vertical scrolling.
 */
class SwipeRow(
    ctx: Context, val content: View, bg: Int,
    rightIcon: Int, rightColor: Int, private val onRight: () -> Unit,
    leftIcon: Int, leftColor: Int, private val onLeft: () -> Unit
) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false; private var armed = false
    private val rightBg = side(rightIcon, rightColor, Gravity.START or Gravity.CENTER_VERTICAL)
    private val leftBg = side(leftIcon, leftColor, Gravity.END or Gravity.CENTER_VERTICAL)

    private fun side(icon: Int, color: Int, g: Int) = FrameLayout(context).apply {
        setBackgroundColor(color); visibility = View.INVISIBLE
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()) },
            LayoutParams((26 * d).toInt(), (26 * d).toInt(), g).apply { marginStart = (24 * d).toInt(); marginEnd = (24 * d).toInt() })
    }

    init {
        addView(rightBg, LayoutParams(-1, -1)); addView(leftBg, LayoutParams(-1, -1))
        content.setBackgroundColor(bg)
        addView(content, LayoutParams(-1, -2))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; armed = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!dragging && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        val th = width * 0.28f
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                content.translationX = dx
                rightBg.visibility = if (dx > 0) View.VISIBLE else View.INVISIBLE
                leftBg.visibility = if (dx < 0) View.VISIBLE else View.INVISIBLE
                val now = Math.abs(dx) > th
                if (now != armed) { armed = now; if (now) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = content.translationX
                if (e.actionMasked == MotionEvent.ACTION_UP) { if (dx > th) onRight() else if (dx < -th) onLeft() }
                content.animate().translationX(0f).setDuration(180).withEndAction {
                    rightBg.visibility = View.INVISIBLE; leftBg.visibility = View.INVISIBLE
                }.start()
                dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
