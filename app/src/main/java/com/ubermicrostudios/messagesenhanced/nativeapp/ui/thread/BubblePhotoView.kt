package com.ubermicrostudios.messagesenhanced.nativeapp.ui.thread

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView

/**
 * Photo that fills the top of a bubble edge to edge, Google Messages style: it takes the
 * full available width (up to [maxPhotoWidth]) at the picture's own aspect ratio, never
 * cropped; very tall pictures shrink to [maxPhotoHeight]. The caption below is held to the
 * photo's width so the bubble never shows a frame around it.
 */
class BubblePhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    var photoMode = false
    var maxPhotoWidth = 0
    var maxPhotoHeight = 0
    var caption: TextView? = null

    /**
     * Source pixel size known before the bitmap is painted (message fields or
     * the URL cache). Used until the drawable itself has a size, so the first
     * measure matches the measure after decode.
     */
    var reserveWidth = 0
    var reserveHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!photoMode || maxPhotoWidth <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = if (mode == MeasureSpec.UNSPECIFIED) maxPhotoWidth else minOf(maxPhotoWidth, specW)
        val d = drawable
        val dw = d?.intrinsicWidth ?: -1
        val dh = d?.intrinsicHeight ?: -1
        val known = dw > 0 && dh > 0
        val iw = if (known) dw else reserveWidth
        val ih = if (known) dh else reserveHeight
        val placeholder = if (reserveWidth > 0 && reserveHeight > 0) 0 else minimumHeight
        val (w, h) = PhotoFit.fit(iw, ih, avail, maxPhotoHeight, placeholder)
        caption?.let { if (it.maxWidth != w) it.maxWidth = w }
        setMeasuredDimension(w, h)
    }
}

/** Pure sizing math, unit-tested. */
object PhotoFit {
    fun fit(iw: Int, ih: Int, maxW: Int, maxH: Int, placeholderH: Int): Pair<Int, Int> {
        if (iw <= 0 || ih <= 0) return maxW to maxOf(placeholderH, 1)
        var w = maxW
        var h = (w.toLong() * ih / iw).toInt()
        if (maxH in 1 until h) {
            h = maxH
            w = (h.toLong() * iw / ih).toInt().coerceAtLeast(1)
        }
        return w to h
    }
}


/** Intrinsic quote width: widest of name and one-line text, plus padding. */
object QuoteWidth {
    fun intrinsic(nameWidth: Float, textWidth: Float, padding: Int): Int =
        kotlin.math.ceil(maxOf(nameWidth, textWidth)).toInt() + padding
}
