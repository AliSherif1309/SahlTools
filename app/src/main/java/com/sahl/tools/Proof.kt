package com.sahl.tools

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sahl proofreader engine. Offline by default (word list + grammar rules, like Harper);
 * optional online deep check with LanguageTool. English text only — Arabic and other scripts are skipped.
 */
object Proof {
    const val SPELL = 0
    const val GRAMMAR = 1
    data class Issue(val start: Int, val len: Int, val kind: Int, val msg: String, val fixes: List<String>)

    @Volatile private var dict: HashMap<String, Int>? = null

    /** Common typos fixed directly (fast and reliable). */
    private val typos = mapOf(
        "teh" to "the", "recieve" to "receive", "recieved" to "received", "alot" to "a lot", "definately" to "definitely",
        "seperate" to "separate", "occured" to "occurred", "untill" to "until", "wich" to "which", "becuase" to "because",
        "beleive" to "believe", "thier" to "their", "tommorow" to "tomorrow", "tomorow" to "tomorrow", "goverment" to "government",
        "enviroment" to "environment", "accomodate" to "accommodate", "acheive" to "achieve", "adress" to "address",
        "begining" to "beginning", "calender" to "calendar", "comming" to "coming", "commited" to "committed",
        "concious" to "conscious", "embarass" to "embarrass", "existance" to "existence", "familar" to "familiar",
        "finaly" to "finally", "foriegn" to "foreign", "freind" to "friend", "garantee" to "guarantee", "happend" to "happened",
        "immediatly" to "immediately", "independant" to "independent", "knowlege" to "knowledge", "liason" to "liaison",
        "maintainance" to "maintenance", "neccessary" to "necessary", "necesary" to "necessary", "noticable" to "noticeable",
        "ocassion" to "occasion", "posession" to "possession", "prefered" to "preferred", "publically" to "publicly",
        "realy" to "really", "reccomend" to "recommend", "recomend" to "recommend", "refered" to "referred",
        "responsability" to "responsibility", "succesful" to "successful", "sucessful" to "successful", "suprise" to "surprise",
        "truely" to "truly", "wierd" to "weird", "writting" to "writing", "youre" to "you're", "dont" to "don't",
        "doesnt" to "doesn't", "didnt" to "didn't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't", "wasnt" to "wasn't",
        "im" to "I'm", "ive" to "I've", "thats" to "that's", "whats" to "what's", "lets" to "let's", "shouldnt" to "shouldn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "havent" to "haven't", "arent" to "aren't", "pls" to "please",
        "plz" to "please", "thx" to "thanks", "ur" to "your", "u" to "you", "r" to "are")

    private val extra = listOf("ok", "okay", "email", "emails", "online", "offline", "wifi", "app", "apps", "website", "login",
        "smartphone", "android", "iphone", "whatsapp", "google", "youtube", "doha", "qatar", "sahl", "inshallah", "mashallah",
        "alhamdulillah", "habibi", "covid", "biomedical", "teams", "excel", "powerpoint", "pdf", "url", "urls", "api")

    fun ensure(c: Context): Map<String, Int> {
        dict?.let { return it }
        synchronized(this) {
            dict?.let { return it }
            val m = HashMap<String, Int>(80_000)
            try {
                c.assets.open("en_words.txt").bufferedReader().useLines { lines ->
                    var rank = 0
                    for (l in lines) { val w = l.substringBefore(' ').trim().lowercase(); if (w.isNotEmpty() && w !in m) m[w] = rank++ }
                }
            } catch (_: Exception) { }
            for (w in extra) m.putIfAbsent(w, 5000)
            for (w in "abcdefghijklmnopqrstuvwxyz") m.putIfAbsent(w.toString(), 90_000)   // single letters (e.g. "a", "I")
            dict = m
            return m
        }
    }

    fun personal(c: Context): MutableSet<String> = Tools.prefs(c).getStringSet("proof_words", emptySet())!!.toMutableSet()
    fun learn(c: Context, w: String) { val s = personal(c); s.add(w.lowercase()); Tools.prefs(c).edit().putStringSet("proof_words", s).apply() }
    fun forget(c: Context, w: String) { val s = personal(c); s.remove(w); Tools.prefs(c).edit().putStringSet("proof_words", s).apply() }

