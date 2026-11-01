package com.ubermicrostudios.messagesenhanced.ui.thread

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import com.ubermicrostudios.messagesenhanced.util.ZoomMath
import kotlin.math.abs

/**
 * Fit-center photo with pinch zoom, pan, double-tap zoom, and swipe-down to close.
 * The dismiss drag moves the bitmap, not the view, so the finger stays on the hit target.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onDismiss: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null

    /** Fires when the photo crosses the fitted zoom. A zoomed photo must pan instead of paging. */
    var onZoomed: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            zoomedNotified = null
            publishZoom()
        }

    private var zoomedNotified: Boolean? = null

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private val mapped = RectF()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var velocity: VelocityTracker? = null

    private var dismissDy = 0f
    private var draggingDown = false
    private var closing = false
    private var scaleAnim: ValueAnimator? = null
    private var dragAnim: ValueAnimator? = null

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector.isQuickScaleEnabled = false
        gestureDetector.setIsLongpressEnabled(false)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        val previous = getDrawable()
        if (previous is Animatable && previous !== drawable) previous.stop()
        super.setImageDrawable(drawable)
        (drawable as? Animatable)?.start()
        updateBase()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? Animatable)?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBase()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closing || drawable == null) return drawable != null
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            draggingDown = false
            dragAnim?.cancel()
            scaleAnim?.cancel()
            // Fitted photos let the pager take horizontal swipes. Zoomed photos keep the gesture.
            if (isZoomed()) parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        velocity?.addMovement(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> cancelDismiss()
            MotionEvent.ACTION_UP -> finishDrag(cancel = false)
            MotionEvent.ACTION_CANCEL -> finishDrag(cancel = true)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        (drawable as? Animatable)?.stop()
        scaleAnim?.cancel()
        dragAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateBase() {
        scaleType = ScaleType.MATRIX
        val d = drawable
        if (d == null || width == 0 || height == 0) {
            baseMatrix.reset()
            suppMatrix.reset()
            dismissDy = 0f
            imageMatrix = Matrix()
            publishZoom()
            return
        }
        val (dw, dh) = drawableSize(d) ?: return
        val scale = ZoomMath.fitScale(width.toFloat(), height.toFloat(), dw, dh)
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((width - dw * scale) / 2f, (height - dh * scale) / 2f)
        suppMatrix.reset()
        dismissDy = 0f
        draggingDown = false
        applyMatrix()
    }

    /** Intrinsic size, then the drawable bounds, so an animated GIF can zoom like a bitmap. */
    private fun drawableSize(d: Drawable): Pair<Float, Float>? {
        var width = d.intrinsicWidth.toFloat()
        var height = d.intrinsicHeight.toFloat()
        if (width <= 0f || height <= 0f) {
            val bounds = d.copyBounds()
            width = bounds.width().toFloat()
            height = bounds.height().toFloat()
        }
        if (width <= 0f || height <= 0f) return null
        return width to height
    }

    private fun currentScale(): Float {
        suppMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun applyMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        if (dismissDy != 0f) drawMatrix.postTranslate(0f, dismissDy)
        imageMatrix = drawMatrix
        publishZoom()
    }

    fun isZoomed(): Boolean = currentScale() > ZoomMath.MIN_SCALE + 0.02f

    private fun publishZoom() {
        val zoomed = drawable != null && isZoomed()
        if (zoomedNotified == zoomed) return
        zoomedNotified = zoomed
        onZoomed?.invoke(zoomed)
    }

    private fun constrain() {
        val d = drawable ?: return
        val (dw, dh) = drawableSize(d) ?: return
        if (width == 0 || height == 0) return
        mapped.set(0f, 0f, dw, dh)
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        drawMatrix.mapRect(mapped)
        val (dx, dy) = ZoomMath.panCorrection(
            mapped.left,
            mapped.top,
            mapped.right,
            mapped.bottom,
            width.toFloat(),
            height.toFloat(),
        )
        if (abs(dx) > 0.5f || abs(dy) > 0.5f) suppMatrix.postTranslate(dx, dy)
    }

    private fun animateScale(target: Float, focusX: Float, focusY: Float) {
        scaleAnim?.cancel()
        val start = currentScale()
        if (abs(start - target) < 0.01f) return
        scaleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val desired = start + (target - start) * anim.animatedFraction
                val now = currentScale().takeIf { it > 0.01f } ?: 1f
                suppMatrix.postScale(desired / now, desired / now, focusX, focusY)
                constrain()
                applyMatrix()
            }
            start()
        }
    }

    private fun cancelDismiss() {
        if (dismissDy == 0f && !draggingDown) return
        draggingDown = false
        dismissDy = 0f
        applyMatrix()
        onDrag?.invoke(0f)
    }

    private fun finishDrag(cancel: Boolean) {
        val tracker = velocity
        velocity = null
        val vy = if (tracker == null) {
            0f
        } else {
            tracker.computeCurrentVelocity(1000)
            tracker.yVelocity.also { tracker.recycle() }
        }
        if (!draggingDown && dismissDy == 0f) return
        draggingDown = false
        val dismiss = !cancel && ZoomMath.shouldDismiss(dismissDy, height.toFloat(), vy)
        if (dismiss) animateOff() else animateBack()
    }

    private fun animateOff() {
        if (closing) return
        closing = true
        runDrag(dismissDy, height.toFloat()) {
            onDismiss?.invoke()
        }
    }

    private fun animateBack() {
        runDrag(dismissDy, 0f, onEnd = null)
    }

    private fun runDrag(from: Float, to: Float, onEnd: (() -> Unit)?) {
        dragAnim?.cancel()
        if (abs(from - to) < 0.5f) {
            dismissDy = to
            applyMatrix()
            onDrag?.invoke(dismissDy)
            onEnd?.invoke()
            return
        }
        dragAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                dismissDy = anim.animatedValue as Float
                applyMatrix()
                onDrag?.invoke(dismissDy)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelDismiss()
            scaleAnim?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return drawable != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentScale()
            if (current <= 0f) return false
            val next = ZoomMath.clampScale(current * detector.scaleFactor)
            val factor = next / current
            if (factor == 1f) return true
            suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrain()
            applyMatrix()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (drawable == null || scaleDetector.isInProgress) return false
            cancelDismiss()
            animateScale(ZoomMath.doubleTapTarget(currentScale()), e.x, e.y)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (drawable == null || scaleDetector.isInProgress || scaleAnim?.isRunning == true) return false
            if (isZoomed()) {
                parent?.requestDisallowInterceptTouchEvent(true)
                suppMatrix.postTranslate(-distanceX, -distanceY)
                constrain()
                applyMatrix()
                return true
            }
            if (!draggingDown) {
                if (-distanceY <= abs(distanceX)) return false
                draggingDown = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            dismissDy = (dismissDy - distanceY).coerceAtLeast(0f)
            applyMatrix()
            onDrag?.invoke(dismissDy)
            return true
        }
    }
}
