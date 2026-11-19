package com.sahl.tools

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Tells apps like WhatsApp that Sahl Tools supports "in-app browser" (Custom Tabs) links.
 * Without it, those apps skip the default link opener and open Chrome directly.
 * The link itself then arrives at the Link Checker popup like any other link.
 */
class CustomTabsService : Service() {
    override fun onBind(i: Intent?): IBinder = Binder()
}
