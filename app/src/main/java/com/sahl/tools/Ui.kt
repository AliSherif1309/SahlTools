package com.sahl.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Small helpers so every Sahl Tools screen looks the same (Thmanyah, emerald & gold cards). */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.sahlFont(): Typeface = resources.getFont(R.font.thmanyah)

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = R.color.text, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(getColor(color))
    typeface = if (bold) Typeface.create(sahlFont(), Typeface.BOLD) else sahlFont()
}

fun Context.rounded(color: Int, r: Int = 20) = GradientDrawable().apply {
    cornerRadius = dp(r).toFloat(); setColor(getColor(color))
}

fun Context.sahlButton(text: String, onTap: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; typeface = Typeface.create(sahlFont(), Typeface.BOLD)
    setTextColor(getColor(R.color.emerald)); background = null
    setOnClickListener { onTap() }
}

fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rounded(R.color.card)
    setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dp(2).toFloat()
}

/** A full screen with a title, ready for content; returns the column to add views to. */
fun Activity.sahlScreen(title: String): LinearLayout {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(32)) }
    col.addView(label(title, 26f, R.color.emerald, true).apply { setPadding(0, 0, 0, dp(12)) })
    setContentView(ScrollView(this).apply { addView(col) })
    return col
}

fun LinearLayout.addSpaced(v: View) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })

fun Context.row(vararg views: View) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    for (v in views) addView(v)
}

fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sahl", text))
    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String) = startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

fun Context.openLink(text: String) = try {
    if (text.startsWith("http", true)) LinkActivity.openInBrowser(this, text)
    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
} catch (_: Exception) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

/** A dialog in the Sahl colours (works in dark mode): title, content, and our own buttons. */
fun Context.sahlDialog(title: String, content: android.view.View, vararg buttons: Pair<String, (android.app.AlertDialog) -> Unit>): android.app.AlertDialog {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(8)) }
    col.addView(label(title, 20f, R.color.text, true).apply { setPadding(0, 0, 0, dp(8)) })
    col.addView(content)
    val bar = LinearLayout(this).apply { gravity = Gravity.END }
    col.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val d = android.app.AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(col) }).create()
    for ((t, act) in buttons) bar.addView(sahlButton(t) { act(d) })
    d.window?.setBackgroundDrawable(rounded(R.color.card, 24))
    d.show()
    return d
}

/** Buttons that wrap onto new lines instead of squashing (simple vertical list when there are many). */
fun Context.actions(vararg buttons: android.view.View): LinearLayout =
    if (buttons.size <= 2) row(*buttons)
    else LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START; for (b in buttons) addView(b) }

/**
 * Adds a Quick Settings tile. Uses Android's "Add tile" pop-up when the phone supports it;
 * on phones that block or ignore it (often Honor/MagicOS), shows clear steps to add it by hand.
 */
fun Activity.addQuickTile(cls: Class<*>, title: Int, icon: Int) {
    var answered = false
    val guide = Runnable {
        if (answered || isFinishing) return@Runnable
        answered = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(getString(R.string.tile_guide_intro, getString(title)), 14f, R.color.sub).apply { setPadding(0, 0, 0, dp(8)) })
        for ((i, step) in listOf(R.string.tile_step1, R.string.tile_step2, R.string.tile_step3, R.string.tile_step4).withIndex())
            box.addView(label("${i + 1}.  " + getString(step, getString(title)), 15f).apply { setPadding(0, dp(4), 0, dp(4)) })
        sahlDialog(getString(R.string.add_tile), box, getString(android.R.string.ok) to { d: android.app.AlertDialog -> d.dismiss() })
    }
    if (android.os.Build.VERSION.SDK_INT < 33) { guide.run(); return }
    try {
        getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            android.content.ComponentName(this, cls), getString(title),
            android.graphics.drawable.Icon.createWithResource(this, icon), mainExecutor) { code ->
            when (code) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                    answered = true; Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
                }
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                    answered = true; Toast.makeText(this, R.string.tile_already, Toast.LENGTH_LONG).show()
                }
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                    answered = true; Toast.makeText(this, R.string.tile_declined, Toast.LENGTH_SHORT).show()
                }
                else -> guide.run()    // blocked or not supported by this phone
            }
        }
        // If the phone never shows the pop-up, explain how to add it by hand
        // (if Android's pop-up is on screen, our window has lost focus — then we wait for the answer instead)
        window.decorView.postDelayed({ if (hasWindowFocus()) guide.run() }, 2500)
    } catch (_: Throwable) { guide.run() }
}
