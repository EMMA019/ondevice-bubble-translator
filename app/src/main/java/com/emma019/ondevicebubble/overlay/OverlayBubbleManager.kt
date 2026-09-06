package com.emma019.ondevicebubble.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView

class OverlayBubbleManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bubbleViews = mutableListOf<View>()
    private var controlView: View? = null

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun showControls(
        onTranslate: () -> Unit,
        onClear: () -> Unit,
        onStop: () -> Unit,
    ) {
        removeControls()
        val density = context.resources.displayMetrics.density
        val pad = (10 * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor("#CC1B6BFF".toColorInt())
        }

        fun chip(label: String, click: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { click() }
            }

        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_bubble_label), onTranslate))
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_clear_label), onClear))
        row.addView(chip("×", onStop))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (120 * density).toInt()
        }

        enableDrag(row, params)
        windowManager.addView(row, params)
        controlView = row
    }

    fun showTranslations(blocks: List<TranslatedBlock>) {
        clearTranslations()
        val density = context.resources.displayMetrics.density
        blocks.forEach { block ->
            val card = MaterialCardView(context).apply {
                radius = 8 * density
                cardElevation = 2 * density
                setCardBackgroundColor("#F2FFF8E1".toColorInt())
                setContentPadding(
                    (6 * density).toInt(),
                    (4 * density).toInt(),
                    (6 * density).toInt(),
                    (4 * density).toInt(),
                )
            }
            val tv = TextView(context).apply {
                text = block.translated
                setTextColor("#FF222222".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            card.addView(tv)
            card.setOnClickListener {
                block.showingTranslation = !block.showingTranslation
                tv.text = if (block.showingTranslation) block.translated else block.original.text
            }

            val o = block.original
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = o.left
                y = o.top
                width = o.width.coerceAtLeast((80 * density).toInt())
            }
            windowManager.addView(card, params)
            bubbleViews += card
        }
    }

    fun clearTranslations() {
        bubbleViews.forEach { runCatching { windowManager.removeView(it) } }
        bubbleViews.clear()
    }

    fun removeControls() {
        controlView?.let { runCatching { windowManager.removeView(it) } }
        controlView = null
    }

    fun dispose() {
        clearTranslations()
        removeControls()
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        var lastX = 0
        var lastY = 0
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    params.x -= dx
                    params.y += dy
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }
    }
}
