package com.sahl.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sahl updates (same way as Sahl Adhan):
 *  - checks GitHub Releases when the app opens and once a day in the background,
 *  - shows "New version available" with the first line of the release notes,
 *  - "Update now" opens the APK link in the browser; tap the download to install.
 * No install permission is needed, which keeps Play Protect calmer.
 */
object Updater {
    private const val REPO = "AliSherif1309/SahlTools"
    data class Release(val code: Int, val name: String, val apkUrl: String, val notes: String)

    private fun ar(c: Context) = c.resources.configuration.locales[0].language == "ar"
    fun tr(c: Context, en: String, arText: String) = if (ar(c)) arText else en

    /** Blocking — call off the main thread. Returns a release only if it is newer. */
    fun fetchNewer(): Release? = try {
        val con = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name")                       // e.g. v1.7
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (code > BuildConfig.VERSION_CODE && url.isNotEmpty())
            Release(code, tag, url, j.optString("body").lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "") else null
    } catch (e: Exception) { null }

    /** Opens the APK link in the browser, which downloads it; tap the download to install. */
    fun downloadAndInstall(ctx: Context, r: Release) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun availableText(c: Context, r: Release) = tr(c, "New version available: ${r.name}", "إصدار جديد متاح: ${r.name}")
    fun upToDateText(c: Context) = tr(c, "You have the latest version (v${BuildConfig.VERSION_NAME})", "لديك آخر إصدار (v${BuildConfig.VERSION_NAME})")
    fun checkingText(c: Context) = tr(c, "Checking…", "جارٍ التحقق…")
    fun checkText(c: Context) = tr(c, "Check for updates", "التحقق من التحديثات")
    fun updateNowText(c: Context) = tr(c, "Update now", "حدّث الآن")
    fun downloadingText(c: Context) = tr(c, "Downloading in your browser — tap the download when it finishes to install",
        "جارٍ التنزيل في المتصفح — اضغط على الملف بعد انتهاء التنزيل لتثبيته")

    /** Sets a battery-friendly daily alarm (inexact) that checks for updates in the background. */
    fun scheduleDaily(c: Context) {
        val i = Intent(c, UpdateCheckReceiver::class.java)
        if (PendingIntent.getBroadcast(c, 4501, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null) return
        val pi = PendingIntent.getBroadcast(c, 4501, i, PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC,
            System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR * 3, AlarmManager.INTERVAL_DAY, pi)
    }

    /** Once a day from the background: post a notification if an update exists (once per version). */
    fun dailyCheck(ctx: Context) {
        val p = ctx.getSharedPreferences("sahl_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0) < 20 * 3600_000L) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        val r = fetchNewer() ?: return
        if (p.getInt("notifiedCode", 0) == r.code) return
        p.edit().putInt("notifiedCode", r.code).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", checkText(ctx), NotificationManager.IMPORTANCE_DEFAULT))
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val open = PendingIntent.getActivity(ctx, 5, launch, PendingIntent.FLAG_IMMUTABLE)
        try {
            nm.notify(500, Notification.Builder(ctx, "updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(availableText(ctx, r))
                .setContentText(r.notes.ifBlank { updateNowText(ctx) })
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: SecurityException) { }
    }
}

/** Fired by the daily alarm; checks GitHub off the main thread. */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val done = goAsync()
        Thread { try { Updater.dailyCheck(c.applicationContext) } finally { done.finish() } }.start()
    }
}
