package com.example.nataliacamo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Full-screen ROI editor shown above the game only while editing is enabled. */
class RoiOverlayView(context: Context) : View(context) {
    private var settings = DetectorSettings()
    private var editMode = false
    private var moving = false
    private var resizing = false
    private var downX = 0f
    private var downY = 0f
    private var start = settings
    private var onChanged: ((DetectorSettings) -> Unit)? = null

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(255, 190, 45)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
    }

    fun setSettings(value: DetectorSettings) {
        settings = value.normalized()
        invalidate()
    }

    fun setOnChanged(listener: (DetectorSettings) -> Unit) {
        onChanged = listener
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        moving = false
        resizing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!settings.showRoi) return

        val r = roiRect()
        if (editMode) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }
        canvas.drawRect(r, borderPaint)
        canvas.drawCircle(r.right, r.bottom, 14f, handlePaint)
        canvas.drawCircle(r.right, r.bottom, 9f, borderPaint)

        val label = if (editMode) "ROI NATALIA • DRAG / RESIZE" else "ROI"
        val y = (r.top - 12f).coerceAtLeast(28f)
        canvas.drawText(label, r.left.coerceAtLeast(8f), y, textPaint)
        if (editMode) {
            canvas.drawText(
                "x=${settings.left} y=${settings.top} w=${settings.width} h=${settings.height}",
                r.left.coerceAtLeast(8f),
                (r.bottom + 24f).coerceAtMost(height - 8f),
                subTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = roiRect()
                downX = event.x
                downY = event.y
                start = settings
                resizing = hypot(event.x - r.right, event.y - r.bottom) <= dp(34f)
                moving = !resizing && r.contains(event.x, event.y)
                return moving || resizing
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moving && !resizing) return true
                val dx = (event.x - downX) / width.coerceAtLeast(1)
                val dy = (event.y - downY) / height.coerceAtLeast(1)
                settings = if (moving) {
                    start.copy(
                        left = (start.left + dx * 1000f).toInt(),
                        top = (start.top + dy * 1000f).toInt()
                    )
                } else {
                    start.copy(
                        width = (start.width + dx * 1000f).toInt(),
                        height = (start.height + dy * 1000f).toInt()
                    )
                }.normalized()
                onChanged?.invoke(settings)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moving = false
                resizing = false
                return true
            }
        }
        return true
    }

    private fun roiRect(): RectF = RectF(
        width * settings.left / 1000f,
        height * settings.top / 1000f,
        width * (settings.left + settings.width) / 1000f,
        height * (settings.top + settings.height) / 1000f
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
