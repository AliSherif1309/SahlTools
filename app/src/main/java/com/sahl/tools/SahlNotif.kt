package com.sahl.tools

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF

internal object SahlNotif {
    const val EMERALD = 0xFF0B5D4B.toInt()
    @Volatile private var cached: Bitmap? = null

    fun apply(ctx: Context, b: Notification.Builder): Notification.Builder =
        b.setSmallIcon(R.drawable.ic_sahl_notif)
            .setColor(EMERALD)
            .setLargeIcon(largeIcon(ctx))

    fun largeIcon(ctx: Context): Bitmap {
        cached?.let { return it }
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val clip = Path().apply {
            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28f, 28f, Path.Direction.CW)
        }
        c.clipPath(clip)
        c.save()
        c.scale(1.32f, 1.32f, size / 2f, size / 2f)
        ctx.getDrawable(R.drawable.ic_sahl_bg)?.apply {
            setBounds(0, 0, size, size)
            draw(c)
        }
        ctx.getDrawable(R.drawable.ic_sahl_fg)?.apply {
            setBounds(0, 0, size, size)
            draw(c)
        }
        c.restore()
        cached = bmp
        return bmp
    }
}
