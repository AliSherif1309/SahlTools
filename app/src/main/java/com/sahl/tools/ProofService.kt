package com.sahl.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.textservice.SpellCheckerService
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The phone-wide spell & grammar checker: Android asks it about text typed in apps and underlines what it reports. */
class SahlSpellChecker : SpellCheckerService() {
    override fun createSession(): Session = object : Session() {
        override fun onCreate() { Proof.ensure(this@SahlSpellChecker) }

        override fun onGetSuggestions(ti: TextInfo, limit: Int): SuggestionsInfo {
            val w = ti.text ?: ""
            val ctx = this@SahlSpellChecker
            if (w.length < 2 || w.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' && it != '’' } || Proof.known(ctx, w))
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray(), ti.cookie, ti.sequence)
            return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                Proof.suggest(ctx, w, limit.coerceIn(1, 5)).toTypedArray(), ti.cookie, ti.sequence)
        }

        override fun onGetSentenceSuggestionsMultiple(tis: Array<TextInfo>, limit: Int): Array<SentenceSuggestionsInfo> {
            val ctx = this@SahlSpellChecker
            return tis.map { ti ->
                val text = ti.text ?: ""
                val issues = try { Proof.checkBest(ctx, text) } catch (_: Exception) { emptyList() }
                val infos = issues.map { i ->
                    val attr = if (i.kind == Proof.GRAMMAR && Build.VERSION.SDK_INT >= 31) SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR
                               else SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                    SuggestionsInfo(attr or (if (i.fixes.isNotEmpty()) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0),
                        i.fixes.take(limit.coerceIn(1, 5)).toTypedArray(), ti.cookie, ti.sequence)
                }
                SentenceSuggestionsInfo(infos.toTypedArray(), issues.map { it.start }.toIntArray(), issues.map { it.len }.toIntArray())
            }.toTypedArray()
        }
    }
}

/** Shared UI: shows text with problems underlined and a card per problem with tap-to-fix buttons. */
fun Activity.proofReport(holder: LinearLayout, text: String, issues: List<Proof.Issue>, onFix: (String) -> Unit) {
    holder.removeAllViews()
    val sp = SpannableString(text)
    for (i in issues) if (i.start + i.len <= text.length) {
        sp.setSpan(UnderlineSpan(), i.start, i.start + i.len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(if (i.kind == Proof.SPELL) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()), i.start, i.start + i.len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    holder.addView(card().apply { addView(TextView(this@proofReport).apply { this.text = sp; textSize = 16f; typeface = sahlFont(); setTextIsSelectable(true); setTextColor(getColor(R.color.text)) }) })
    holder.addView(label(if (issues.isEmpty()) "✓  " + getString(R.string.pr_clean) else getString(R.string.pr_found, issues.size,
        issues.count { it.kind == Proof.SPELL }, issues.count { it.kind == Proof.GRAMMAR }), 15f, R.color.emerald, true).apply { setPadding(dp(4), dp(10), 0, dp(6)) })
    if (issues.any { it.fixes.isNotEmpty() }) holder.addView(sahlButton("✨  " + getString(R.string.pr_fix_all)) { onFix(Proof.fixAll(text, issues)) })
    for (i in issues) holder.addSpaced(card().apply {
        val bad = text.substring(i.start, minOf(text.length, i.start + i.len))
        addView(label((if (i.kind == Proof.SPELL) "🔴  " else "🔵  ") + "\"" + bad + "\"", 16f, bold = true))
        addView(label(i.msg, 13f, R.color.sub))
        val row = LinearLayout(this@proofReport).apply { gravity = Gravity.START }
        for (f in i.fixes) row.addView(label(f.ifEmpty { "∅" }, 15f, R.color.card, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(R.color.emerald, 14)
            setOnClickListener { onFix(StringBuilder(text).replace(i.start, i.start + i.len, f).toString()) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); topMargin = dp(6) })
        if (row.childCount > 0) addView(android.widget.HorizontalScrollView(this@proofReport).apply { addView(row) })
        if (i.kind == Proof.SPELL) addView(sahlButton("＋ " + getString(R.string.pr_add_word, bad)) { Proof.learn(this@proofReport, bad); onFix(text) })
    })
}

/** Select text in any app → "Proofread" → fix it and put it back. */
class ProofPopupActivity : Activity() {
    private var text = ""
    private var readOnly = true
    private lateinit var holder: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) || intent.action != Intent.ACTION_PROCESS_TEXT
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(R.color.bg, 24); setPadding(dp(16), dp(14), dp(16), dp(10)) }
        col.addView(label("✍  " + getString(R.string.pr_t), 18f, R.color.emerald, true))
        holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(sahlButton(getString(R.string.copy)) { copy(text) })
        actions.addView(sahlButton(getString(if (readOnly) android.R.string.ok else R.string.pr_replace)) {
            if (!readOnly) setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
            finish()
        })
        col.addView(actions)
        setContentView(ScrollView(this).apply { addView(col) })
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        run()
    }

    private fun run() {
        holder.removeAllViews(); holder.addView(label(getString(R.string.working), 14f, R.color.sub))
        val t = text
        Thread {
            val issues = Proof.checkBest(this, t)
            runOnUiThread { proofReport(holder, t, issues) { fixed -> text = fixed; run() } }
        }.start()
    }
}

