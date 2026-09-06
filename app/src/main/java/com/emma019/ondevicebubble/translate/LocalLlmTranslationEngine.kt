package com.emma019.ondevicebubble.translate

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Higher-quality on-device path via MediaPipe LLM Inference.
 * Requires a `.task` model at [defaultModelFile] (not bundled — too large for git).
 */
class LocalLlmTranslationEngine(
    private val context: Context,
    private val modelFileName: String = "gemma.task",
) : TranslationEngine {
    override val id: String = "local_llm"
    override val displayName: String = "Local LLM (MediaPipe)"

    private var llm: LlmInference? = null

    fun defaultModelFile(): File =
        File(File(context.filesDir, "models"), modelFileName)

    override suspend fun isReady(): Boolean =
        llm != null || defaultModelFile().isFile

    override suspend fun prepare(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (llm != null) return@withContext
        val model = defaultModelFile()
        if (!model.isFile) {
            error(
                "Model missing: ${model.absolutePath}. " +
                    "Copy a MediaPipe LLM .task model there (see README).",
            )
        }
        onProgress("Loading local LLM…")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTokens(1024)
            .build()
        llm = LlmInference.createFromOptions(context, options)
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String = withContext(Dispatchers.Default) {
        prepare {}
        val client = llm ?: error("Local LLM not prepared")
        val prompt = buildPrompt(text, sourceLang, targetLang)
        client.generateResponse(prompt).trim()
    }

    private fun buildPrompt(text: String, sourceLang: String, targetLang: String): String {
        val src = langLabel(sourceLang)
        val dst = langLabel(targetLang)
        return """
            You are a professional translator. Translate the following text from $src to $dst.
            Keep meaning natural and idiomatic. Output ONLY the translation, no quotes or notes.

            Text:
            $text
        """.trimIndent()
    }

    private fun langLabel(code: String): String = when (code.lowercase()) {
        "en", "english" -> "English"
        "ja", "jp", "japanese" -> "Japanese"
        "zh", "chinese" -> "Chinese"
        "ko", "korean" -> "Korean"
        else -> code
    }

    override fun close() {
        llm?.close()
        llm = null
    }
}
