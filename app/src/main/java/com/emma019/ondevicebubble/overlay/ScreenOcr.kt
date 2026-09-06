package com.emma019.ondevicebubble.overlay

import android.graphics.Bitmap
import android.util.Log
import com.emma019.ondevicebubble.translate.TextPreprocessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class ScreenOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        val scaled = scaleForOcr(bitmap)
        return try {
            withTimeout(20_000) {
                recognizeInternal(scaled)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private suspend fun recognizeInternal(bitmap: Bitmap): List<TextBlock> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val blocks = result.textBlocks.mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        val cleaned = TextPreprocessor.normalize(block.text)
                        if (cleaned.length < 2) return@mapNotNull null
                        // Map boxes back to original full-screen coordinates.
                        TextBlock(
                            text = cleaned,
                            left = (box.left / lastScale).toInt(),
                            top = (box.top / lastScale).toInt(),
                            right = (box.right / lastScale).toInt(),
                            bottom = (box.bottom / lastScale).toInt(),
                        )
                    }
                    Log.i(TAG, "recognized ${blocks.size} blocks")
                    cont.resume(blocks)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "OCR failed", err)
                    cont.resumeWithException(err)
                }
        }

    private var lastScale = 1f

    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val maxW = 1080
        if (bitmap.width <= maxW) {
            lastScale = 1f
            return bitmap
        }
        lastScale = maxW.toFloat() / bitmap.width.toFloat()
        val h = (bitmap.height * lastScale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxW, h, true)
    }

    fun close() {
        recognizer.close()
    }

    companion object {
        private const val TAG = "ScreenOcr"
    }
}
