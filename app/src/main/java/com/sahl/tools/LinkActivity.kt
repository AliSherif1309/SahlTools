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
        fun render(clean: String, expandedFrom: String?) {
            body.removeAllViews()
            result.text = clean
            result.setOnClickListener { openChecked(clean) }
            val removed = url.length - clean.length
            if (expandedFrom != null && expandedFrom != url) body.addView(label("↪  " + getString(R.string.was_short), 13f, R.color.sub))
            if (clean != (expandedFrom ?: url)) body.addView(label("✂  " + getString(R.string.tracking_removed), 13f, R.color.sub))
            else if (removed == 0) body.addView(label("✓  " + getString(R.string.already_clean), 13f, R.color.sub))
            body.addView(row(sahlButton(getString(R.string.copy)) { copy(clean) },
                sahlButton(getString(R.string.share)) { shareText(clean) },
                sahlButton(getString(R.string.open)) { openChecked(clean) }))
            safety(body, clean, autoScan) { fixed -> render(fixed, expandedFrom) }
        }
        Thread {
            val expanded = try { LinkCleaner.expand(url) } catch (_: Exception) { url }
            val clean = LinkCleaner.clean(expanded)
            runOnUiThread { render(clean, expanded) }
        }.start()
    }

    /** Warning signs found on the phone, plus a VirusTotal scan. */
    private fun safety(into: LinearLayout, url: String, autoScan: Boolean, onFix: (String) -> Unit) {
        into.addView(label(getString(R.string.safety_t), 16f, R.color.emerald, true).apply { setPadding(0, dp(8), 0, 0) })
        val w = LinkSafety.warnings(url)
        if (w.isEmpty()) into.addView(label("✓  " + getString(R.string.ws_none), 14f))
        else for (r in w) {
            into.addView(label("⚠  " + getString(r), 14f).apply { setPadding(0, dp(2), 0, dp(2)) })
            LinkSafety.fixFor(r, url)?.let { fix ->
                into.addView(sahlButton(getString(fix.labelId)) { onFix(fix.apply(url)) })
            }
            if (r == R.string.ws_short) {
                val expandBtn = sahlButton(getString(R.string.ws_fix_expand)) {}
                expandBtn.setOnClickListener {
                    expandBtn.isEnabled = false
                    Thread {
                        val expanded = try { LinkCleaner.expand(url) } catch (_: Exception) { url }
                        val clean = LinkCleaner.clean(expanded)
                        runOnUiThread {
                            expandBtn.isEnabled = true
                            if (clean != url) onFix(clean)
                            else android.widget.Toast.makeText(this, R.string.ws_expand_fail, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
                into.addView(expandBtn)
            }
        }
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
        /**
         * Opens a link outside Sahl Tools. Uses your remembered app if you chose "Always";
         * otherwise shows Sahl's own picker with every browser and app that can open it.
         */
        fun openInBrowser(c: Context, url: String, ask: Boolean = false, done: () -> Unit = {}) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val pm = c.packageManager
            // MATCH_ALL: without it Android lists only the default opener (Sahl Tools itself)
            val apps = pm.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_ALL)
                .filter { it.activityInfo.packageName != c.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedWith(compareBy({ !isBrowser(c, it.activityInfo.packageName) }, { it.loadLabel(pm).toString() }))
            fun go(p: android.content.pm.ResolveInfo) = try {
                c.startActivity(Intent(view).setComponent(ComponentName(p.activityInfo.packageName, p.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { c.copy(url) }.also { done() }
            val saved = Tools.prefs(c).getString("browser", null)
            val remembered = apps.firstOrNull { it.activityInfo.packageName == saved }
            when {
                apps.isEmpty() -> c.copy(url)
                !ask && remembered != null -> go(remembered)
                else -> picker(c, url, apps) { p, always ->
                    if (always) Tools.prefs(c).edit().putString("browser", p.activityInfo.packageName).apply()
                    go(p)
                }
            }
        }

        private fun isBrowser(c: Context, pkg: String) = c.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), android.content.pm.PackageManager.MATCH_ALL)
            .any { it.activityInfo.packageName == pkg }

        /** Sahl's own "Open with" sheet: app icons and names, plus "Always use this app". */
        private fun picker(c: Context, url: String, apps: List<android.content.pm.ResolveInfo>,
                           chosen: (android.content.pm.ResolveInfo, Boolean) -> Unit) {
            val pm = c.packageManager
            val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
            val always = android.widget.CheckBox(c).apply {
                text = c.getString(R.string.always_use); typeface = c.sahlFont(); setTextColor(c.getColor(R.color.text))
            }
            list.addView(c.label(Uri.parse(url).host ?: url, 13f, R.color.sub).apply { setPadding(0, 0, 0, c.dp(6)) })
            var dialog: AlertDialog? = null
            for (p in apps) {
                val row = LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(8), c.dp(10), c.dp(8), c.dp(10))
                    isClickable = true; background = c.getDrawable(android.R.drawable.list_selector_background)
                    setOnClickListener { dialog?.dismiss(); chosen(p, always.isChecked) }
                }
                row.addView(android.widget.ImageView(c).apply { setImageDrawable(p.loadIcon(pm)) }, LinearLayout.LayoutParams(c.dp(36), c.dp(36)))
                row.addView(c.label(p.loadLabel(pm), 16f).apply { setPadding(c.dp(14), 0, 0, 0) })
                list.addView(row)
            }
            list.addView(always)
            dialog = c.sahlDialog(c.getString(R.string.open_with), list, c.getString(android.R.string.cancel) to { d: android.app.AlertDialog -> d.dismiss() })
        }

        /** Forget the remembered app so the picker shows again. */
        fun forgetBrowser(c: Context) = Tools.prefs(c).edit().remove("browser").apply()
    }
}
