package com.emma019.ondevicebubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emma019.ondevicebubble.overlay.TextBlock
import com.emma019.ondevicebubble.translate.TextPreprocessor

/**
 * Optional helper: when enabled in system settings, callers can pull visible
 * text nodes without OCR. Phase 2 MVP still defaults to screenshot OCR.
 */
class TranslateAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun snapshotTextBlocks(): List<TextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<TextBlock>()
        walk(root, out)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<TextBlock>) {
        val text = node.text?.toString()?.let(TextPreprocessor::normalize).orEmpty()
        if (text.isNotBlank() && node.isVisibleToUser) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                out += TextBlock(text, rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out) }
        }
    }

    companion object {
        @Volatile
        var instance: TranslateAccessibilityService? = null
            private set
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
