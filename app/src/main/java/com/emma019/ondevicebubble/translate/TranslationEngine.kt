package com.emma019.ondevicebubble.translate

/**
 * Swap-friendly translation backend. Phase 1 compares engines on plain text.
 * Phase 2 will feed OCR / accessibility text through the same seam.
 */
interface TranslationEngine {
    val id: String
    val displayName: String

    /** True when the engine can translate without further setup. */
    suspend fun isReady(): Boolean

    /**
     * Ensure models/packs are present. May download on first use (ML Kit).
     * Emit human-readable progress via [onProgress] when useful.
     */
    suspend fun prepare(onProgress: (String) -> Unit = {})

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String

    fun close()
}
