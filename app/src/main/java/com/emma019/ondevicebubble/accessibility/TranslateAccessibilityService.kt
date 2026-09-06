package com.emma019.ondevicebubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emma019.ondevicebubble.overlay.TextBlock
import com.emma019.ondevicebubble.translate.TextPreprocessor

/**
 * Primary text source for fast translation: read visible nodes from the
 * active window (no screenshot / OCR). Also notifies listeners when the
 * screen likely changed (debounced) for always-on translation.
 */
class TranslateAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable {
        screenChangeListener?.onScreenMaybeChanged()
    }

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
            .sortedBy { it.top * 10000 + it.left }
    }

    fun contentFingerprint(): String {
        return snapshotTextBlocks()
            .joinToString("\n") { it.text }
            .hashCode()
            .toString()
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
                child.recycle()
            }
        }
    }

    fun interface ScreenChangeListener {
        fun onScreenMaybeChanged()
    }

    companion object {
        private const val DEBOUNCE_MS = 700L

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
