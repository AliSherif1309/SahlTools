package com.sahl.tools

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs


private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
    w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
)

/** Volume Lock: remembers the current levels and puts them back whenever something changes them. */
class VolumeLockService : Service() {
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM
    )
    private val locked = HashMap<Int, Int>()
    private lateinit var am: AudioManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = restore()
    }

    override fun onBind(i: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        for (s in streams) locked[s] = am.getStreamVolume(s)
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
        Tools.setOn(this, Tools.VOLUME, true)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        Tools.foreground(this, Tools.VOLUME, 11, R.string.unlock)
        if (i?.action == "stop") stopSelf()
        return START_STICKY
    }

    private fun restore() {
        for ((s, v) in locked) if (am.getStreamVolume(s) != v) {
            try { am.setStreamVolume(s, v, 0) } catch (_: SecurityException) { /* Do Not Disturb blocks ring changes */ }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        Tools.setOn(this, Tools.VOLUME, false)
        super.onDestroy()
    }
}

/** Touch Lock: a see-through layer over the whole screen that swallows every touch. */
class TouchLockService : Service() {
    private var layer: View? = null
    private val h = Handler(Looper.getMainLooper())
    private var taps = 0
    private var lastTap = 0L

    override fun onBind(i: Intent?): android.os.IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        val wm = getSystemService(WindowManager::class.java)
        val badge = TextView(this).apply {
            text = getString(R.string.tap3)
            setTextColor(Color.WHITE); textSize = 14f
            typeface = resources.getFont(R.font.thmanyah)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xCC073D32.toInt()) }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x01000000)
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(80) })
        }
        val fade = Runnable { badge.animate().alpha(0.15f).setDuration(600).start() }
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                badge.alpha = 1f; h.removeCallbacks(fade); h.postDelayed(fade, 3000)
            }
            true // swallow everything
        }
        badge.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            taps = if (now - lastTap < 600) taps + 1 else 1
            lastTap = now
            if (taps >= 3) stopSelf()
        }
        val p = overlayParams(-1, -1,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(root, p)
        layer = root
        h.postDelayed(fade, 3000)
        Tools.setOn(this, Tools.TOUCH, true)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        Tools.foreground(this, Tools.TOUCH, 12, R.string.unlock)
        if (i?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        layer?.let { getSystemService(WindowManager::class.java).removeView(it) }
        Tools.setOn(this, Tools.TOUCH, false)
        super.onDestroy()
    }
}

/** Floating Timer: a draggable bubble. Stopwatch by default, countdown when started with minutes. */
class TimerService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private lateinit var label: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var running = false
    private var base = 0L          // elapsed ms banked before the current run
    private var startedAt = 0L
    private var countdownMs = 0L   // 0 = stopwatch
    private var alerted = false
    private var ring: android.media.Ringtone? = null

    private val tick = object : Runnable {
        override fun run() { draw(); h.postDelayed(this, 200) }
    }

    override fun onBind(i: Intent?): android.os.IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        val wm = getSystemService(WindowManager::class.java)
        label = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(resources.getFont(R.font.thmanyah), android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(getColor(R.color.gold))
            setPadding(dp(8), dp(8), dp(14), dp(8))
            setOnClickListener { stopSelf() }
        }
        val row = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(0xE6073D32.toInt()); setStroke(dp(2), getColor(R.color.gold))
            }
            addView(label); addView(close)
        }
        params = overlayParams(-2, -2, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(24); y = dp(160)
        }
        var downX = 0f; var downY = 0f; var px = 0; var py = 0; var moved = false; var downAt = 0L
        label.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; px = params.x; py = params.y; moved = false; downAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) { params.x = px + dx.toInt(); params.y = py + dy.toInt(); wm.updateViewLayout(row, params) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (SystemClock.uptimeMillis() - downAt > 600) reset() else startPause()
                }
            }
            true
        }
        wm.addView(row, params)
        bubble = row
        draw()
        Tools.setOn(this, Tools.TIMER, true)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        Tools.foreground(this, Tools.TIMER, 13)
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        // min > 0 = countdown, otherwise a stopwatch counting up from 00:00 — starts right away
        val min = i?.getIntExtra("min", 0) ?: 0
        countdownMs = if (min > 0) min * 60_000L else 0L
        reset(); startPause()
        return START_NOT_STICKY
    }

    private fun elapsed() = base + if (running) SystemClock.elapsedRealtime() - startedAt else 0

    private fun startPause() {
        ring?.stop()
        if (alerted) { reset(); return }
        if (running) { base = elapsed(); running = false; h.removeCallbacks(tick) }
        else { startedAt = SystemClock.elapsedRealtime(); running = true; h.post(tick) }
        draw()
    }

    private fun reset() {
        ring?.stop()
        running = false; base = 0; alerted = false; h.removeCallbacks(tick); draw()
    }

    private fun draw() {
        var ms = if (countdownMs > 0) countdownMs - elapsed() else elapsed()
        if (countdownMs > 0 && ms <= 0) { ms = 0; if (!alerted) finished() }
        val s = ms / 1000
        label.text = if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                     else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
        label.alpha = if (running || ms == 0L && countdownMs == 0L) 1f else 0.7f
    }

    private fun finished() {
        alerted = true; running = false; base = countdownMs; h.removeCallbacks(tick)
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        try {
            ring = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.also { it.play() }
            h.postDelayed({ ring?.stop() }, 15_000)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        ring?.stop()
        bubble?.let { getSystemService(WindowManager::class.java).removeView(it) }
        Tools.setOn(this, Tools.TIMER, false)
        super.onDestroy()
    }
}
