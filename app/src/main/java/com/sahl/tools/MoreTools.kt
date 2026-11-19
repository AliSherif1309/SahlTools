package com.sahl.tools

import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

// ───────────────────────── Flashlight ─────────────────────────
object Torch {
    private var callbackSet = false

    private fun cameraId(c: Context): String? {
        val cm = c.getSystemService(CameraManager::class.java)
        return cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }

    /** Keeps our on/off state right even when the flashlight is switched elsewhere. */
    fun watch(c: Context) {
        if (callbackSet) return
        callbackSet = true
        val app = c.applicationContext
        app.getSystemService(CameraManager::class.java).registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId(app) && Tools.isOn(app, Tools.FLASH) != enabled) Tools.setOn(app, Tools.FLASH, enabled)
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun toggle(c: Context) {
        val id = cameraId(c) ?: return
        val on = !Tools.isOn(c, Tools.FLASH)
        try { c.getSystemService(CameraManager::class.java).setTorchMode(id, on); Tools.setOn(c, Tools.FLASH, on) } catch (_: Exception) { }
    }
}

// ───────────────────────── Sound mode ─────────────────────────
object SoundMode {
    fun current(c: Context) = c.getSystemService(AudioManager::class.java).ringerMode

    fun name(c: Context) = c.getString(when (current(c)) {
        AudioManager.RINGER_MODE_SILENT -> R.string.mode_silent
        AudioManager.RINGER_MODE_VIBRATE -> R.string.mode_vibrate
        else -> R.string.mode_sound
    })

    /** Sound → Vibrate → Silent → Sound. Silent needs Do Not Disturb access, otherwise it skips it. */
    fun cycle(c: Context) {
        val am = c.getSystemService(AudioManager::class.java)
        val dnd = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> if (dnd) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am.ringerMode = next } catch (_: SecurityException) { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        Tools.setOn(c, Tools.SOUND, am.ringerMode != AudioManager.RINGER_MODE_NORMAL)
    }
}

// ───────────────────────── Screen dimmer ─────────────────────────
/** A see-through black layer that makes the screen darker than the lowest brightness. Touches pass through. */
class DimService : Service() {
    private var layer: View? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(i: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        params.alpha = level(this)
        try {
            getSystemService(WindowManager::class.java).addView(v, params)
            layer = v
        } catch (e: Exception) {
            Tools.prefs(this).edit().putString("crash", "Dimmer: " + android.util.Log.getStackTraceString(e).take(3000)).apply()
            stopSelf()
        }
        Tools.setOn(this, Tools.DIM, true)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        Tools.foreground(this, Tools.DIM, 14)
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        layer?.let { params.alpha = level(this); getSystemService(WindowManager::class.java).updateViewLayout(it, params) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        layer?.let { getSystemService(WindowManager::class.java).removeView(it) }
        Tools.setOn(this, Tools.DIM, false)
        super.onDestroy()
    }

    companion object {
        /** Darkness 10–80 %. Android lets touches through only up to 80 %. */
        fun level(c: Context) = Tools.prefs(c).getInt("dimLevel", 40).coerceIn(10, 80) / 100f
    }
}

// ───────────────────────── Link cleaner ─────────────────────────
object LinkCleaner {
    private val junk = Regex("^(utm_.*|fbclid|gclid|dclid|gbraid|wbraid|msclkid|mc_eid|mc_cid|igshid|igsh|si|feature|ref|ref_src|ref_url|" +
        "_hsenc|_hsmi|yclid|twclid|ttclid|s_cid|spm|scm|share_id|sharer|smid|pp|xmt|mibextid|trk|trkCampaign|campaign_id|ad_id)$", RegexOption.IGNORE_CASE)

    fun findUrl(text: String) = findAll(text).firstOrNull()

