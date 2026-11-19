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

    fun key(c: Context) = Tools.prefs(c).getString("vtKey", "") ?: ""

    /** Website search — works without a key. */
    fun vtWebsite(url: String) = "https://www.virustotal.com/gui/search/" + URLEncoder.encode(url, "UTF-8")

    data class Verdict(val malicious: Int, val suspicious: Int, val total: Int)

    /** Blocking. Looks the link up on VirusTotal; if it is new, submits it and waits for the result. */
    fun virusTotal(key: String, url: String): Verdict? {
        val id = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        get(key, "urls/$id")?.let { return stats(it.getJSONObject("data").getJSONObject("attributes").getJSONObject("last_analysis_stats")) }
        val con = (URL("https://www.virustotal.com/api/v3/urls").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("x-apikey", key); setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        con.outputStream.use { it.write(("url=" + URLEncoder.encode(url, "UTF-8")).toByteArray()) }
        if (con.responseCode != 200) throw IllegalStateException("VT ${con.responseCode}")
        val analysis = JSONObject(con.inputStream.bufferedReader().readText()).getJSONObject("data").getString("id")
        repeat(8) {
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

    /** Small box to paste the free VirusTotal API key once. */
    fun askKey(c: Context, done: () -> Unit) {
        val box = EditText(c).apply {
            hint = "API key"; setText(key(c)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val wrap = LinearLayout(c).apply { setPadding(c.dp(20), c.dp(8), c.dp(20), 0); addView(box, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(c).setTitle(R.string.vt_key_title).setMessage(R.string.vt_key_how).setView(wrap)
            .setPositiveButton(R.string.save_key) { _, _ ->
                Tools.prefs(c).edit().putString("vtKey", box.text.toString().trim()).apply(); done()
            }
            .setNeutralButton(R.string.get_key) { _, _ -> c.openLink("https://www.virustotal.com/gui/join-us") }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
