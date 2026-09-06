package com.emma019.ondevicebubble.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import kotlin.math.abs

class OverlayBubbleManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bubbleViews = mutableListOf<View>()
    private var controlView: View? = null
    private var expandedPanel: View? = null
    private var collapsedFab: View? = null
    private var autoChip: TextView? = null
    private var panelExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapsePanel() }

    private var onToggleAuto: (() -> Unit)? = null
    private var onTranslate: (() -> Unit)? = null
    private var onPolish: (() -> Unit)? = null
    private var onClear: (() -> Unit)? = null
    private var onStop: (() -> Unit)? = null
    private var autoEnabledCached = true

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
        this.autoEnabledCached = autoEnabled
        this.onToggleAuto = onToggleAuto
        this.onTranslate = onTranslate
        this.onPolish = onPolish
        this.onClear = onClear
        this.onStop = onStop
        removeControls()
        showCollapsedFab()
    }

    fun updateAutoLabel(autoEnabled: Boolean) {
        autoEnabledCached = autoEnabled
        autoChip?.text = autoLabel(autoEnabled)
    }

    private fun autoLabel(on: Boolean): String =
        if (on) context.getString(com.emma019.ondevicebubble.R.string.overlay_auto_on)
        else context.getString(com.emma019.ondevicebubble.R.string.overlay_auto_off)

    private fun showCollapsedFab() {
        panelExpanded = false
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#FF1B6BFF".toColorInt())
        }
        val fab = TextView(context).apply {
            text = "訳"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = bg
            setOnClickListener { expandPanel() }
        }
        val params = baseParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * density).toInt()
            y = (140 * density).toInt()
        }
        windowManager.addView(fab, params)
        collapsedFab = fab
        controlView = fab
    }

    private fun expandPanel() {
        if (panelExpanded) return
        removeControlsOnly()
        panelExpanded = true
        val density = context.resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor("#FF1B6BFF".toColorInt())
            }
            minimumWidth = (56 * density).toInt()
        }

        fun chip(label: String, click: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER
                setOnClickListener {
                    click()
                    scheduleCollapse()
                }
            }

        autoChip = chip(autoLabel(autoEnabledCached)) {
            onToggleAuto?.invoke()
        }.also { row.addView(it) }
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_bubble_label)) {
            onTranslate?.invoke()
        })
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_polish_label)) {
            onPolish?.invoke()
        })
        row.addView(chip(context.getString(com.emma019.ondevicebubble.R.string.overlay_clear_label)) {
            onClear?.invoke()
        })
        row.addView(chip("×") { onStop?.invoke() })

        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * density).toInt()
            y = (140 * density).toInt()
        }
        enableDrag(row, params)
        windowManager.addView(row, params)
        expandedPanel = row
        controlView = row
        scheduleCollapse()
    }

    private fun scheduleCollapse() {
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, 2800L)
    }

    private fun collapsePanel() {
        if (!panelExpanded) return
        removeControlsOnly()
        showCollapsedFab()
    }

    fun showTranslations(blocks: List<TranslatedBlock>) {
        clearTranslations()
        val density = context.resources.displayMetrics.density
        val padH = (6 * density).toInt()
        val padV = (4 * density).toInt()

        blocks.forEach { block ->
            val o = block.original
            // Fully opaque cover over the original glyph box.
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor("#FFFFFDF5".toColorInt())
            }
            val card = FrameLayout(context).apply {
                background = bg
                setPadding(padH, padV, padH, padV)
            }
            val tv = TextView(context).apply {
                text = block.translated
                setTextColor("#FF111111".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 12
            }
            card.addView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            card.setOnClickListener {
                block.showingTranslation = !block.showingTranslation
                tv.text = if (block.showingTranslation) block.translated else block.original.text
                fitText(tv, o.width - padH * 2, o.height - padV * 2)
            }

            val w = o.width.coerceAtLeast((72 * density).toInt())
            val h = o.height.coerceAtLeast((28 * density).toInt())
            val params = baseParams(w, h).apply {
                gravity = Gravity.TOP or Gravity.START
                x = o.left
                y = o.top
            }
            windowManager.addView(card, params)
            // Measure after attach-ish: fit into the covered rect.
            card.post { fitText(tv, w - padH * 2, h - padV * 2) }
            bubbleViews += card
        }
    }

    /** Shrink text until it fits the opaque cover rect. */
    private fun fitText(tv: TextView, maxW: Int, maxH: Int) {
        if (maxW <= 0 || maxH <= 0) return
        var sp = 14f
        val minSp = 8f
        while (sp >= minSp) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (tv.measuredHeight <= maxH && tv.measuredWidth <= maxW) break
            sp -= 0.5f
        }
    }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    fun clearTranslations() {
        bubbleViews.forEach { runCatching { windowManager.removeView(it) } }
        bubbleViews.clear()
    }

    private fun removeControlsOnly() {
        mainHandler.removeCallbacks(collapseRunnable)
        controlView?.let { runCatching { windowManager.removeView(it) } }
        controlView = null
        expandedPanel = null
        collapsedFab = null
        autoChip = null
    }

    fun removeControls() {
        removeControlsOnly()
        panelExpanded = false
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
                    scheduleCollapse()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // END gravity: x grows leftward from the right edge.
                        params.x = (startX - dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(v, params)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
    }
}
