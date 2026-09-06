package com.emma019.ondevicebubble.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Detects BCP-47 language codes for on-device routing (e.g. en, ko, zh). */
class LanguageDetector {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build(),
    )

    suspend fun detect(text: String): String = suspendCancellableCoroutine { cont ->
        client.identifyLanguage(text)
            .addOnSuccessListener { code ->
                cont.resume(if (code == "und") "und" else code)
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() {
        client.close()
    }
}
