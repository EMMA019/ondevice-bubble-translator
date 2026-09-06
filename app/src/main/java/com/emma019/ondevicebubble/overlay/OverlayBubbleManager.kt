package com.emma019.ondevicebubble.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

class OverlayBubbleManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bubbleViews = mutableListOf<View>()
    private var controlView: View? = null
    private var autoChip: TextView? = null

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun showControls(
        autoEnabled: Boolean,
        onToggleAuto: () -> Unit,
        onTranslate: () -> Unit,
        onPolish: () -> Unit,
        onClear: () -> Unit,
        onStop: () -> Unit,
    ) {
        removeControls()
        val density = context.resources.displayMetrics.density
        val pad = (10 * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor("#E61B6BFF".toColorInt())
            minimumWidth = (56 * density).toInt()
        }

        fun chip(label: String, click: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER
                setOnClickListener { click() }
            }

        autoChip = chip(autoLabel(autoEnabled), onToggleAuto).also { row.addView(it) }
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_bubble_label), onTranslate))
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_polish_label), onPolish))
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
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - (72 * density).toInt())
            y = (140 * density).toInt()
        }

        enableDrag(row, params)
        windowManager.addView(row, params)
        controlView = row
    }

    fun updateAutoLabel(autoEnabled: Boolean) {
        autoChip?.text = autoLabel(autoEnabled)
    }

    private fun autoLabel(on: Boolean): String =
        if (on) context.getString(com.emma019.ondevicebubble.R.string.overlay_auto_on)
        else context.getString(com.emma019.ondevicebubble.R.string.overlay_auto_off)

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
        autoChip = null
    }

    fun dispose() {
        clearTranslations()
        removeControls()
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(v, params)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
    }
}
