package com.sahl.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The popup that appears when you tap or share a link (like URLCheck):
 * live steps, tracking codes you can tick on/off, redirects, warning signs,
 * a live VirusTotal scan, then Open / Open with / Copy / Share.
 */
class LinkPopupActivity : Activity() {
    private lateinit var urlView: TextView
    private lateinit var hostView: TextView
    private lateinit var steps: LinearLayout
    private lateinit var params: LinearLayout
    private lateinit var safetyBox: LinearLayout
    private lateinit var vtView: TextView
    private lateinit var vtButtons: LinearLayout

    private var base = ""                                   // address without the ?query part
    private val query = ArrayList<Triple<String, String, CheckBox>>()  // name, value, "remove" box
    private var fragment: String? = null
    private var scanned = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val input = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val first = LinkCleaner.findUrl(input)
        if (first == null) { startActivity(Intent(this, LinkActivity::class.java).putExtra(Intent.EXTRA_TEXT, input)); finish(); return }
        build()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        start(first)
    }

    // ───── layout ─────
    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(R.color.card, 24)
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        col.addView(row(
            label("🔗  " + getString(R.string.link_t), 15f, R.color.emerald, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            label("✕", 18f, R.color.sub).apply { setPadding(dp(12), 0, dp(4), 0); setOnClickListener { finish() } }))
        hostView = label("", 22f, R.color.text, true).apply { setPadding(0, dp(6), 0, 0) }
        urlView = label("", 13f, R.color.sub).apply { setTextIsSelectable(true); maxLines = 4 }
        col.addView(hostView); col.addView(urlView)

        steps = section(col, R.string.pop_steps)
        params = section(col, R.string.pop_tracking)
        safetyBox = section(col, R.string.safety_t)
        vtView = label("", 15f, bold = true)
        vtButtons = LinearLayout(this)
        safetyBox.addView(vtView); safetyBox.addView(vtButtons)

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        actions.addView(action(getString(R.string.open), true) { LinkActivity.openInBrowser(this, current()) { finish() } })
        actions.addView(action(getString(R.string.open_with), false) { LinkActivity.openInBrowser(this, current(), ask = true) { finish() } })
        actions.addView(action(getString(R.string.copy), false) { copy(current()) })
        actions.addView(action(getString(R.string.share), false) { shareText(current()) })
        col.addView(actions)
        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true })
    }

    private fun section(parent: LinearLayout, title: Int): LinearLayout {
        parent.addView(View(this).apply { setBackgroundColor(getColor(R.color.sub)); alpha = 0.2f },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        parent.addView(label(getString(title), 13f, R.color.emerald, true))
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { parent.addView(it) }
    }

    private fun action(text: String, main: Boolean, onTap: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create(sahlFont(), Typeface.BOLD)
        setTextColor(getColor(if (main) R.color.card else R.color.emerald))
        background = if (main) rounded(R.color.emerald, 20) else null
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }
    }

    private fun step(text: String) = label(text, 14f).also { steps.addView(it) }

    // ───── checking ─────
    private fun start(url: String) {
        show(url)
        val s1 = step("⏳  " + getString(R.string.pop_following))
        Thread {
            val hops = try { LinkCleaner.chain(url) } catch (_: Exception) { listOf(url) }
            runOnUiThread {
                if (hops.size > 1) {
                    s1.text = "↪  " + getString(R.string.pop_redirects, hops.size - 1)
                    for (h in hops.drop(1)) steps.addView(label("   → " + Uri.parse(h).host, 13f, R.color.sub))
                } else s1.text = "✓  " + getString(R.string.pop_no_redirect)
                show(hops.last())
                val n = query.count { it.third.isChecked }
                step(if (n > 0) "✂  " + getString(R.string.pop_removed_n, n) else "✓  " + getString(R.string.already_clean))
                safety()
            }
        }.start()
    }

    /** Splits the address into its parts and lists every ?parameter with a tick box. */
    private fun show(url: String) {
        val u = Uri.parse(url)
        base = url.substringBefore('#').substringBefore('?')
        fragment = u.encodedFragment
        query.clear(); params.removeAllViews()
        for (name in u.queryParameterNames) for (value in u.getQueryParameters(name)) {
            val tracking = LinkCleaner.isTracking(name)
            val box = CheckBox(this).apply {
                isChecked = tracking
                text = (if (tracking) "🎯 " else "") + name + " = " + value.take(40)
                typeface = sahlFont(); textSize = 13f; setTextColor(getColor(R.color.text))
                setOnCheckedChangeListener { _, _ -> refresh() }
            }
            query.add(Triple(name, value, box))
            params.addView(box)
        }
        if (query.isEmpty()) params.addView(label("✓  " + getString(R.string.pop_no_params), 14f, R.color.sub))
        else params.addView(row(
            sahlButton(getString(R.string.pop_remove_all)) { query.forEach { it.third.isChecked = true } },
            sahlButton(getString(R.string.pop_keep_all)) { query.forEach { it.third.isChecked = false } }))
        hostView.text = u.host ?: url
        refresh()
    }

    /** The address with the ticked parameters removed — updates live as boxes change. */
    private fun current(): String {
        val b = Uri.parse(base).buildUpon()
        for ((n, v, box) in query) if (!box.isChecked) b.appendQueryParameter(n, v)
        fragment?.let { b.encodedFragment(it) }
        return b.build().toString()
    }

    private fun refresh() {
        urlView.text = current()
    }

    private fun safety() {
        val url = current()
        val w = LinkSafety.warnings(url)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (w.isEmpty()) box.addView(label("✓  " + getString(R.string.ws_none), 14f))
        else for (r in w) box.addView(label("⚠  " + getString(r), 14f).apply { setTextColor(0xFFD08A00.toInt()) })
        safetyBox.addView(box, 0)
        vtButtons.addView(sahlButton(getString(R.string.vt_scan)) { scan() })
        vtButtons.addView(sahlButton(getString(R.string.vt_report)) { LinkActivity.openInBrowser(this, LinkSafety.vtReport(current())) })
        if (LinkSafety.key(this).isNotEmpty()) scan()
        else vtView.text = getString(R.string.vt_no_key)
    }

    private fun scan() {
        val key = LinkSafety.key(this)
        if (key.isEmpty()) { LinkSafety.askKey(this) { scan() }; return }
        val url = current()
        if (scanned == url) return
        scanned = url
        vtView.setTextColor(getColor(R.color.text)); vtView.text = "⏳  " + getString(R.string.vt_scanning)
        Thread {
            var msg: String; var bad = false
            try {
                val v = LinkSafety.virusTotal(key, url) { n -> runOnUiThread { vtView.text = "⏳  " + getString(R.string.vt_live, n * 5) } }
                msg = when {
                    v == null -> getString(R.string.vt_slow)
                    v.malicious + v.suspicious > 0 -> { bad = true; "⛔  " + getString(R.string.vt_bad, v.malicious + v.suspicious, v.total) }
                    else -> "✓  " + getString(R.string.vt_clean, v.total)
                }
            } catch (e: SecurityException) { msg = getString(R.string.vt_bad_key) }
              catch (e: Exception) { msg = getString(R.string.vt_error) }
            runOnUiThread {
                if (!bad && !msg.startsWith("✓")) scanned = ""   // allow trying again
                vtView.text = msg
                vtView.setTextColor(if (bad) 0xFFC62828.toInt() else getColor(R.color.emerald))
                if (bad) hostView.setTextColor(0xFFC62828.toInt())
            }
        }.start()
    }
}
