package com.sahl.tools

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Rules, block/allow lists and the log for Call Guard. */
object CallGuard {
    const val ALLOW = 0; const val SILENCE = 1; const val BLOCK = 2

    fun prefs(c: Context) = Tools.prefs(c)
    fun opt(c: Context, k: String, def: Boolean) = prefs(c).getBoolean("cg_$k", def)
    fun setOpt(c: Context, k: String, v: Boolean) = prefs(c).edit().putBoolean("cg_$k", v).apply()

    fun list(c: Context, name: String): MutableList<String> =
        prefs(c).getString("cg_$name", "")!!.split('\n').filter { it.isNotBlank() }.toMutableList()
    fun saveList(c: Context, name: String, l: List<String>) = prefs(c).edit().putString("cg_$name", l.distinct().joinToString("\n")).apply()

    /** Keeps only digits and a leading +. */
    fun norm(n: String) = n.trim().let { (if (it.startsWith("+")) "+" else "") + it.filter { ch -> ch.isDigit() } }

    /** A pattern like "+234", "0800*" or "+97455*12" matches the start of a number (* = any digits). */
    fun matches(number: String, pattern: String): Boolean {
        val n = norm(number)
        val p = pattern.trim().filter { it.isDigit() || it == '+' || it == '*' }
        if (p.isEmpty()) return false
        fun rx(x: String) = x.split('*').joinToString(".*") { Regex.escape(it) }
        if (Regex("^" + rx(p) + ".*").matches(n)) return true
        // A pattern without + also matches after a country code (e.g. "55123456" matches "+97455123456")
        return !p.startsWith("+") && Regex("^\\+?\\d{0,3}" + rx(p) + ".*").matches(n)
    }

