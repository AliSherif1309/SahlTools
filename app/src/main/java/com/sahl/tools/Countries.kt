package com.sahl.tools

/** Country of an international number (for unknown callers and contact numbers). */
object Countries {
    private val map = mapOf(
        "974" to ("Qatar" to "قطر"), "971" to ("UAE" to "الإمارات"), "966" to ("Saudi Arabia" to "السعودية"),
        "965" to ("Kuwait" to "الكويت"), "973" to ("Bahrain" to "البحرين"), "968" to ("Oman" to "عُمان"),
        "20" to ("Egypt" to "مصر"), "962" to ("Jordan" to "الأردن"), "961" to ("Lebanon" to "لبنان"),
        "963" to ("Syria" to "سوريا"), "964" to ("Iraq" to "العراق"), "970" to ("Palestine" to "فلسطين"),
        "967" to ("Yemen" to "اليمن"), "249" to ("Sudan" to "السودان"), "212" to ("Morocco" to "المغرب"),
        "213" to ("Algeria" to "الجزائر"), "216" to ("Tunisia" to "تونس"), "218" to ("Libya" to "ليبيا"),
        "90" to ("Turkey" to "تركيا"), "98" to ("Iran" to "إيران"), "91" to ("India" to "الهند"),
        "92" to ("Pakistan" to "باكستان"), "880" to ("Bangladesh" to "بنغلاديش"), "94" to ("Sri Lanka" to "سريلانكا"),
        "977" to ("Nepal" to "نيبال"), "63" to ("Philippines" to "الفلبين"), "62" to ("Indonesia" to "إندونيسيا"),
        "60" to ("Malaysia" to "ماليزيا"), "86" to ("China" to "الصين"), "44" to ("UK" to "بريطانيا"),
        "1" to ("USA/Canada" to "أمريكا/كندا"), "33" to ("France" to "فرنسا"), "49" to ("Germany" to "ألمانيا"),
        "39" to ("Italy" to "إيطاليا"), "34" to ("Spain" to "إسبانيا"), "7" to ("Russia" to "روسيا"),
        "254" to ("Kenya" to "كينيا"), "234" to ("Nigeria" to "نيجيريا"), "251" to ("Ethiopia" to "إثيوبيا")
    )

    /** Returns the country name, or null for local numbers / unknown codes. */
    fun of(number: String, localCc: String, arabic: Boolean): String? {
        val t = Norm.text(number).trim()
        var d = Norm.digits(t)
        d = when {
            t.startsWith("+") -> d
            d.startsWith("00") -> d.drop(2)
            else -> return null
        }
        for (len in 3 downTo 1) {
            val code = d.take(len)
            val c = map[code] ?: continue
            if (code == localCc) return null
            return if (arabic) c.second else c.first
        }
        return null
    }
}