    private fun latin(w: String) = w.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '’' }

    fun known(c: Context, word: String): Boolean {
        val d = ensure(c)
        if (d.isEmpty()) return true                         // no word list → never flag
        val w = word.lowercase().replace('’', '\'')
        if (w in d || w in personal(c)) return true
        if ('\'' in w) {                                      // contractions and possessives
            val base = w.substringBefore('\'')
            val tail = w.substringAfter('\'')
            if (tail in setOf("s", "t", "re", "ve", "ll", "d", "m") && (base in d || base + "n" in d || base.removeSuffix("n") in d)) return true
        }
        for ((suf, add) in listOf("s" to "", "es" to "", "ed" to "", "ed" to "e", "ing" to "", "ing" to "e", "ly" to "", "ies" to "y", "ied" to "y", "er" to "", "est" to ""))
            if (w.endsWith(suf) && w.length > suf.length + 2 && (w.removeSuffix(suf) + add) in d) return true
        return false
    }

    /** Ranked spelling suggestions (most common words first). */
    fun suggest(c: Context, word: String, limit: Int = 4): List<String> {
        val d = ensure(c)
        val w = word.lowercase()
        typos[w]?.let { return listOf(match(word, it)) }
        val e1 = edits(w)
        var found = e1.filter { it in d }
        if (found.isEmpty() && w.length in 3..12) found = e1.asSequence().flatMap { edits(it).asSequence() }.filter { it in d }.distinct().take(200).toList()
        return found.distinct().sortedBy { d[it] ?: Int.MAX_VALUE }.take(limit).map { match(word, it) }
    }

    /** Keeps the capital letter if the original word had one. */
    private fun match(orig: String, s: String) =
        if (orig.isNotEmpty() && orig[0].isUpperCase() && s.isNotEmpty()) s[0].uppercase() + s.substring(1) else s

    private fun edits(w: String): Set<String> {
        val out = HashSet<String>()
        val letters = "abcdefghijklmnopqrstuvwxyz"
        for (i in 0..w.length) {
            val a = w.substring(0, i); val b = w.substring(i)
            if (b.isNotEmpty()) out.add(a + b.substring(1))                                     // delete
            if (b.length > 1) out.add(a + b[1] + b[0] + b.substring(2))                          // swap
            for (ch in letters) {
                if (b.isNotEmpty()) out.add(a + ch + b.substring(1))                             // replace
                out.add(a + ch + b)                                                              // insert
            }
        }
        return out
    }

    private val wordRx = Regex("[A-Za-z][A-Za-z'’]*")

    /** Finds spelling and grammar issues in a piece of text. */
    fun check(c: Context, text: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val words = wordRx.findAll(text).toList()
        // Spelling
        for (m in words) {
            val w = m.value.trimEnd('\'', '’')
            if (w.length < 2 || !latin(w)) continue
            val prev = text.substring(0, m.range.first)
            if (Regex("(https?://|www\\.|@)\\S*$").containsMatchIn(prev)) continue          // inside a link or email
            if (w.length > 1 && w.all { it.isUpperCase() }) continue                          // acronyms: NASA, QNB
            val sentenceStart = prev.trimEnd().let { it.isEmpty() || it.last() in ".!?\n" }
            if (w[0].isUpperCase() && !sentenceStart && !known(c, w)) continue              // likely a name
            val typo = typos[w.lowercase()]
            if (typo != null) { issues.add(Issue(m.range.first, w.length, SPELL, c.getString(R.string.pr_m_typo), listOf(match(w, typo)))); continue }
            if (!known(c, w)) issues.add(Issue(m.range.first, w.length, SPELL, c.getString(R.string.pr_m_spell), suggest(c, w)))
        }
        // Grammar rules
        for ((i, m) in words.withIndex()) {
            val w = m.value; val lw = w.lowercase()
            val next = words.getOrNull(i + 1)
            // repeated word: "the the"
            if (next != null && next.value.equals(w, true) && text.substring(m.range.last + 1, next.range.first).isBlank() && lw !in setOf("had", "that", "very", "bye", "ha", "no"))
                issues.add(Issue(m.range.first, next.range.last + 1 - m.range.first, GRAMMAR, c.getString(R.string.pr_m_repeat), listOf(w)))
            // a / an
            if ((lw == "a" || lw == "an") && next != null && text.substring(m.range.last + 1, next.range.first) == " ") {
                val n = next.value.lowercase()
                val vowelSound = (n[0] in "aeiou" && !n.startsWith("uni") && !n.startsWith("use") && !n.startsWith("one") && !n.startsWith("eu") && !n.startsWith("usu")) ||
                    listOf("hour", "honest", "honor", "honour", "heir").any { n.startsWith(it) }
                if (lw == "a" && vowelSound) issues.add(Issue(m.range.first, 1, GRAMMAR, c.getString(R.string.pr_m_an), listOf(if (w[0].isUpperCase()) "An" else "an")))
                if (lw == "an" && !vowelSound && n[0].isLetter()) issues.add(Issue(m.range.first, 2, GRAMMAR, c.getString(R.string.pr_m_a), listOf(if (w[0].isUpperCase()) "A" else "a")))
            }
            // lowercase i
            if (w == "i") issues.add(Issue(m.range.first, 1, GRAMMAR, c.getString(R.string.pr_m_i), listOf("I")))
            // could of → could have
            if (lw in setOf("could", "should", "would", "must", "might") && next?.value?.lowercase() == "of")
                issues.add(Issue(next.range.first, 2, GRAMMAR, c.getString(R.string.pr_m_have), listOf("have")))
            // more then → more than
            if (lw in setOf("more", "less", "better", "worse", "rather", "other", "bigger", "smaller", "faster", "higher", "lower") && next?.value?.lowercase() == "then")
                issues.add(Issue(next.range.first, 4, GRAMMAR, c.getString(R.string.pr_m_than), listOf("than")))
            // your welcome → you're welcome
            if (lw == "your" && next?.value?.lowercase() in setOf("welcome", "right", "wrong", "going", "not", "being"))
                issues.add(Issue(m.range.first, 4, GRAMMAR, c.getString(R.string.pr_m_youre), listOf(if (w[0].isUpperCase()) "You're" else "you're")))
            // its a → it's a
            if (lw == "its" && next?.value?.lowercase() in setOf("a", "an", "the", "not", "been", "going", "time", "ok", "okay", "fine"))
                issues.add(Issue(m.range.first, 3, GRAMMAR, c.getString(R.string.pr_m_its), listOf(if (w[0].isUpperCase()) "It's" else "it's")))
        }
        // Capital letter at the start of a sentence
        Regex("(^|[.!?]\\s+|\\n)([a-z])").findAll(text).forEach { m ->
            val g = m.groups[2]!!
            val wEnd = wordRx.find(text, g.range.first)?.range?.last ?: g.range.first
            val word = text.substring(g.range.first, wEnd + 1)
            if (latin(word) && !Regex("(https?://|www\\.)$").containsMatchIn(text.substring(0, g.range.first)))
                issues.add(Issue(g.range.first, word.length, GRAMMAR, c.getString(R.string.pr_m_cap), listOf(word.replaceFirstChar { it.uppercase() })))
        }
        // Double spaces
        Regex("(?<=\\S)  +(?=\\S)").findAll(text).forEach { issues.add(Issue(it.range.first, it.value.length, GRAMMAR, c.getString(R.string.pr_m_space), listOf(" "))) }
        return issues.sortedBy { it.start }.distinctBy { it.start }
    }

    fun online(c: Context) = Tools.prefs(c).getBoolean("proof_online", false)

    private val cache = object : LinkedHashMap<String, List<Issue>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Issue>>?) = size > 60
    }

    /** Deep check with LanguageTool (only when the user turned it on). Falls back to offline on any problem. */
    fun checkBest(c: Context, text: String): List<Issue> {
        if (!online(c) || text.isBlank() || text.none { it in 'a'..'z' || it in 'A'..'Z' }) return check(c, text)
        synchronized(cache) { cache[text]?.let { return it } }
        return try {
            val con = (URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 3000; readTimeout = 4000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            con.outputStream.use { it.write(("language=en-US&text=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val ms = j.getJSONArray("matches")
            val out = (0 until ms.length()).map { k ->
                val m = ms.getJSONObject(k)
                val reps = m.getJSONArray("replacements")
                val word = text.substring(m.getInt("offset"), m.getInt("offset") + m.getInt("length"))
                val spell = m.getJSONObject("rule").optString("issueType") == "misspelling"
                Issue(m.getInt("offset"), m.getInt("length"), if (spell) SPELL else GRAMMAR, m.optString("message"),
                    (0 until minOf(4, reps.length())).map { reps.getJSONObject(it).getString("value") })
                    .takeUnless { spell && word.lowercase() in personal(c) }
            }.filterNotNull()
            synchronized(cache) { cache[text] = out }
            out
        } catch (_: Exception) { check(c, text) }
    }

    /** Applies the first fix of every issue (from the end so positions stay right). */
    fun fixAll(text: String, issues: List<Issue>): String {
        val sb = StringBuilder(text)
        for (i in issues.sortedByDescending { it.start }) if (i.fixes.isNotEmpty() && i.start + i.len <= sb.length) sb.replace(i.start, i.start + i.len, i.fixes[0])
        return sb.toString()
    }
}