/** Settings & test page for the proofreader. */
class ProofActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) { super.onCreate(b); col = sahlScreen(getString(R.string.pr_t)) }
    override fun onResume() { super.onResume(); draw() }

    private fun selected() = try {
        Settings.Secure.getString(contentResolver, "selected_spell_checker")?.startsWith(packageName) == true
    } catch (_: Exception) { false }

    private fun draw() {
        while (col.childCount > 1) col.removeViewAt(1)
        val on = selected()
        col.addSpaced(card().apply {
            background = rounded(if (on) R.color.emerald else R.color.card, 20)
            addView(label(if (on) "✓  " + getString(R.string.pr_on) else "⚠  " + getString(R.string.pr_off), 18f, if (on) R.color.card else R.color.text, true))
            addView(label(getString(R.string.pr_how), 13f, if (on) R.color.card else R.color.sub))
            if (!on) {
                if (Shell.ready()) addView(sahlButton("⚡  " + getString(R.string.pr_enable_shizuku)) {
                    Thread {
                        val r = Shell.run("settings put secure spell_checker_enabled 1; settings put secure selected_spell_checker $packageName/.SahlSpellChecker; settings put secure selected_spell_checker_subtype 0")
                        runOnUiThread { Toast.makeText(this@ProofActivity, if (r.first == 0) R.string.pr_enabled else R.string.db_failed, Toast.LENGTH_SHORT).show(); draw() }
                    }.start()
                })
                addView(sahlButton(getString(R.string.pr_enable_manual)) { openSpellSettings() })
                addView(label(getString(R.string.pr_manual_steps), 13f, R.color.sub))
            }
        })
        // Options
        col.addSpaced(card().apply {
            addView(android.widget.Switch(this@ProofActivity).apply {
                text = getString(R.string.pr_online); typeface = sahlFont(); setTextColor(getColor(R.color.text))
                isChecked = Proof.online(this@ProofActivity)
                setOnCheckedChangeListener { _, v -> Tools.prefs(this@ProofActivity).edit().putBoolean("proof_online", v).apply() }
            })
            addView(label(getString(R.string.pr_online_note), 12f, R.color.sub))
            addView(label(getString(R.string.pr_select_tip), 13f).apply { setPadding(0, dp(10), 0, 0) })
        })
        // Try it
        col.addView(label(getString(R.string.pr_try), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        val input = EditText(this).apply {
            hint = getString(R.string.pr_try_hint); typeface = sahlFont(); minLines = 3; gravity = Gravity.TOP or Gravity.START
            setText(Tools.prefs(this@ProofActivity).getString("proof_last", "i has recieve teh email and its a  good idea, could of been better then before."))
        }
        col.addView(input, LinearLayout.LayoutParams(-1, -2))
        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun check() {
            val t = input.text.toString()
            Tools.prefs(this).edit().putString("proof_last", t).apply()
            Thread { val iss = Proof.checkBest(this, t); runOnUiThread { proofReport(result, t, iss) { fixed -> input.setText(fixed); check() } } }.start()
        }
        col.addView(row(sahlButton("✍  " + getString(R.string.check)) { check() }, sahlButton(getString(R.string.copy)) { copy(input.text.toString()) }))
        col.addView(result)
        // Personal dictionary
        val words = Proof.personal(this).sorted()
        col.addView(label(getString(R.string.pr_dict, words.size), 16f, R.color.emerald, true).apply { setPadding(dp(4), dp(10), 0, dp(4)) })
        col.addSpaced(card().apply {
            val add = EditText(this@ProofActivity).apply { hint = getString(R.string.pr_dict_hint); typeface = sahlFont(); setSingleLine() }
            addView(add, LinearLayout.LayoutParams(-1, -2))
            addView(sahlButton("＋ " + getString(R.string.cg_add)) { add.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Proof.learn(this@ProofActivity, it); draw() } })
            for (w in words) addView(row(label(w, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                sahlButton("✕") { Proof.forget(this@ProofActivity, w); draw() }))
        })
    }

    private fun openSpellSettings() {
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"),
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), Intent(Settings.ACTION_LOCALE_SETTINGS))
        for (i in tries) try { startActivity(i); return } catch (_: Exception) { }
    }
}
