package com.sahl.tools

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Link Cleaner & Checker. Opens in three ways:
 *  • from the app — paste any number of links and press Check
 *  • from Share — the shared text is checked straight away
 *  • by tapping a link anywhere (choose "Sahl Tools" as the opener) — checked, then opened in your browser
 */
class LinkActivity : Activity() {
    private lateinit var results: LinearLayout
    private lateinit var box: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val input = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        val col = sahlScreen(getString(R.string.link_t))
        box = EditText(this).apply {
            hint = getString(R.string.paste_hint); typeface = sahlFont(); minLines = 3; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.text)); setText(input)
        }
        col.addSpaced(card().apply {
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(row(
                sahlButton(getString(R.string.paste_link)) {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@LinkActivity)?.toString() ?: ""
                    box.setText(if (box.text.isBlank()) t else box.text.toString() + "\n" + t)
                    check()
                },
                sahlButton(getString(R.string.check)) { check() },
                sahlButton(getString(R.string.clear)) { box.setText(""); results.removeAllViews() }))
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)
        if (input.isNotBlank()) check()
    }

    private fun check() {
        results.removeAllViews()
        val urls = LinkCleaner.findAll(box.text.toString())
        if (urls.isEmpty()) { results.addView(label(getString(R.string.no_link), 16f, R.color.sub)); return }
        // Auto-scan with VirusTotal only when there are a few links (the free key allows 4 scans a minute)
        for (u in urls) results.addSpaced(linkCard(u, autoScan = urls.size <= 3))
    }

    private fun linkCard(url: String, autoScan: Boolean) = card().apply {
        val result = label(getString(R.string.working), 17f, R.color.emerald, true).apply { setTextIsSelectable(true) }
        addView(label(url, 13f, R.color.sub))
        addView(result)
        val body = LinearLayout(this@LinkActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(body)
        Thread {
            val expanded = try { LinkCleaner.expand(url) } catch (_: Exception) { url }
            val clean = LinkCleaner.clean(expanded)
            runOnUiThread {
                result.text = clean
                result.setOnClickListener { openChecked(clean) }
                val removed = url.length - clean.length
                if (expanded != url) body.addView(label("↪  " + getString(R.string.was_short), 13f, R.color.sub))
                if (clean != expanded) body.addView(label("✂  " + getString(R.string.tracking_removed), 13f, R.color.sub))
                else if (removed == 0) body.addView(label("✓  " + getString(R.string.already_clean), 13f, R.color.sub))
                body.addView(row(sahlButton(getString(R.string.copy)) { copy(clean) },
                    sahlButton(getString(R.string.share)) { shareText(clean) },
                    sahlButton(getString(R.string.open)) { openChecked(clean) }))
                safety(body, clean, autoScan)
            }
        }.start()
    }

    /** Warning signs found on the phone, plus a VirusTotal scan. */
    private fun safety(into: LinearLayout, url: String, autoScan: Boolean) {
        into.addView(label(getString(R.string.safety_t), 16f, R.color.emerald, true).apply { setPadding(0, dp(8), 0, 0) })
        val w = LinkSafety.warnings(url)
        if (w.isEmpty()) into.addView(label("✓  " + getString(R.string.ws_none), 14f))
        else for (r in w) into.addView(label("⚠  " + getString(r), 14f).apply { setPadding(0, dp(2), 0, dp(2)) })
        val vt = label("", 15f, bold = true).apply { setPadding(0, dp(6), 0, 0) }
        into.addView(vt)
        val scan = sahlButton(getString(R.string.vt_scan)) {}
        scan.setOnClickListener {
            val key = LinkSafety.key(this)
            if (key.isEmpty()) { LinkSafety.askKey(this) { scan.performClick() }; return@setOnClickListener }
            vt.setTextColor(getColor(R.color.text)); vt.text = getString(R.string.vt_scanning); scan.isEnabled = false
            Thread {
                var msg: String; var bad = false
                try {
                    val v = LinkSafety.virusTotal(key, url)
                    msg = when {
                        v == null -> getString(R.string.vt_slow)
                        v.malicious + v.suspicious > 0 -> { bad = true; "⛔  " + getString(R.string.vt_bad, v.malicious + v.suspicious, v.total) }
                        else -> "✓  " + getString(R.string.vt_clean, v.total)
                    }
                } catch (e: SecurityException) { msg = getString(R.string.vt_bad_key) }
                  catch (e: Exception) { msg = getString(R.string.vt_error) }
                runOnUiThread {
                    scan.isEnabled = true; vt.text = msg
                    vt.setTextColor(if (bad) 0xFFC62828.toInt() else getColor(R.color.emerald))
                }
            }.start()
        }
        into.addView(row(scan, sahlButton(getString(R.string.vt_site)) { openInBrowser(this, LinkSafety.vtWebsite(url)) }))
        if (autoScan && LinkSafety.key(this).isNotEmpty()) scan.performClick()
    }

    private fun openChecked(url: String) = openInBrowser(this, url)

    companion object {
        /** Opens a link in a real browser — never back into Sahl Tools. Remembers the browser you pick. */
        fun openInBrowser(c: Context, url: String, ask: Boolean = false) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val browsers = c.packageManager.queryIntentActivities(view, 0)
                .filter { it.activityInfo.packageName != c.packageName }
            val saved = Tools.prefs(c).getString("browser", null)
            val pick = if (ask) null else browsers.firstOrNull { it.activityInfo.packageName == saved } ?: browsers.singleOrNull()
            fun go(p: android.content.pm.ResolveInfo) = c.startActivity(Intent(view)
                .setComponent(ComponentName(p.activityInfo.packageName, p.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            when {
                pick != null -> go(pick)
                browsers.isEmpty() -> c.copy(url)
                else -> {
                    val pm = c.packageManager
                    AlertDialog.Builder(c).setTitle(R.string.pick_browser)
                        .setItems(browsers.map { it.loadLabel(pm) }.toTypedArray()) { _, i ->
                            if (!ask) Tools.prefs(c).edit().putString("browser", browsers[i].activityInfo.packageName).apply()
                            go(browsers[i])
                        }.show()
                }
            }
        }
    }
}
