package com.sahl.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases: checks for a newer signed APK,
 * downloads it inside the app and hands it to Android's installer.
 * Reusable in every Sahl app — only REPO changes.
 */
object Updater {
    private const val REPO = "AliSherif1309/SahlTools"
    data class Release(val code: Int, val tag: String, val apkUrl: String)

    /** Blocking — call off the main thread. Returns a release only if it is newer than this build. */
    fun fetchNewer(): Release? = try {
        val con = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("Accept", "application/vnd.github+json") }
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name")                       // e.g. v1.7
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) assets.getJSONObject(i).let {
            if (it.getString("name").endsWith(".apk")) url = it.getString("browser_download_url")
        }
        if (code > BuildConfig.VERSION_CODE && url.isNotEmpty()) Release(code, tag, url) else null
    } catch (e: Exception) { null }

    /** Blocking — downloads the APK straight into an install session, reporting 0–100 progress. */
    fun install(ctx: Context, r: Release, progress: (Int) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            val con = URL(r.apkUrl).openConnection() as HttpURLConnection   // GitHub redirects are followed
            val total = con.contentLengthLong
            con.inputStream.use { input ->
                s.openWrite("update.apk", 0, total).use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                    s.fsync(out)
                }
            }
            val done = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, InstallResult::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(done.intentSender)
        }
    }

    /** True if Android lets this app install updates; otherwise opens the setting to allow it. */
    fun canInstall(a: Activity): Boolean {
        if (a.packageManager.canRequestPackageInstalls()) return true
        a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:" + a.packageName)))
        return false
    }
}

/** Android asks the user to confirm the install; this shows that confirmation screen. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -99) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
