package com.sahl.tools

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL
import java.net.URLEncoder

/**
 * Link safety: quick warning signs checked on the phone (no internet needed),
 * plus an optional VirusTotal scan (~90 antivirus engines) with the user's free API key.
 */
object LinkSafety {
    private val riskyTlds = setOf("zip", "mov", "xyz", "top", "click", "country", "gq", "tk", "ml", "cf", "ga", "work",
        "support", "rest", "fit", "loan", "men", "kim", "cam", "quest", "sbs", "cfd", "lol", "icu", "buzz", "monster")
    private val brands = listOf("paypal", "apple", "icloud", "google", "microsoft", "office365", "outlook", "amazon", "netflix",
        "whatsapp", "facebook", "instagram", "qnb", "qib", "ooredoo", "vodafone", "metrash", "hukoomi", "dhl", "aramex", "fedex", "qatarpost")
    private val shorteners = setOf("bit.ly", "t.co", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "cutt.ly", "rb.gy", "shorturl.at", "t.ly")

    /** Warning signs as string resource ids. Empty list = nothing suspicious found. */
    fun warnings(url: String): List<Int> {
        val w = ArrayList<Int>()
        val u = Uri.parse(url)
        val host = (u.host ?: return listOf(R.string.ws_bad)).lowercase()
        val parts = host.split('.')
        val tld = parts.last()
        val main = if (parts.size >= 2) parts[parts.size - 2] else host
        if (u.scheme == "http") w.add(R.string.ws_http)
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || host.contains(':')) w.add(R.string.ws_ip)
        if (host.contains("xn--") || IDN.toUnicode(host) != host) w.add(R.string.ws_lookalike)
        if (tld in riskyTlds) w.add(R.string.ws_tld)
        if (url.substringBefore('?').contains('@')) w.add(R.string.ws_at)
        if (parts.size >= 5 || host.length > 45) w.add(R.string.ws_long)
        if (host in shorteners) w.add(R.string.ws_short)
        // A brand name in the address but not as the real site name, e.g. paypal.secure-login.xyz
        if (brands.any { b -> host.contains(b) && main != b && !main.startsWith(b) }) w.add(R.string.ws_brand)
        return w
    }

    private val fileTypes = mapOf(
        "apk" to "Android app (APK)", "apks" to "Android app (APK)", "xapk" to "Android app (APK)", "aab" to "Android app",
        "exe" to "Windows program", "msi" to "Windows installer", "bat" to "Windows script", "scr" to "Windows program",
        "zip" to "ZIP archive", "rar" to "RAR archive", "7z" to "7z archive", "dmg" to "Mac installer",
        "pdf" to "PDF document", "doc" to "Word document", "docm" to "Word document with macros", "xls" to "Excel file",
        "xlsm" to "Excel file with macros", "js" to "Script", "vbs" to "Script", "jar" to "Java program", "iso" to "Disk image")

    /** If the link downloads a file instead of opening a page, says what kind. Blocking (asks the server). */
    fun download(url: String): String? {
        val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        fileTypes[ext]?.let { return it }
        return try {
            val con = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"; connectTimeout = 6000; readTimeout = 6000; setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val type = con.contentType?.lowercase() ?: ""
            val disp = con.getHeaderField("Content-Disposition")?.lowercase() ?: ""
            con.disconnect()
            when {
                "android.package-archive" in type -> "Android app (APK)"
                "attachment" in disp -> fileTypes[disp.substringAfterLast('.').trim('"', ' ', ';')] ?: "File download"
                "octet-stream" in type || "x-msdownload" in type -> "File download"
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun key(c: Context) = Tools.prefs(c).getString("vtKey", "") ?: ""

    /** Website search — works without a key. */
    fun vtWebsite(url: String) = "https://www.virustotal.com/gui/search/" + URLEncoder.encode(url, "UTF-8")

    data class Verdict(val malicious: Int, val suspicious: Int, val total: Int)

    /** Blocking. Looks the link up on VirusTotal; if it is new, submits it and waits for the result. */
    /** Address of the full VirusTotal report page for a link. */
    fun vtReport(url: String) = "https://www.virustotal.com/gui/url/" +
        java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun virusTotal(key: String, url: String, progress: (Int) -> Unit = {}): Verdict? {
        val id = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        get(key, "urls/$id")?.let { return stats(it.getJSONObject("data").getJSONObject("attributes").getJSONObject("last_analysis_stats")) }
        val con = (URL("https://www.virustotal.com/api/v3/urls").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("x-apikey", key); setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        con.outputStream.use { it.write(("url=" + URLEncoder.encode(url, "UTF-8")).toByteArray()) }
        if (con.responseCode != 200) throw IllegalStateException("VT ${con.responseCode}")
        val analysis = JSONObject(con.inputStream.bufferedReader().readText()).getJSONObject("data").getString("id")
        repeat(8) { n ->
            progress(n + 1)
            Thread.sleep(5000)
            val a = get(key, "analyses/$analysis")?.getJSONObject("data")?.getJSONObject("attributes") ?: return@repeat
            if (a.optString("status") == "completed") return stats(a.getJSONObject("stats"))
        }
        return null
    }

    private fun get(key: String, path: String): JSONObject? {
        val con = (URL("https://www.virustotal.com/api/v3/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; setRequestProperty("x-apikey", key)
        }
        return when (con.responseCode) {
            200 -> JSONObject(con.inputStream.bufferedReader().readText())
            404 -> null
            401 -> throw SecurityException("bad key")
            else -> throw IllegalStateException("VT ${con.responseCode}")
        }
    }

    private fun stats(s: JSONObject): Verdict {
        var total = 0
        for (k in s.keys()) total += s.optInt(k)
        return Verdict(s.optInt("malicious"), s.optInt("suspicious"), total)
    }

    private val keyShape = Regex("\\b[0-9a-f]{64}\\b")

    /** A VirusTotal key found on the clipboard, if any (64 letters/numbers 0-9 a-f). */
    fun keyFromClipboard(c: Context): String? = try {
        val clip = c.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.let { keyShape.find(it.toString().lowercase())?.value }
    } catch (_: Exception) { null }

    /** Blocking: true if VirusTotal accepts the key. */
    fun testKey(key: String) = try { get(key, "domains/google.com"); true } catch (_: Exception) { false }

    /** Step-by-step setup for the free VirusTotal key, with paste-and-test. */
    fun askKey(c: Context, done: () -> Unit) {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(c.label(c.getString(R.string.vt_why), 14f, R.color.sub).apply { setPadding(0, 0, 0, c.dp(10)) })
        col.addView(c.label("1.  " + c.getString(R.string.vt_step1), 15f, bold = true))
        col.addView(c.sahlButton(c.getString(R.string.vt_open_signup)) { c.openLink("https://www.virustotal.com/gui/join-us") })
        col.addView(c.label("2.  " + c.getString(R.string.vt_step2), 15f, bold = true))
        col.addView(c.sahlButton(c.getString(R.string.vt_open_key)) { c.openLink("https://www.virustotal.com/gui/my-apikey") })
        col.addView(c.label("3.  " + c.getString(R.string.vt_step3), 15f, bold = true))
        val box = EditText(c).apply {
            hint = "API key"; setText(key(c).ifEmpty { keyFromClipboard(c) ?: "" }); typeface = c.sahlFont(); textSize = 13f
            setTextColor(c.getColor(R.color.text)); setHintTextColor(c.getColor(R.color.sub))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        val status = c.label("", 14f, R.color.emerald, true)
        col.addView(c.sahlButton(c.getString(R.string.paste_link)) {
            keyFromClipboard(c)?.let { box.setText(it) } ?: run { status.text = c.getString(R.string.vt_no_clip) }
        })
        col.addView(status)
        c.sahlDialog(c.getString(R.string.vt_key_title), col,
            c.getString(android.R.string.cancel) to { d: android.app.AlertDialog -> d.dismiss() },
            c.getString(R.string.save_key) to { d ->
                val k = box.text.toString().trim().lowercase()
                if (!keyShape.matches(k)) status.text = c.getString(R.string.vt_wrong_shape)
                else Thread {
                    val ok = testKey(k)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (ok) {
                            Tools.prefs(c).edit().putString("vtKey", k).apply()
                            android.widget.Toast.makeText(c, R.string.vt_key_ok, android.widget.Toast.LENGTH_LONG).show()
                            d.dismiss(); done()
                        } else status.text = c.getString(R.string.vt_bad_key)
                    }
                }.start().also { status.text = c.getString(R.string.vt_testing) }
            })
    }
}
