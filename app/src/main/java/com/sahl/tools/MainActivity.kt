package com.sahl.tools

import android.app.Activity
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private val switches = HashMap<String, Switch>()
    private lateinit var font: Typeface
    private lateinit var updateBar: TextView
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = sync()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(getColor(color))
        typeface = if (bold) Typeface.create(font, Typeface.BOLD) else font
    }

    private fun rounded(color: Int, r: Int = 20) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(getColor(color))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        font = resources.getFont(R.font.thmanyah)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(32)) }

        // Header
        list.addView(text(getString(R.string.app_name), 30f, R.color.emerald, true))
        list.addView(text(getString(R.string.tagline), 15f, R.color.sub).apply { setPadding(0, dp(2), 0, dp(12)) })
        updateBar = text("", 14f, R.color.emerald_dark, true).apply {
            visibility = View.GONE; background = rounded(R.color.gold, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.CENTER
        }
        list.addView(updateBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        for (k in Tools.ALL) list.addView(card(k), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        list.addView(text(getString(R.string.battery_hint), 13f, R.color.sub).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        setContentView(ScrollView(this).apply { addView(list) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        checkUpdate()
    }

    private fun card(k: String): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(R.color.card)
            setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dp(2).toFloat()
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(Tools.icon(k)); background = rounded(R.color.emerald, 14)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        top.addView(text(getString(Tools.title(k)), 19f, R.color.text, true).apply { setPadding(dp(12), 0, dp(12), 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            setOnClickListener {
                if (!Tools.toggle(this@MainActivity, k)) {
                    Toast.makeText(this@MainActivity,
                        if (k == Tools.SCREEN) R.string.need_settings else R.string.need_overlay, Toast.LENGTH_LONG).show()
                    Tools.missingPermission(this@MainActivity, k)?.let { startActivity(it) }
                }
                sync()
            }
        }
        switches[k] = sw
        top.addView(sw)
        c.addView(top)
        c.addView(text(getString(Tools.desc(k)), 14f, R.color.sub).apply { setPadding(0, dp(8), 0, dp(4)) })

        if (k == Tools.TIMER) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val min = EditText(this).apply {
                hint = getString(R.string.minutes); inputType = InputType.TYPE_CLASS_NUMBER; typeface = font
                setText("5"); setTextColor(getColor(R.color.text))
            }
            row.addView(min, LinearLayout.LayoutParams(dp(90), -2))
            row.addView(button(getString(R.string.countdown)) {
                val m = min.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 5
                if (!Tools.toggle(this, k, m)) Tools.missingPermission(this, k)?.let { startActivity(it) }
                sync()
            })
            c.addView(row)
            c.addView(button("⏱  " + getString(R.string.stopwatch)) {
                if (!Tools.toggle(this, k, -1)) Tools.missingPermission(this, k)?.let { startActivity(it) }
                sync()
            })
        }
        if (Build.VERSION.SDK_INT >= 33) c.addView(button(getString(R.string.add_tile)) { addTile(k) })
        return c
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; typeface = Typeface.create(font, Typeface.BOLD)
        setTextColor(getColor(R.color.emerald)); background = null
        setOnClickListener { onTap() }
    }

    /** Asks Android to put this tool's tile in the Quick Settings panel (Android 13+). */
    private fun addTile(k: String) {
        if (Build.VERSION.SDK_INT < 33) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, Tools.tileClass(k)), getString(Tools.title(k)),
            Icon.createWithResource(this, Tools.icon(k)), mainExecutor
        ) { code ->
            if (code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sync() { for ((k, s) in switches) s.isChecked = Tools.isOn(this, k) }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(Tools.CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, f)
        sync()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    /** Looks for a newer GitHub release and shows a gold bar to download it. */
    private fun checkUpdate() = Thread {
        try {
            val con = (URL("https://api.github.com/repos/AliSherif1309/SahlTools/releases/latest").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 8000; readTimeout = 8000 }
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val tag = j.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = j.getJSONArray("assets")
            var url = ""
            for (i in 0 until assets.length()) assets.getJSONObject(i).let { if (it.getString("name").endsWith(".apk")) url = it.getString("browser_download_url") }
            if (code > BuildConfig.VERSION_CODE && url.isNotEmpty()) runOnUiThread {
                updateBar.text = getString(R.string.update_available, tag)
                updateBar.visibility = View.VISIBLE
                updateBar.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        } catch (_: Exception) { }
    }.start()
}
