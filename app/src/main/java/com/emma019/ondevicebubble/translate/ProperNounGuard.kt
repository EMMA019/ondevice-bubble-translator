package com.emma019.ondevicebubble.translate

/**
 * Light heuristics to keep acronyms / person names from being mangled
 * (e.g. BBC → イギリス放送株式会社).
 */
object ProperNounGuard {
    private val tokenRe = Regex("\\S+")

    data class Protected(val masked: String, val tokens: List<String>)

    fun protect(text: String): Protected {
        val saved = mutableListOf<String>()
        val masked = tokenRe.replace(text) { m ->
            val t = m.value
            when {
                isAcronym(t) || isNameToken(t, text, m.range) -> {
                    val id = saved.size
                    saved += stripPunctKeep(t)
                    "⟦P$id⟧"
                }
                else -> t
            }
        }
        return Protected(masked, saved)
    }

    fun restore(translated: String, tokens: List<String>): String {
        var out = translated
        tokens.forEachIndexed { i, original ->
            out = out.replace("⟦P$i⟧", original)
            out = out.replace("[P$i]", original)
        }
        return out
    }

    private fun stripPunctKeep(token: String): String =
        token.trim(',', '.', ':', ';', ')', '(', '"', '\'')

    private fun isAcronym(token: String): Boolean {
        val t = stripPunctKeep(token)
        return t.length in 2..5 && t.all { it.isUpperCase() || it.isDigit() } &&
            t.any { it.isLetter() }
    }

    private fun isNameToken(token: String, full: String, range: IntRange): Boolean {
        val t = stripPunctKeep(token)
        if (t.length < 2 || t.length > 20) return false
        if (!t[0].isUpperCase() || !t.drop(1).all { it.isLowerCase() || it == '-' }) return false
        val before = full.substring(0, range.first).trimEnd()
            .substringAfterLast(' ', missingDelimiterValue = "")
        val after = full.substring(range.last + 1).trimStart()
            .substringBefore(' ', missingDelimiterValue = "")
        return looksNameWord(before) || looksNameWord(after)
    }

    private fun looksNameWord(token: String): Boolean {
        val t = stripPunctKeep(token)
        return t.length in 2..20 && t[0].isUpperCase() &&
            t.drop(1).all { it.isLowerCase() || it == '-' }
    }
}
