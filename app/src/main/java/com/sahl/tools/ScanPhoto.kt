package com.sahl.tools

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.LinearLayout
import com.google.zxing.integration.android.IntentIntegrator

// ───────────────────────── QR & barcode scanner ─────────────────────────
class ScanActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = sahlScreen(getString(R.string.scan_t))
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 9)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
        else { col.addView(label(getString(R.string.need_camera), 16f, R.color.sub)); col.addView(sahlButton(getString(R.string.scan_again)) { scanOrAsk() }) }
    }

    private fun scanOrAsk() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun scan() = try {
        IntentIntegrator(this).setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false)
            .setOrientationLocked(false).initiateScan()
    } catch (e: Throwable) {
        col.addView(label(getString(R.string.scan_failed) + "\n" + e, 14f, R.color.sub))
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(req, res, data)
        val text = r?.contents
        col.removeAllViews()
        col.addView(label(getString(R.string.scan_t), 26f, R.color.emerald, true).apply { setPadding(0, 0, 0, dp(12)) })
        if (text == null) { finish(); return }
        val links = LinkCleaner.findAll(text)
        col.addSpaced(card().apply {
            addView(label(r.formatName ?: "", 13f, R.color.sub))
            addView(label(text, 18f).apply { setTextIsSelectable(true) })
            addView(row(sahlButton(getString(R.string.copy)) { copy(text) },
                sahlButton(getString(R.string.share)) { shareText(text) },
                if (links.isNotEmpty()) sahlButton(getString(R.string.check_link)) {
                    startActivity(Intent(this@ScanActivity, LinkActivity::class.java).putExtra(Intent.EXTRA_TEXT, text))
                } else sahlButton(getString(R.string.open)) { openLink(text) }))
        })
        col.addView(sahlButton(getString(R.string.scan_again)) { scan() })
    }
}

// ───────────────────────── Photo info remover ─────────────────────────
class PhotoActivity : Activity() {
    /** Every hidden detail we look for, with a friendly name. */
    private val tags = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensModel", ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_ORIENTATION
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) } }
        val col = sahlScreen(getString(R.string.photo_t))
        if (uris.isEmpty()) {
            col.addView(label(getString(R.string.photo_how), 16f, R.color.sub))
            col.addView(sahlButton(getString(R.string.pick_photos)) { startActivity(Intent(this, PickPhotosActivity::class.java)); finish() })
            return
        }
        val status = label(getString(R.string.working), 16f, R.color.emerald, true)
        col.addSpaced(card().apply { addView(status) })
        Thread {
            val out = ArrayList<Uri>()
            val reports = ArrayList<Pair<String, List<String>>>()
            for (u in uris) try {
                val found = describe(u)
                clean(u)?.let { out.add(it); reports.add(name(u) to found) }
            } catch (_: Exception) { }
            runOnUiThread {
                status.text = getString(R.string.photo_done, out.size)
                for ((n, found) in reports) col.addSpaced(card().apply {
                    addView(label(n, 15f, R.color.text, true))
                    if (found.isEmpty()) addView(label("✓  " + getString(R.string.nothing_hidden), 14f, R.color.sub))
                    else for (line in found) addView(label(line, 14f).apply { setPadding(0, dp(3), 0, dp(3)) })
                })
                if (out.isNotEmpty()) col.addView(sahlButton(getString(R.string.share_clean)) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, out).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                })
            }
        }.start()
    }

    private fun name(u: Uri): String = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: u.lastPathSegment ?: "photo"

    /** Reads what the photo is hiding, in plain words, before it is removed. */
    private fun describe(u: Uri): List<String> {
        val e = contentResolver.openInputStream(u)?.use { ExifInterface(it) } ?: return emptyList()
        val lines = ArrayList<String>()
        val ll = FloatArray(2)
        if (e.getLatLong(ll)) {
            val alt = e.getAltitude(Double.NaN)
            lines.add("📍  " + getString(R.string.ex_location, String.format(java.util.Locale.US, "%.5f, %.5f", ll[0], ll[1])) +
                if (!alt.isNaN()) "  (${alt.toInt()} m)" else "")
        }
        val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
        if (cam.isNotEmpty()) lines.add(getString(R.string.ex_camera, cam))
        e.getAttribute("LensModel")?.let { lines.add(getString(R.string.ex_lens, it)) }
        (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))?.let {
            lines.add(getString(R.string.ex_date, it))
        }
        val settings = listOfNotNull(
            e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
            e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { if (it < 1) "1/${(1 / it).toInt()}s" else "${it}s" },
            e.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
            e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it.substringBefore('/').toDoubleOrNull()?.div(it.substringAfter('/', "1").toDoubleOrNull() ?: 1.0)?.let { f -> String.format(java.util.Locale.US, "%.1f", f) } ?: it} mm" })
        if (settings.isNotEmpty()) lines.add("⚙  " + getString(R.string.ex_settings, settings.joinToString(" · ")))
        e.getAttribute(ExifInterface.TAG_SOFTWARE)?.let { lines.add("💻  " + getString(R.string.ex_software, it)) }
        listOfNotNull(e.getAttribute(ExifInterface.TAG_ARTIST), e.getAttribute(ExifInterface.TAG_COPYRIGHT)).takeIf { it.isNotEmpty() }
            ?.let { lines.add("👤  " + getString(R.string.ex_owner, it.joinToString(" · "))) }
        e.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)?.let { lines.add("🆔  " + getString(R.string.ex_id)) }
        val count = tags.count { e.getAttribute(it) != null }
        if (count > 0) lines.add(getString(R.string.ex_total, count))
        return lines
    }

    /** Re-draws the photo upright and saves a fresh JPEG — the hidden details are left behind. */
    private fun clean(src: Uri): Uri? {
        val rotation = contentResolver.openInputStream(src)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } ?: 0f
        var bmp = contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it) } ?: return null
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Sahl_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sahl Clean")
        }
        val dst = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        contentResolver.openOutputStream(dst)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return dst
    }
}
