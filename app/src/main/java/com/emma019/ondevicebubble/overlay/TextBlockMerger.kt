package com.emma019.ondevicebubble.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Merge vertically-adjacent OCR/a11y lines into paragraph bubbles. */
object TextBlockMerger {
    fun merge(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy({ it.top }, { it.left }))
        val out = mutableListOf<TextBlock>()
        var cur = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (shouldMerge(cur, next)) {
                cur = TextBlock(
                    text = joinLines(cur.text, next.text),
                    left = min(cur.left, next.left),
                    top = min(cur.top, next.top),
                    right = max(cur.right, next.right),
                    bottom = max(cur.bottom, next.bottom),
                )
            } else {
                out += cur
                cur = next
            }
        }
        out += cur
        return out
    }

    private fun shouldMerge(a: TextBlock, b: TextBlock): Boolean {
        val gap = b.top - a.bottom
        val lineH = max(a.height, 1)
        val closeVertically = gap <= (lineH * 0.85f).toInt()
        val overlapsX = a.left < b.right && b.left < a.right
        val similarLeft = abs(a.left - b.left) <= max(a.width, b.width) / 3
        return closeVertically && (overlapsX || similarLeft)
    }

    private fun joinLines(a: String, b: String): String {
        val left = a.trimEnd()
        val right = b.trimStart()
        if (left.endsWith("-") && right.isNotEmpty() && right[0].isLetter()) {
            return left.dropLast(1) + right
        }
        return "$left $right"
    }
}
