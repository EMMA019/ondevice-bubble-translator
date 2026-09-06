package com.emma019.ondevicebubble.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPreprocessorTest {
    @Test
    fun joinsWrappedLinesAndCollapsesSpaces() {
        val raw = "Hello\nworld.\n\n\nNext  paragraph."
        val out = TextPreprocessor.normalize(raw)
        assertEquals("Hello world.\n\nNext paragraph.", out)
    }

    @Test
    fun repairsSoftHyphenBreaks() {
        val raw = "trans-\nlation"
        assertEquals("translation", TextPreprocessor.normalize(raw))
    }
}
