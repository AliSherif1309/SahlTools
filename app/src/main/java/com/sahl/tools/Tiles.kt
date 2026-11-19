package com.sahl.tools

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One Quick Settings tile per tool. All share the same logic. */
abstract class ToolTile(private val key: String) : TileService() {
    override fun onStartListening() {
        if (key == Tools.FLASH) Torch.watch(this)
        refresh()
    }

    override fun onClick() {
        val perm = Tools.missingPermission(this, key)
        when {
            perm != null -> openAndCollapse(perm)
            // Touch Lock must close the panel first, otherwise the lock sits under the shade
            key == Tools.TOUCH && !Tools.isOn(this, key) ->
                openAndCollapse(Intent(this, ActionActivity::class.java).putExtra("key", key))
            else -> Tools.toggle(this, key)
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Tools.isOn(this, key)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (key == Tools.SOUND) {
            t.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = SoundMode.name(this)
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAndCollapse(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(i)
    }
}

class ScreenTile : ToolTile(Tools.SCREEN)
class VolumeTile : ToolTile(Tools.VOLUME)
class TouchTile : ToolTile(Tools.TOUCH)
class TimerTile : ToolTile(Tools.TIMER)
class FlashTile : ToolTile(Tools.FLASH)
class DimTile : ToolTile(Tools.DIM)
class SoundTile : ToolTile(Tools.SOUND)

/** Opens the QR & barcode scanner straight from Quick Settings. */
class ScanTile : TileService() {
    override fun onStartListening() { qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() } }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val i = Intent(this, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}

/** Invisible helper: lets a tile close the Quick Settings panel, then turns the tool on. */
class ActionActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        intent.getStringExtra("key")?.let { Tools.toggle(applicationContext, it) }
        finish()
    }
}
