package com.ubermicrostudios.messagesenhanced.ui.thread

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/** Rounded pill around an "@Name" mention: subtle lighter fill, accent text. */
class MentionChipSpan(
    private val fill: Int,
    private val textColor: Int,
    private val density: Float,
) : ReplacementSpan() {
    private val padH get() = 6f * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val bold = Paint(paint).apply { isFakeBoldText = true }
        if (fm != null) paint.getFontMetricsInt(fm)
        return (bold.measureText(text, start, end) + padH * 2).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = Paint(paint)
        p.isFakeBoldText = true
        val w = p.measureText(text, start, end)
        val fm = p.fontMetrics
        val rect = RectF(x, y + fm.ascent - 1f * density, x + w + padH * 2, y + fm.descent + 1f * density)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bg)
        p.color = textColor
        canvas.drawText(text, start, end, x + padH, y.toFloat(), p)
    }
}
