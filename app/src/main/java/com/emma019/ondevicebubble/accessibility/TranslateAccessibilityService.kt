package com.emma019.ondevicebubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emma019.ondevicebubble.overlay.TextBlock
import com.emma019.ondevicebubble.translate.TextPreprocessor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Primary text source: accessibility nodes.
 * Fallback for Chrome/Web: takeScreenshot() → OCR (no MediaProjection).
 */
class TranslateAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable {
        screenChangeListener?.onScreenMaybeChanged()
    }
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> scheduleNotify()
        }
    }

    override fun onInterrupt() = Unit

    private fun scheduleNotify() {
        if (screenChangeListener == null) return
        mainHandler.removeCallbacks(notifyRunnable)
        mainHandler.postDelayed(notifyRunnable, DEBOUNCE_MS)
    }

    fun snapshotTextBlocks(): List<TextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = LinkedHashMap<String, TextBlock>()
        walk(root, out)
        return out.values
            .filter { it.text.length >= 2 }
            .filterNot { isChromeChromeUi(it.text) }
            .sortedBy { it.top * 10000 + it.left }
    }

    /**
     * Screenshot via AccessibilityService (API 30+). Used when node text is too thin
     * (typical for Chrome article bodies).
     */
    suspend fun takeBitmapScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            val held = AtomicReference<Bitmap?>()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            held.set(bmp)
                            if (cont.isActive) cont.resume(bmp)
                        } catch (t: Throwable) {
                            Log.e(TAG, "screenshot convert failed", t)
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed code=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    private fun walk(node: AccessibilityNodeInfo, out: LinkedHashMap<String, TextBlock>) {
        val raw = sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.toString() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val text = TextPreprocessor.normalize(raw)
        if (text.isNotBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.width() > 2 && rect.height() > 2) {
                val key = "${text.lowercase()}@${rect.left / 8}:${rect.top / 8}"
                out.putIfAbsent(
                    key,
                    TextBlock(text, rect.left, rect.top, rect.right, rect.bottom),
                )
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                walk(child, out)
            }
        }
    }

    fun interface ScreenChangeListener {
        fun onScreenMaybeChanged()
    }

    companion object {
        private const val TAG = "TranslateA11y"
        private const val DEBOUNCE_MS = 700L

        private val chromeUi = setOf(
            "share", "save", "search", "menu", "home", "sign in", "subscribe",
            "add as preferred on google", "listen", "follow", "bbc",
        )

        fun isChromeChromeUi(text: String): Boolean {
            val t = text.trim().lowercase()
            if (t.length <= 24 && chromeUi.any { t == it || t.startsWith(it) }) return true
            return false
        }

        /** True when node text is too thin to trust (e.g. Chrome article). */
        fun needsOcrFallback(blocks: List<TextBlock>): Boolean {
            if (blocks.isEmpty()) return true
            val substantial = blocks.count { it.text.length >= 40 }
            val totalChars = blocks.sumOf { it.text.length }
            return substantial < 2 || totalChars < 120
        }

        @Volatile
        var instance: TranslateAccessibilityService? = null
            private set

        @Volatile
        var screenChangeListener: ScreenChangeListener? = null

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notifyRunnable)
        if (instance === this) instance = null
        super.onDestroy()
    }
}
