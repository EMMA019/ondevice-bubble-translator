package com.emma019.ondevicebubble.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Baseline on-device NMT. Useful as the quality bar to beat.
 */
class MlKitTranslationEngine : TranslationEngine {
    override val id: String = "mlkit"
    override val displayName: String = "ML Kit (baseline)"

    private var translator: Translator? = null
    private var preparedPair: Pair<String, String>? = null

    override suspend fun isReady(): Boolean = translator != null

    override suspend fun prepare(onProgress: (String) -> Unit) {
        ensureTranslator(TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE, onProgress)
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val source = mapLang(sourceLang)
        val target = mapLang(targetLang)
        ensureTranslator(source, target) {}
        val client = translator ?: error("Translator not prepared")
        return suspendCancellableCoroutine { cont ->
            client.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun ensureTranslator(
        source: String,
        target: String,
        onProgress: (String) -> Unit,
    ) {
        if (translator != null && preparedPair == source to target) return
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val client = Translation.getClient(options)
        onProgress("Downloading language pack…")
        suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator = client
                    preparedPair = source to target
                    cont.resume(Unit)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private fun mapLang(code: String): String = when (code.lowercase()) {
        "en", "english" -> TranslateLanguage.ENGLISH
        "ja", "jp", "japanese" -> TranslateLanguage.JAPANESE
        "zh", "chinese" -> TranslateLanguage.CHINESE
        "ko", "korean" -> TranslateLanguage.KOREAN
        else -> TranslateLanguage.ENGLISH
    }

    override fun close() {
        translator?.close()
        translator = null
        preparedPair = null
    }
}
