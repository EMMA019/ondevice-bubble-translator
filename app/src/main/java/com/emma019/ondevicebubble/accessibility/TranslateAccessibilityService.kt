package com.emma019.ondevicebubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emma019.ondevicebubble.overlay.TextBlock
import com.emma019.ondevicebubble.translate.TextPreprocessor

/**
 * Primary text source for fast translation: read visible nodes from the
 * active window (no screenshot / OCR).
 */
class TranslateAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun snapshotTextBlocks(): List<TextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = LinkedHashMap<String, TextBlock>()
        walk(root, out)
        return out.values
            .filter { it.text.length >= 2 }
            .sortedBy { it.top * 10000 + it.left }
    }

    private fun walk(node: AccessibilityNodeInfo, out: LinkedHashMap<String, TextBlock>) {
        if (!node.isVisibleToUser) {
            // Still walk children; visibility flags can be weird in WebViews.
        }
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

    companion object {
        @Volatile
        var instance: TranslateAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
