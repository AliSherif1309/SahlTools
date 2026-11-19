package com.sahl.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/** The list of tools, their on/off state, and the actions shared by the app screen and the tiles. */
object Tools {
    const val SCREEN = "screen"
    const val VOLUME = "volume"
    const val TOUCH = "touch"
    const val TIMER = "timer"
    const val FLASH = "flash"
    const val DIM = "dim"
    const val SOUND = "sound"
    const val CHANGED = "com.sahl.tools.CHANGED"
    val ALL = listOf(SCREEN, VOLUME, TOUCH, TIMER, FLASH, DIM, SOUND)

    fun prefs(c: Context) = c.getSharedPreferences("sahl", Context.MODE_PRIVATE)
    fun isOn(c: Context, k: String) = prefs(c).getBoolean(k, false)

    fun setOn(c: Context, k: String, on: Boolean) {
        prefs(c).edit().putBoolean(k, on).apply()
        TileService.requestListeningState(c, ComponentName(c, tileClass(k)))
        c.sendBroadcast(Intent(CHANGED).setPackage(c.packageName))
    }

    fun tileClass(k: String): Class<*> = when (k) {
        SCREEN -> ScreenTile::class.java
        VOLUME -> VolumeTile::class.java
        TOUCH -> TouchTile::class.java
        TIMER -> TimerTile::class.java
        FLASH -> FlashTile::class.java
        DIM -> DimTile::class.java
        else -> SoundTile::class.java
    }

    fun title(k: String) = when (k) {
        SCREEN -> R.string.screen_t; VOLUME -> R.string.volume_t
        TOUCH -> R.string.touch_t; TIMER -> R.string.timer_t
        FLASH -> R.string.flash_t; DIM -> R.string.dim_t; else -> R.string.sound_t
    }

    fun desc(k: String) = when (k) {
        SCREEN -> R.string.screen_d; VOLUME -> R.string.volume_d
        TOUCH -> R.string.touch_d; TIMER -> R.string.timer_d
        FLASH -> R.string.flash_d; DIM -> R.string.dim_d; else -> R.string.sound_d
    }

    fun icon(k: String) = when (k) {
        SCREEN -> R.drawable.ic_screen; VOLUME -> R.drawable.ic_volume
        TOUCH -> R.drawable.ic_touch; TIMER -> R.drawable.ic_timer
        FLASH -> R.drawable.ic_flash; DIM -> R.drawable.ic_dim; else -> R.drawable.ic_sound
    }

    /** Which system permission a tool needs, or null if it is already granted. */
    fun missingPermission(c: Context, k: String): Intent? {
        val pkg = android.net.Uri.parse("package:" + c.packageName)
        return when (k) {
            SCREEN -> if (Settings.System.canWrite(c)) null else Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            TOUCH, TIMER, DIM -> if (Settings.canDrawOverlays(c)) null else Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            else -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Turns a tool on or off. Returns false if a permission must be granted first. */
    fun toggle(c: Context, k: String, countdownMin: Int = 0): Boolean {
        if (missingPermission(c, k) != null) return false
        val on = isOn(c, k)
        when (k) {
            SCREEN -> if (on) KeepScreen.disable(c) else KeepScreen.enable(c)
            VOLUME -> service(c, VolumeLockService::class.java, !on)
            TOUCH -> service(c, TouchLockService::class.java, !on)
            FLASH -> Torch.toggle(c)
            DIM -> service(c, DimService::class.java, !on)
            SOUND -> SoundMode.cycle(c)
            TIMER -> {
                if (on && countdownMin == 0) service(c, TimerService::class.java, false)
                else c.startForegroundService(Intent(c, TimerService::class.java).putExtra("min", countdownMin))
            }
        }
        return true
    }

    private fun service(c: Context, cls: Class<*>, start: Boolean) {
        val i = Intent(c, cls)
        if (start) c.startForegroundService(i) else c.stopService(i)
    }

    /** Puts a service in the foreground with a small "is on — Stop" notification. */
    fun foreground(s: Service, k: String, id: Int, actionLabel: Int = R.string.stop) {
        val nm = s.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tools", s.getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(s, id, Intent(s, s.javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(s, 0, Intent(s, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(s, "tools").setSmallIcon(icon(k))
            .setContentTitle(s.getString(title(k)) + " " + s.getString(R.string.running))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, s.getString(actionLabel), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else s.startForeground(id, n)
    }
}

/** Keep Screen On: raises the system screen timeout to the maximum and restores it afterwards. */
object KeepScreen {
    fun enable(c: Context) {
        val cr = c.contentResolver
        if (!Tools.isOn(c, Tools.SCREEN)) {
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
            Tools.prefs(c).edit().putInt("oldTimeout", cur).apply()
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
        Tools.setOn(c, Tools.SCREEN, true)
    }

    fun disable(c: Context) {
        if (Settings.System.canWrite(c)) {
            val old = Tools.prefs(c).getInt("oldTimeout", 60_000)
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, old)
        }
        Tools.setOn(c, Tools.SCREEN, false)
    }
}
