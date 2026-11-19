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
