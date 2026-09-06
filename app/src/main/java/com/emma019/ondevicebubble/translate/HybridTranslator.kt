package com.emma019.ondevicebubble.translate

import android.content.Context
import android.util.Log

/**
 * Auto path → ML Kit (fast).
 * Quality path → Local LLM when model is present (manual / polish / long blocks).
 */
class HybridTranslator(context: Context) {
    val mlkit = MlKitTranslationEngine()
    val localLlm = LocalLlmTranslationEngine(context.applicationContext)
    private var llmReady = false

    fun hasLocalModel(): Boolean = localLlm.defaultModelFile().isFile

    suspend fun ensureLlm(onProgress: (String) -> Unit = {}): Boolean {
        if (!hasLocalModel()) return false
        if (llmReady) return true
        return runCatching {
            localLlm.prepare(onProgress)
            llmReady = true
            true
        }.getOrElse {
            Log.e(TAG, "LLM prepare failed", it)
            false
        }
    }

    suspend fun translateFast(text: String, sourceLang: String, targetLang: String): String =
        mlkit.translate(text, sourceLang, targetLang)

    suspend fun translateQuality(text: String, sourceLang: String, targetLang: String): String {
        if (ensureLlm {}) {
            return runCatching {
                localLlm.translate(text, sourceLang, targetLang)
            }.getOrElse {
                Log.e(TAG, "LLM translate failed, fallback ML Kit", it)
                mlkit.translate(text, sourceLang, targetLang)
            }
        }
        return mlkit.translate(text, sourceLang, targetLang)
    }

    /**
     * @param preferQuality when true (manual 訳), long blocks use Local LLM if available.
     */
    suspend fun translateBlock(
        text: String,
        sourceLang: String,
        targetLang: String,
        preferQuality: Boolean,
    ): String {
        val useLlm = preferQuality && hasLocalModel() && text.length >= LONG_BLOCK_CHARS
        return if (useLlm) translateQuality(text, sourceLang, targetLang)
        else translateFast(text, sourceLang, targetLang)
    }

    fun mapLang(code: String): String? = mlkit.mapLang(code)

    fun close() {
        mlkit.close()
        localLlm.close()
        llmReady = false
    }

    companion object {
        private const val TAG = "HybridTranslator"
        const val LONG_BLOCK_CHARS = 48
    }
}