    fun inContacts(c: Context, number: String): Boolean {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            c.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    /** Decides what to do with a call, and why. */
    fun decide(c: Context, number: String?, presentation: Int, verification: Int): Pair<Int, Int> {
        val strong = if (opt(c, "reject", true)) BLOCK else SILENCE
        if (number.isNullOrBlank() || presentation != android.telecom.TelecomManager.PRESENTATION_ALLOWED)
            return if (opt(c, "hidden", true)) strong to R.string.cg_r_hidden else ALLOW to R.string.cg_r_ok
        if (list(c, "allow").any { matches(number, it) }) return ALLOW to R.string.cg_r_allowlist
        if (inContacts(c, number)) return ALLOW to R.string.cg_r_contact
        if (list(c, "block").any { matches(number, it) }) return BLOCK to R.string.cg_r_blocklist
        // Urgent: the same unknown number calling twice within 5 minutes gets through
        if (opt(c, "repeat", true)) {
            val recent = log(c).firstOrNull { norm(it.optString("n")) == norm(number) && System.currentTimeMillis() - it.optLong("t") < 5 * 60_000 }
            if (recent != null) return ALLOW to R.string.cg_r_repeat
        }
        if (verification == Connection.VERIFICATION_STATUS_FAILED && opt(c, "spoofed", true)) return strong to R.string.cg_r_spoofed
        val home = prefs(c).getString("cg_home", "+974")!!
        if (opt(c, "foreign", false) && norm(number).startsWith("+") && !norm(number).startsWith(norm(home))) return strong to R.string.cg_r_foreign
        if (opt(c, "unknown", false)) return SILENCE to R.string.cg_r_unknown
        return ALLOW to R.string.cg_r_ok
    }

    fun log(c: Context): List<JSONObject> {
        val a = try { JSONArray(prefs(c).getString("cg_log", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun addLog(c: Context, number: String?, action: Int, reason: Int) {
        val l = listOf(JSONObject().put("n", number ?: "").put("a", action).put("r", c.resources.getResourceEntryName(reason))
            .put("t", System.currentTimeMillis())) + log(c).take(299)
        prefs(c).edit().putString("cg_log", JSONArray(l).toString()).apply()
        if (action != ALLOW) prefs(c).edit().putInt("cg_count_$action", prefs(c).getInt("cg_count_$action", 0) + 1).apply()
    }

    fun active(c: Context) = Build.VERSION.SDK_INT >= 29 &&
        c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

/** Android hands every incoming call from an unknown number to this service before it rings. */
class SahlCallScreening : CallScreeningService() {
    override fun onScreenCall(d: Call.Details) {
        if (Build.VERSION.SDK_INT >= 29 && d.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(d, CallResponse.Builder().build()); return
        }
        val number = d.handle?.schemeSpecificPart
        val verification = if (Build.VERSION.SDK_INT >= 30) d.callerNumberVerificationStatus else Connection.VERIFICATION_STATUS_NOT_VERIFIED
        val (action, reason) = try { CallGuard.decide(this, number, d.handlePresentation, verification) } catch (_: Exception) { CallGuard.ALLOW to R.string.cg_r_ok }
        val r = CallResponse.Builder()
        when (action) {
            CallGuard.BLOCK -> r.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false)
            CallGuard.SILENCE -> if (Build.VERSION.SDK_INT >= 29) r.setSilenceCall(true)
        }
        respondToCall(d, r.build())
        CallGuard.addLog(this, number, action, reason)
        if (action == CallGuard.BLOCK && CallGuard.opt(this, "notify", true)) notifyBlocked(number, reason)
        else if (action == CallGuard.SILENCE && CallGuard.opt(this, "notify_silent", true)) notifySilenced(number, reason)
    }

    private fun notifyBlocked(number: String?, reason: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("calls", getString(R.string.cg_t), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 21, Intent(this, CallGuardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2100 + (System.currentTimeMillis() % 50).toInt(), SahlNotif.apply(this, Notification.Builder(this, "calls"))
            .setContentTitle(getString(R.string.cg_n_blocked, number ?: getString(R.string.cg_hidden)))
            .setContentText(getString(reason)).setContentIntent(open).setAutoCancel(true).build())
    }

    private fun notifySilenced(number: String?, reason: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(Sahl.silentCallChannel(this, "calls_silenced", getString(R.string.cg_ch_silenced)))
        val launch = packageManager.getLaunchIntentForPackage("com.sahl.phone") ?: Intent(this, CallGuardActivity::class.java)
        val open = PendingIntent.getActivity(this, 22, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2200 + (System.currentTimeMillis() % 50).toInt(), SahlNotif.apply(this, Notification.Builder(this, "calls_silenced"))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle(getString(R.string.cg_n_silenced_incoming, number ?: getString(R.string.cg_hidden)))
            .setContentText(getString(reason))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

class CallGuardActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.cg_t))
    }

    override fun onResume() { super.onResume(); draw() }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        val on = CallGuard.active(this)
        // Status
        col.addSpaced(card().apply {
            background = rounded(if (on) R.color.emerald else R.color.card, 20)
            addView(label(if (on) "🛡  " + getString(R.string.cg_on) else "⚠  " + getString(R.string.cg_off), 20f, if (on) R.color.card else R.color.text, true))
            val p = CallGuard.prefs(this@CallGuardActivity)
            addView(label(getString(R.string.cg_stats, p.getInt("cg_count_${CallGuard.BLOCK}", 0), p.getInt("cg_count_${CallGuard.SILENCE}", 0)),
                14f, if (on) R.color.card else R.color.sub))
            if (!on) addView(sahlButton(getString(R.string.cg_turn_on)) { askRole() })
        })
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            col.addSpaced(card().apply {
                addView(label(getString(R.string.cg_contacts_why), 14f, R.color.sub))
                addView(sahlButton(getString(R.string.cg_contacts_allow)) { requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4) })
            })

        // Rules
        col.addView(label(getString(R.string.cg_rules), 18f, R.color.emerald, true).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        col.addSpaced(card().apply {
            for ((k, def, t) in listOf(Triple("spoofed", true, R.string.cg_o_spoofed), Triple("hidden", true, R.string.cg_o_hidden),
                Triple("foreign", false, R.string.cg_o_foreign), Triple("unknown", false, R.string.cg_o_unknown),
                Triple("repeat", true, R.string.cg_o_repeat), Triple("reject", true, R.string.cg_o_reject),
                Triple("notify", true, R.string.cg_o_notify), Triple("notify_silent", true, R.string.cg_o_notify_silent)))
                addView(Switch(this@CallGuardActivity).apply {
                    text = getString(t); typeface = sahlFont(); textSize = 15f; setTextColor(getColor(R.color.text))
                    setPadding(0, dp(8), 0, dp(8)); isChecked = CallGuard.opt(this@CallGuardActivity, k, def)
                    setOnCheckedChangeListener { _, v -> CallGuard.setOpt(this@CallGuardActivity, k, v) }
                })
        })

        listCard("block", R.string.cg_blocklist, R.string.cg_block_hint)
        listCard("allow", R.string.cg_allowlist, R.string.cg_allow_hint)

        // Log
        val log = CallGuard.log(this)
        col.addView(label(getString(R.string.cg_log, log.size), 18f, R.color.emerald, true).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        if (log.isEmpty()) col.addView(label(getString(R.string.cg_log_empty), 14f, R.color.sub))
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val lc = card()
        for (e in log.take(60)) {
            val n = e.optString("n")
            val a = e.optInt("a")
            val reason = resources.getIdentifier(e.optString("r"), "string", packageName).takeIf { it != 0 }?.let { getString(it) } ?: ""
            val icon = when (a) { CallGuard.BLOCK -> "⛔"; CallGuard.SILENCE -> "🔕"; else -> "✓" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); isClickable = true
                background = getDrawable(android.R.drawable.list_selector_background)
                addView(label("$icon  " + n.ifEmpty { getString(R.string.cg_hidden) }, 15f, bold = true))
                addView(label(fmt.format(Date(e.optLong("t"))) + " · " + reason, 12f, R.color.sub))
                if (n.isNotEmpty()) setOnClickListener { numberMenu(n) }
            }
            lc.addView(row)
        }
        if (log.isNotEmpty()) {
            lc.addView(sahlButton(getString(R.string.cg_clear_log)) { CallGuard.prefs(this).edit().remove("cg_log").apply(); draw() })
            col.addSpaced(lc)
        }
    }

    private fun listCard(name: String, title: Int, hint: Int) {
        col.addView(label(getString(title), 18f, R.color.emerald, true).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        col.addSpaced(card().apply {
            val input = EditText(this@CallGuardActivity).apply {
                this.hint = getString(hint); typeface = sahlFont(); setSingleLine(); inputType = InputType.TYPE_CLASS_PHONE
            }
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(sahlButton(getString(R.string.cg_add)) {
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) { CallGuard.saveList(this@CallGuardActivity, name, CallGuard.list(this@CallGuardActivity, name) + v); draw() }
            })
            for (p in CallGuard.list(this@CallGuardActivity, name)) {
                val r = LinearLayout(this@CallGuardActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                r.addView(label(p, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(sahlButton("✕") {
                    CallGuard.saveList(this@CallGuardActivity, name, CallGuard.list(this@CallGuardActivity, name) - p); draw()
                })
                addView(r)
            }
        })
    }

    private fun numberMenu(n: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(n, 18f, bold = true))
        box.addView(sahlButton("⛔  " + getString(R.string.cg_add_block)) { CallGuard.saveList(this, "block", CallGuard.list(this, "block") + n); draw() })
        box.addView(sahlButton("✓  " + getString(R.string.cg_add_allow)) { CallGuard.saveList(this, "allow", CallGuard.list(this, "allow") + n); draw() })
        box.addView(sahlButton(getString(R.string.cg_call_back)) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) })
        box.addView(sahlButton(getString(R.string.cg_lookup)) { openLink("https://www.google.com/search?q=" + Uri.encode(n)) })
        sahlDialog(getString(R.string.cg_t), box, getString(android.R.string.ok) to { d: android.app.AlertDialog -> d.dismiss() })
    }

    private fun askRole() {
        if (Build.VERSION.SDK_INT < 29) return
        val rm = getSystemService(RoleManager::class.java)
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 6)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) { draw() }
}
