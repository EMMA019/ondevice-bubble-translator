package com.emma019.ondevicebubble.overlay

data class TextBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class TranslatedBlock(
    val original: TextBlock,
    var translated: String,
    var sourceLang: String = "en",
    var showingTranslation: Boolean = true,
)
