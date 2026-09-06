package com.emma019.ondevicebubble.overlay

import android.graphics.Bitmap
import com.emma019.ondevicebubble.translate.TextPreprocessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ScreenOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.mapNotNull { block ->
                    val box = block.boundingBox ?: return@mapNotNull null
                    val cleaned = TextPreprocessor.normalize(block.text)
                    if (cleaned.isBlank()) return@mapNotNull null
                    // Skip tiny noise / already-CJK-only crumbs for en→ja MVP focus.
                    if (cleaned.length < 2) return@mapNotNull null
                    TextBlock(
                        text = cleaned,
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                    )
                }
                cont.resume(blocks)
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() {
        recognizer.close()
    }
}
