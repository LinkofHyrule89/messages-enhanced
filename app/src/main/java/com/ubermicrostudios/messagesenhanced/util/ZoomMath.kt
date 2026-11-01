package com.ubermicrostudios.messagesenhanced.util

import kotlin.math.min

/**
 * Fit, pinch, and swipe-down numbers for the full-screen photo.
 * Scale is relative to the fitted image: 1 fills the screen without cropping.
 * Translations are in view pixels.
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val DOUBLE_TAP_SCALE = 2.5f
    const val MAX_SCALE = 4f

    /** Fraction of the view height a downward drag must travel to close. */
    const val DISMISS_FRACTION = 0.22f

    /** Downward velocity (px/s) that closes a shorter drag. */
    const val DISMISS_VELOCITY = 1600f

    fun fitScale(viewW: Float, viewH: Float, imageW: Float, imageH: Float): Float {
        if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f) return 1f
        return min(viewW / imageW, viewH / imageH)
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Double-tap toggles between the fit and [DOUBLE_TAP_SCALE]. */
    fun doubleTapTarget(current: Float): Float =
        if (current < DOUBLE_TAP_SCALE - 0.05f) DOUBLE_TAP_SCALE else MIN_SCALE

    /**
     * Extra translation so the scaled image stays on screen.
     * When the image is smaller than the view on an axis, it stays centered.
     * [left]/[top]/[right]/[bottom] are the image edges in view pixels.
     */
    fun panCorrection(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        viewW: Float,
        viewH: Float,
    ): Pair<Float, Float> {
        val width = right - left
        val height = bottom - top
        val dx = if (width <= viewW) {
            (viewW - width) / 2f - left
        } else {
            when {
                left > 0f -> -left
                right < viewW -> viewW - right
                else -> 0f
            }
        }
        val dy = if (height <= viewH) {
            (viewH - height) / 2f - top
        } else {
            when {
                top > 0f -> -top
                bottom < viewH -> viewH - bottom
                else -> 0f
            }
        }
        return dx to dy
    }

    fun shouldDismiss(translationY: Float, viewHeight: Float, velocityY: Float): Boolean {
        if (translationY <= 0f || viewHeight <= 0f) return false
        if (translationY >= viewHeight * DISMISS_FRACTION) return true
        return velocityY >= DISMISS_VELOCITY && translationY >= viewHeight * 0.08f
    }
}
