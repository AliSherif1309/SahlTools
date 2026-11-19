package com.sahl.tools

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** A saved Wi-Fi network. password = null means open (no password) or not readable (enterprise). */
data class SavedWifi(val ssid: String, val password: String?, val type: String)

/** Reads saved Wi-Fi networks with passwords through Shizuku (Android does not allow normal apps to read them). */
object WifiPasswords {
    /** 0 = Shizuku not running, 1 = running but not allowed yet, 2 = ready */
    fun state(): Int = try {
        when {
            !Shizuku.pingBinder() -> 0
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
            else -> 1
        }
    } catch (_: Throwable) { 0 }

    @Suppress("DEPRECATION")
    fun list(): List<SavedWifi> {
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("wifi"))
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val wm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val m = wm.javaClass.methods.first { it.name == "getPrivilegedConfiguredNetworks" }
        val pkg = if (Shizuku.getUid() == 0) "android" else "com.android.shell"
        var firstString = true
        val args = m.parameterTypes.map { t ->
            when (t) {
                String::class.java -> if (firstString) { firstString = false; pkg } else null
                Bundle::class.java -> Bundle().apply {
                    if (Build.VERSION.SDK_INT >= 31) putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE",
                        android.content.AttributionSource.Builder(Shizuku.getUid()).setPackageName(pkg).build())
                }
                else -> null
            }
        }
        val res = m.invoke(wm, *args.toTypedArray()) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val configs = res.javaClass.getMethod("getList").invoke(res) as List<WifiConfiguration>
        return configs.map { c ->
            val ssid = c.SSID?.trim('"') ?: "?"
            val psk = c.preSharedKey?.takeIf { it.isNotEmpty() && it != "*" }?.trim('"')
            val wep = c.wepKeys?.firstOrNull { !it.isNullOrEmpty() }?.trim('"')
            val type = when {
                psk != null -> "WPA"
                wep != null -> "WEP"
                c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP) -> "EAP"
                else -> "nopass"
            }
            SavedWifi(ssid, psk ?: wep, type)
        }.distinctBy { it.ssid }.sortedBy { it.ssid.lowercase() }
    }

    /** The standard Wi-Fi QR text that every phone camera understands. */
    fun qrText(w: SavedWifi): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        val t = if (w.type == "EAP") "WPA" else w.type
        return "WIFI:T:$t;S:${esc(w.ssid)};" + (if (w.password != null && t != "nopass") "P:${esc(w.password)};" else "") + ";"
    }
}

class WifiPassActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var list: LinearLayout
    private var all: List<SavedWifi> = emptyList()
    private val listener = Shizuku.OnRequestPermissionResultListener { _, r -> runOnUiThread { if (r == PackageManager.PERMISSION_GRANTED) draw() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.wpass_t))
        try { Shizuku.addRequestPermissionResultListener(listener) } catch (_: Throwable) { }
    }

    override fun onDestroy() { try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Throwable) { }; super.onDestroy() }
    override fun onResume() { super.onResume(); draw() }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        // Manual QR — always available
        col.addSpaced(manualCard())
        when (WifiPasswords.state()) {
            0 -> col.addSpaced(card().apply {
                addView(label(getString(R.string.wpass_need_shizuku), 15f, R.color.sub))
                addView(sahlButton(getString(R.string.wpass_open_shizuku)) {
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                        ?: openLink("https://shizuku.rikka.app/download/")
                })
            })
            1 -> col.addSpaced(card().apply {
                addView(label(getString(R.string.wpass_allow_shizuku), 15f, R.color.sub))
                addView(sahlButton(getString(R.string.wpass_allow)) { try { Shizuku.requestPermission(7) } catch (_: Throwable) { } })
            })
            else -> loadSaved()
        }
    }

    private fun loadSaved() {
        val search = EditText(this).apply {
            hint = getString(R.string.wpass_search); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = show(s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(label(getString(R.string.wpass_saved), 18f, R.color.emerald, true).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        col.addView(search, LinearLayout.LayoutParams(-1, -2))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        list.addView(label(getString(R.string.working), 14f, R.color.sub))
        Thread {
            val r = try { Result.success(WifiPasswords.list()) } catch (e: Throwable) { Result.failure(e) }
            runOnUiThread {
                list.removeAllViews()
                r.onSuccess { all = it; show("") }
                r.onFailure { list.addView(label(getString(R.string.wpass_failed) + "\n" + (it.cause ?: it), 13f, R.color.sub)) }
            }
        }.start()
    }

    private fun show(q: String) {
        list.removeAllViews()
        for (w in all.filter { q.isBlank() || it.ssid.contains(q, true) }) list.addSpaced(netCard(w))
        if (all.isEmpty()) list.addView(label(getString(R.string.wpass_none), 14f, R.color.sub))
    }

    private fun netCard(w: SavedWifi) = card().apply {
        addView(label("📶  " + w.ssid, 17f, bold = true))
        val pass = label(if (w.password == null) getString(if (w.type == "EAP") R.string.wpass_eap else R.string.wpass_open)
                         else "••••••••  (" + getString(R.string.wpass_tap_show) + ")", 15f, R.color.sub).apply { setTextIsSelectable(true) }
        if (w.password != null) pass.setOnClickListener { pass.text = w.password; pass.setTextColor(getColor(R.color.text)) }
        addView(pass)
        addView(actions(
            sahlButton(getString(R.string.wpass_qr)) { showQr(w) },
            *(if (w.password != null) arrayOf(
                sahlButton(getString(R.string.copy)) { copy(w.password) },
                sahlButton(getString(R.string.share)) { shareText(getString(R.string.wpass_share_text, w.ssid, w.password)) })
              else emptyArray<android.widget.Button>())))
    }

    /** Type any network to make a QR code (works without Shizuku). */
    private fun manualCard() = card().apply {
        addView(label(getString(R.string.wpass_manual), 16f, R.color.text, true))
        val ssid = EditText(this@WifiPassActivity).apply {
            hint = getString(R.string.wpass_name); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            currentSsid()?.let { setText(it) }
        }
        val pass = EditText(this@WifiPassActivity).apply {
            hint = getString(R.string.wpass_password); typeface = sahlFont(); setTextColor(getColor(R.color.text)); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        addView(ssid, LinearLayout.LayoutParams(-1, -2)); addView(pass, LinearLayout.LayoutParams(-1, -2))
        addView(sahlButton(getString(R.string.wpass_make_qr)) {
            val s = ssid.text.toString().trim()
            if (s.isEmpty()) return@sahlButton
            val p = pass.text.toString()
            showQr(SavedWifi(s, p.ifEmpty { null }, if (p.isEmpty()) "nopass" else "WPA"))
        })
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')?.takeUnless { it.startsWith("<") }
    } catch (_: Exception) { null }

    private fun showQr(w: SavedWifi) {
        val bmp: Bitmap = BarcodeEncoder().encodeBitmap(WifiPasswords.qrText(w), BarcodeFormat.QR_CODE, 720, 720)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply {
            setImageBitmap(bmp); setBackgroundColor(android.graphics.Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(dp(260), dp(260)))
        box.addView(label(getString(R.string.wpass_scan_hint), 13f, R.color.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        box.addView(label(w.ssid, 16f, bold = true).apply { gravity = Gravity.CENTER })
        w.password?.let { box.addView(label(it, 14f, R.color.sub).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }) }
        // Full brightness so the other phone's camera reads it easily
        val old = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        sahlDialog(w.ssid, box, getString(android.R.string.ok) to { d: android.app.AlertDialog -> d.dismiss() })
            .setOnDismissListener { window.attributes = window.attributes.apply { screenBrightness = old } }
    }
}

// ───────────────────────── Deep-link opener ─────────────────────────
/** Open any link or app address (tel:, whatsapp://, market://, intent:…), choosing which app opens it. */
class DeepLinkActivity : Activity() {
    private lateinit var box: EditText
    private lateinit var out: LinearLayout

    private val examples = listOf(
        "https://wa.me/97400000000" to R.string.dl_ex_wa,
        "tel:999" to R.string.dl_ex_tel,
        "market://details?id=com.sahl.tools" to R.string.dl_ex_store,
        "geo:25.2854,51.5310?q=Doha" to R.string.dl_ex_map,
        "mailto:someone@example.com?subject=Hello" to R.string.dl_ex_mail,
        "intent:#Intent;action=android.settings.APPLICATION_DEVELOPMENT_SETTINGS;end" to R.string.dl_ex_dev)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = sahlScreen(getString(R.string.deeplink_t))
        box = EditText(this).apply {
            hint = getString(R.string.dl_hint); typeface = sahlFont(); setTextColor(getColor(R.color.text))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        }
        col.addSpaced(card().apply {
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(row(sahlButton(getString(R.string.paste_link)) {
                    val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
                    box.setText(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@DeepLinkActivity)?.toString()?.trim() ?: "")
                }, sahlButton(getString(R.string.dl_find)) { find() }))
        })
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(out)
        val recent = Tools.prefs(this).getString("dlRecent", "")!!.split('\n').filter { it.isNotBlank() }
        if (recent.isNotEmpty()) {
            col.addView(label(getString(R.string.dl_recent), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(8), 0, dp(4)) })
            col.addSpaced(card().apply { for (r in recent) addView(pick(r, r)) })
        }
        col.addView(label(getString(R.string.dl_examples), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(8), 0, dp(4)) })
        col.addSpaced(card().apply { for ((u, t) in examples) addView(pick(getString(t), u)) })
    }

    private fun pick(title: String, uri: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true
        background = getDrawable(android.R.drawable.list_selector_background)
        addView(label(title, 14f, bold = true)); addView(label(uri, 12f, R.color.sub))
        setOnClickListener { box.setText(uri); find() }
    }

    private fun parse(text: String): Intent? = try {
        if (text.startsWith("intent:") || text.contains("#Intent;")) Intent.parseUri(text, Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, Uri.parse(text))
    } catch (_: Exception) { null }

    /** Lists every app that can open this address, then opens the one you tap. */
    private fun find() {
        out.removeAllViews()
        val text = box.text.toString().trim()
        val i = parse(text)
        if (text.isEmpty() || i == null) { out.addView(label(getString(R.string.dl_bad), 14f, R.color.sub)); return }
        remember(text)
        i.addCategory(Intent.CATEGORY_DEFAULT)
        val apps = packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).distinctBy { it.activityInfo.packageName + it.activityInfo.name }
        val c = card()
        c.addView(label(getString(R.string.dl_scheme, Uri.parse(text).scheme ?: "intent"), 13f, R.color.sub))
        if (apps.isEmpty()) {
            c.addView(label(getString(R.string.dl_no_app), 15f))
            c.addView(sahlButton(getString(R.string.dl_try)) { launch(i) })
        } else for (a in apps) {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); isClickable = true
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { launch(Intent(i).setClassName(a.activityInfo.packageName, a.activityInfo.name)) }
            }
            r.addView(ImageView(this).apply { setImageDrawable(a.loadIcon(packageManager)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            t.addView(label(a.loadLabel(packageManager), 15f, bold = true))
            t.addView(label(a.activityInfo.name.substringAfterLast('.'), 11f, R.color.sub))
            r.addView(t)
            c.addView(r)
        }
        out.addSpaced(c)
    }

    private fun launch(i: Intent) = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.dl_failed) + " " + e.javaClass.simpleName, Toast.LENGTH_LONG).show() }

    private fun remember(t: String) {
        val p = Tools.prefs(this)
        val list = (listOf(t) + p.getString("dlRecent", "")!!.split('\n')).filter { it.isNotBlank() }.distinct().take(8)
        p.edit().putString("dlRecent", list.joinToString("\n")).apply()
    }
}
