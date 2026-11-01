package com.ubermicrostudios.messagesenhanced.ui.thread

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/**
 * Composer version of [MentionChipSpan]: same pill look, but keeps the text's natural width so
 * the cursor and selection stay aligned. The pill bleeds into the surrounding non-breaking spaces.
 */
class ComposerMentionSpan(
    private val fill: Int,
    private val textColor: Int,
    private val density: Float,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = paint.measureText(text, start, end)
        val fm = paint.fontMetrics
        val bleed = 3f * density
        val rect = RectF(x - bleed, y + fm.ascent - 1f * density, x + w + bleed, y + fm.descent + 1f * density)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bg)
        val p = Paint(paint)
        p.color = textColor
        canvas.drawText(text, start, end, x, y.toFloat(), p)
    }
}
