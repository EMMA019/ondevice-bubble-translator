package com.emma019.ondevicebubble.translate

/**
 * Shared text cleanup for OCR-ish and pasted copy.
 * Keep this free of engine-specific logic.
 */
object TextPreprocessor {
    private val softHyphenLineBreak = Regex("([A-Za-zÁ-ÿ])-\\n([A-Za-zÁ-ÿ])")
    private val loneNewlines = Regex("(?<!\\n)\\n(?!\\n)")
    private val multiSpace = Regex("[\\t ]+")
    private val multiBlank = Regex("\\n{3,}")

    fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = softHyphenLineBreak.replace(t, "$1$2")
        // Join single newlines that often come from wrapped UI / OCR lines.
        t = loneNewlines.replace(t, " ")
        t = multiSpace.replace(t, " ")
        t = multiBlank.replace(t, "\n\n")
        return t.trim()
    }
}
