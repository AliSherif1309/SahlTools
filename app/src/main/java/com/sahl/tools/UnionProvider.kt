package com.sahl.tools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.telecom.Connection
import android.telecom.TelecomManager

/**
 * Sahl Union door of Sahl Tools — Call Guard for the other Sahl apps (Sahl key only):
 *   query  content://com.sahl.tools.union/guard/check/<number> -> action (0 allow, 1 silence, 2 block), reason, label
 *   query  content://com.sahl.tools.union/guard/log?limit=100   -> number, action, label, time   (calls Call Guard stopped)
 *   query  content://com.sahl.tools.union/guard/status          -> active (1/0), blocked count, silenced count
 *   insert content://com.sahl.tools.union/guard/block  {number} -> adds to the Call Guard block list
 *   insert content://com.sahl.tools.union/guard/allow  {number} -> adds to the allow list
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    private fun label(reasonKey: String): String {
        val c = context ?: return reasonKey
        val id = c.resources.getIdentifier(reasonKey, "string", c.packageName)
        return if (id != 0) c.getString(id) else reasonKey
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val seg = uri.pathSegments
        if (seg.firstOrNull() != "guard") return null
        return try {
            when (seg.getOrNull(1)) {
                "check" -> {
                    val out = MatrixCursor(arrayOf("action", "reason", "label"))
                    val number = seg.getOrNull(2) ?: return out
                    val (action, reason) = CallGuard.decide(c, number, TelecomManager.PRESENTATION_ALLOWED, Connection.VERIFICATION_STATUS_NOT_VERIFIED)
                    out.addRow(arrayOf<Any>(action, c.resources.getResourceEntryName(reason), c.getString(reason)))
                    out
                }
                "log" -> {
                    val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 300) ?: 100
                    val out = MatrixCursor(arrayOf("number", "action", "label", "time"))
                    for (o in CallGuard.log(c)) {
                        if (out.count >= limit) break
                        val a = o.optInt("a")
                        if (a == CallGuard.ALLOW) continue
                        out.addRow(arrayOf<Any>(o.optString("n"), a, label(o.optString("r")), o.optLong("t")))
                    }
                    out
                }
                "status" -> {
                    val p = CallGuard.prefs(c)
                    MatrixCursor(arrayOf("active", "blocked", "silenced")).apply {
                        addRow(arrayOf<Any>(if (CallGuard.active(c)) 1 else 0,
                            p.getInt("cg_count_${CallGuard.BLOCK}", 0), p.getInt("cg_count_${CallGuard.SILENCE}", 0)))
                    }
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val c = context ?: return null
        val seg = uri.pathSegments
        val number = values?.getAsString("number")?.trim()
        if (seg.firstOrNull() != "guard" || number.isNullOrEmpty()) return null
        val listName = when (seg.getOrNull(1)) { "block" -> "block"; "allow" -> "allow"; else -> return null }
        val l = CallGuard.list(c, listName)
        val n = CallGuard.norm(number)
        if (l.none { CallGuard.norm(it) == n }) { l.add(n); CallGuard.saveList(c, listName, l) }
        return uri
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
