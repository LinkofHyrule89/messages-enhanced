package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen

/** Two-tone color circle. The selected state is a rounded-square ring, matching the web swatch. */
class ThemeSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var lightColor: Int = 0xFF9CC3FF.toInt()
        set(value) { field = value; invalidate() }
    var themColor: Int = 0xFF262B33.toInt()
        set(value) { field = value; invalidate() }
    var meColor: Int = 0xFF2F6FE4.toInt()
        set(value) { field = value; invalidate() }
    var ringColor: Int = 0xFF9CC3FF.toInt()
        set(value) { field = value; invalidate() }
    var solidColor: Int? = null
        set(value) { field = value; invalidate() }
    var selectedSwatch: Boolean = false
        set(value) { field = value; invalidate() }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x26FFFFFF
    }
    private val matrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val stroke = width * 4f / 108f
        val pad = width * 8f / 108f
        val cx = width / 2f
        val cy = height / 2f
        if (selectedSwatch) {
            ringPaint.strokeWidth = stroke
            ringPaint.color = ringColor
            val inset = stroke / 2f
            val radius = width * 30f / 108f
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, ringPaint)
        }
        val rect = RectF(pad + stroke, pad + stroke, width - pad - stroke, height - pad - stroke)
        val solid = solidColor
        if (solid != null) {
            fillPaint.shader = null
            fillPaint.color = solid
        } else {
            val shader = SweepGradient(
                cx,
                cy,
                intArrayOf(lightColor, lightColor, themColor, themColor, meColor, meColor),
                floatArrayOf(0f, 0.499f, 0.501f, 0.749f, 0.751f, 1f),
            )
            matrix.setRotate(ThemeScreen.SWATCH_ROTATION_DEGREES, cx, cy)
            shader.setLocalMatrix(matrix)
            fillPaint.shader = shader
        }
        val innerRadius = rect.width() * 22f / 84f
        if (selectedSwatch) canvas.drawRoundRect(rect, innerRadius, innerRadius, fillPaint)
        else canvas.drawOval(rect, fillPaint)
        edgePaint.strokeWidth = width * 2f / 108f
        if (selectedSwatch) canvas.drawRoundRect(rect, innerRadius, innerRadius, edgePaint)
        else canvas.drawOval(rect, edgePaint)
    }
}
