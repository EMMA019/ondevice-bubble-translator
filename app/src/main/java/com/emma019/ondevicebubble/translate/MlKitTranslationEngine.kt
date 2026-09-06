package com.emma019.ondevicebubble.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device NMT with multi-language source → Japanese (or other target).
 * Keeps a small cache of translators per language pair.
 */
class MlKitTranslationEngine : TranslationEngine {
    override val id: String = "mlkit"
    override val displayName: String = "ML Kit (multi-lang)"

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val mutex = Mutex()

    override suspend fun isReady(): Boolean = translators.isNotEmpty()

    override suspend fun prepare(onProgress: (String) -> Unit) {
        // Warm a common pair; others download lazily on first use.
        ensureTranslator(TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE, onProgress)
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val source = mapLang(sourceLang) ?: return text
        val target = mapLang(targetLang) ?: TranslateLanguage.JAPANESE
        if (source == target) return text
        val client = ensureTranslator(source, target) {}
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
    ): Translator = mutex.withLock {
        val key = source to target
        translators[key]?.let { return it }
        onProgress("Downloading $source→$target…")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val client = Translation.getClient(options)
        suspendCancellableCoroutine { cont ->
            client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    translators[key] = client
                    cont.resume(client)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /**
     * Map BCP-47 / casual codes to ML Kit TranslateLanguage.
     * Returns null when unsupported.
     */
    fun mapLang(code: String): String? {
        val c = code.lowercase().substringBefore('-')
        return when (c) {
            "en", "english" -> TranslateLanguage.ENGLISH
            "ja", "jp", "japanese" -> TranslateLanguage.JAPANESE
            "zh", "zh-cn", "zh-tw", "chinese" -> TranslateLanguage.CHINESE
            "ko", "korean" -> TranslateLanguage.KOREAN
            "fr", "french" -> TranslateLanguage.FRENCH
            "de", "german" -> TranslateLanguage.GERMAN
            "es", "spanish" -> TranslateLanguage.SPANISH
            "it", "italian" -> TranslateLanguage.ITALIAN
            "pt", "portuguese" -> TranslateLanguage.PORTUGUESE
            "ru", "russian" -> TranslateLanguage.RUSSIAN
            "th", "thai" -> TranslateLanguage.THAI
            "vi", "vietnamese" -> TranslateLanguage.VIETNAMESE
            "hi", "hindi" -> TranslateLanguage.HINDI
            "id", "indonesian" -> TranslateLanguage.INDONESIAN
            "tr", "turkish" -> TranslateLanguage.TURKISH
            "pl", "polish" -> TranslateLanguage.POLISH
            "nl", "dutch" -> TranslateLanguage.DUTCH
            "sv", "swedish" -> TranslateLanguage.SWEDISH
            "ar", "arabic" -> TranslateLanguage.ARABIC
            "und", "" -> null
            else -> TranslateLanguage.fromLanguageTag(c)
        }
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