    /** Every link in a piece of text; "www.site.com" style links get https:// added. */
    fun findAll(text: String) = Regex("(https?://|www\\.)[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';') }
        .map { if (it.startsWith("www.", true)) "https://$it" else it }
        .distinct().take(20).toList()

    /** Follows short links (bit.ly, t.co …) to the real address. Blocking. */
    fun isTracking(name: String) = junk.matches(name)

    fun expand(url: String) = chain(url).last()

    /** Every address a link passes through before the real page (short links, redirects). Blocking. */
    fun chain(url: String): List<String> {
        val hops = arrayListOf(url)
        var u = url
        repeat(6) {
            val con = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; requestMethod = "HEAD"; connectTimeout = 6000; readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val code = con.responseCode
            val loc = con.getHeaderField("Location")
            con.disconnect()
            if (code !in 300..399 || loc == null) return hops
            u = URL(URL(u), loc).toString()
            hops.add(u)
        }
        return hops
    }

    fun clean(url: String): String {
        val uri = Uri.parse(url)
        val keep = uri.queryParameterNames.filterNot { junk.matches(it) }
        val b = uri.buildUpon().clearQuery()
        for (k in keep) for (v in uri.getQueryParameters(k)) b.appendQueryParameter(k, v)
        return b.build().toString().removeSuffix("?")
    }
}

// ───────────────────────── Save to phone ─────────────────────────
class SaveActivity : Activity() {
    private val files = ArrayList<Uri>()
    private var text: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) files.add(u) else text = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { files.addAll(it) }
        }
        when {
            files.size > 1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 2)
            files.size == 1 || text != null -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (text != null) "text/plain" else contentResolver.getType(files[0]) ?: "*/*")
                .putExtra(Intent.EXTRA_TITLE, if (text != null) "Sahl_" + System.currentTimeMillis() + ".txt" else nameOf(files[0])), 1)
            else -> { Toast.makeText(this, R.string.save_how, Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun nameOf(u: Uri): String = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: ("Sahl_" + System.currentTimeMillis())

    @Deprecated("Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val target = data?.data
        if (res != RESULT_OK || target == null) { finish(); return }
        var saved = 0
        try {
            if (req == 1) {
                contentResolver.openOutputStream(target)?.use { out ->
                    val t = text
                    if (t != null) out.write(t.toByteArray())
                    else contentResolver.openInputStream(files[0])?.use { it.copyTo(out) }
                }
                saved = 1
            } else {
                val tree = android.provider.DocumentsContract.buildDocumentUriUsingTree(target,
                    android.provider.DocumentsContract.getTreeDocumentId(target))
                for (f in files) {
                    val doc = android.provider.DocumentsContract.createDocument(contentResolver, tree,
                        contentResolver.getType(f) ?: "application/octet-stream", nameOf(f)) ?: continue
                    contentResolver.openOutputStream(doc)?.use { out -> contentResolver.openInputStream(f)?.use { it.copyTo(out) } }
                    saved++
                }
            }
        } catch (_: Exception) { }
        Toast.makeText(this, getString(R.string.saved_n, saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}

// ───────────────────────── Wi-Fi info ─────────────────────────
class WifiActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = sahlScreen(getString(R.string.wifi_t))
        box = card()
        col.addSpaced(box)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            col.addView(sahlButton(getString(R.string.wifi_name_perm)) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 3)
            })
    }

    override fun onResume() { super.onResume(); h.post(object : Runnable { override fun run() { draw(); h.postDelayed(this, 2000) } }) }
    override fun onPause() { h.removeCallbacksAndMessages(null); super.onPause() }

    @Suppress("DEPRECATION")
    private fun draw() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val cm = getSystemService(ConnectivityManager::class.java)
        val info = wm.connectionInfo
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val connected = info != null && info.networkId != -1
        val rows = ArrayList<Pair<Int, String>>()
        if (!connected) rows.add(R.string.wifi_status to getString(R.string.wifi_off))
        else {
            val ssid = info.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" } ?: "—"
            val bars = WifiManager.calculateSignalLevel(info.rssi, 5)
            val band = when (info.frequency) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "${info.frequency} MHz" }
            rows.add(R.string.wifi_name to ssid)
            rows.add(R.string.wifi_signal to "${info.rssi} dBm  " + "▮".repeat(bars) + "▯".repeat(4 - bars.coerceAtMost(4)))
            rows.add(R.string.wifi_speed to "${info.linkSpeed} Mbps")
            rows.add(R.string.wifi_band to "$band  (${info.frequency} MHz)")
            if (Build.VERSION.SDK_INT >= 30) rows.add(R.string.wifi_standard to when (info.wifiStandard) {
                4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 7 -> "Wi-Fi 6E/7"; 8 -> "Wi-Fi 7 (be)"; else -> "—" })
        }
        lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.let { rows.add(R.string.wifi_ip to it.address.hostAddress!!) }
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress?.let { rows.add(R.string.wifi_gateway to it) }
        lp?.dnsServers?.takeIf { it.isNotEmpty() }?.let { rows.add(R.string.wifi_dns to it.joinToString(", ") { a -> a.hostAddress ?: "" }) }
        box.removeAllViews()
        for ((k, v) in rows) {
            box.addView(label(getString(k), 13f, R.color.sub))
            box.addView(label(v, 17f, bold = true).apply { setTextIsSelectable(true); setPadding(0, 0, 0, dp(8)) })
        }
    }
}

/** Picks photos from the gallery and hands them to the photo cleaner. */
class PickPhotosActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 4)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res == RESULT_OK && data != null) {
            val list = ArrayList<Uri>()
            data.data?.let { list.add(it) }
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { if (it !in list) list.add(it) } }
            startActivity(Intent(this, PhotoActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, list).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
