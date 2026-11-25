package com.sahl.tools

import android.app.Notification
import android.content.Context

internal object SahlNotif {
    const val EMERALD = 0xFF0B5D4B.toInt()

    fun apply(@Suppress("UNUSED_PARAMETER") ctx: Context, b: Notification.Builder): Notification.Builder =
        b.setSmallIcon(R.drawable.ic_sahl_notif)
            .setColor(EMERALD)
}
