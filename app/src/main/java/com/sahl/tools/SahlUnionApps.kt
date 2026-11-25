package com.sahl.tools

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shows all Sahl Union apps inside Settings:
 *  - Installed apps show their real icon and open on tap.
 *  - Uninstalled apps show a Sahl emerald & gold install badge (⬇) and open their GitHub Releases page to install.
 */
object SahlUnionApps {
    private data class UnionApp(
        val pkgs: List<String>,
        val en: String,
        val ar: String,
        val repo: String
    )

    private val apps = listOf(
        UnionApp(listOf("com.sahl.phone"), "Phone", "الهاتف", "SahlPhone"),
        UnionApp(listOf("com.sahl.contacts"), "Contacts", "جهات الاتصال", "SahlContacts"),
        UnionApp(listOf("com.sahl.messages"), "Messages", "الرسائل", "SahlMessages"),
        UnionApp(listOf("com.sahl.camera"), "Camera", "الكاميرا", "SahlCamera"),
        UnionApp(listOf("com.sahl.gallery"), "Gallery", "المعرض", "SahlGallery"),
        UnionApp(listOf("com.sahl.files"), "Files", "الملفات", "SahlFiles"),
        UnionApp(listOf("com.sahl.adhan"), "Adhan", "الأذان", "SahlAdhan"),
        UnionApp(listOf("com.sahl.qibla", "com.aly.trueqibla"), "Qibla", "القبلة", "TrueQibla-Android"),
        UnionApp(listOf("com.sahl.tools"), "Tools", "الأدوات", "SahlTools"),
        UnionApp(listOf("com.aly.sahlsync"), "Sync", "المزامنة", "SahlSync"),
        UnionApp(listOf("com.aly.sahlpdf", "com.sahl.pdf"), "PDF", "PDF", "SahlPDF"),
        UnionApp(listOf("com.sahl.home"), "Theme", "الثيم", "SahlHome")
    )

    fun strip(c: Context, textColor: Int): View {
        val pm = c.packageManager
        val d = c.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val ar = c.resources.configuration.locales[0].language == "ar"

        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, px(4))
        }

        for (app in apps) {
            var installedPkg: String? = null
            var info: android.content.pm.ApplicationInfo? = null
            for (p in app.pkgs) {
                val ai = try { pm.getApplicationInfo(p, 0) } catch (_: Exception) { null }
                if (ai != null) {
                    installedPkg = p
                    info = ai
                    break
                }
            }

            val item = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(6), px(4), px(10), px(4))
                alpha = if (info != null) 1f else 0.72f
                setOnClickListener {
                    if (installedPkg != null) {
                        try { pm.getLaunchIntentForPackage(installedPkg)?.let { c.startActivity(it) } } catch (_: Exception) { }
                    } else {
                        try {
                            val url = "https://github.com/AliSherif1309/${app.repo}/releases/latest"
                            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    }
                }
            }

            if (info != null) {
                item.addView(ImageView(c).apply {
                    setImageDrawable(pm.getApplicationIcon(info))
                    layoutParams = LinearLayout.LayoutParams(px(44), px(44))
                })
            } else {
                val badge = FrameLayout(c).apply {
                    background = GradientDrawable().apply {
                        setColor(0xFF0B3B30.toInt())
                        cornerRadius = px(12).toFloat()
                        setStroke(px(1), 0xFFD4AF37.toInt())
                    }
                    addView(TextView(c).apply {
                        text = "⬇"
                        textSize = 17f
                        setTextColor(0xFFF5C84C.toInt())
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                item.addView(badge, LinearLayout.LayoutParams(px(44), px(44)))
            }

            val label = if (info != null) {
                pm.getApplicationLabel(info).toString().removePrefix("Sahl ").removePrefix("سهل ")
            } else {
                if (ar) app.ar else app.en
            }
            item.addView(TextView(c).apply {
                text = label
                textSize = 11f
                setTextColor(if (info != null) textColor else Color.argb(190, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, px(4), 0, 0)
            })
            row.addView(item)
        }

        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}
