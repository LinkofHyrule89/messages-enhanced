package com.ubermicrostudios.messagesenhanced.ui.thread

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.SwipeMath
import com.ubermicrostudios.messagesenhanced.util.SwipeSession

/**
 * Left drag slides every row together and uncovers the time column.
 * Right drag on one row reveals the reply icon and, past the threshold,
 * calls [onReply]. Vertical movement keeps the list scrolling.
 * Distances come from [SwipeMath], scaled from dp to pixels.
 */
class ThreadGestures(
    private val recyclerView: RecyclerView,
    private val messageFor: (String) -> MessageDto?,
    private val onReply: (MessageDto) -> Unit,
) : RecyclerView.OnItemTouchListener {

    private val density = recyclerView.resources.displayMetrics.density
    private val ease = PathInterpolator(SwipeMath.EASE_X1, SwipeMath.EASE_Y1, SwipeMath.EASE_X2, SwipeMath.EASE_Y2)
    private val session = SwipeSession()
    private val accent = ContextCompat.getColor(recyclerView.context, R.color.accent)
    private val panel = ContextCompat.getColor(recyclerView.context, R.color.panel2)

    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var tracking = false
    private var stole = false
    private var row: View? = null
    private var downId: String? = null
    private var timeAnimator: ValueAnimator? = null
    private var replyAnimator: ValueAnimator? = null

    /** Pixel offsets applied to whatever is currently attached. */
    private var timePx = 0f
    private var replyPx = 0f
    private var replyKey: String? = null
    private var showIcon = false
    private var iconProgress = 0f
    private var iconArmed = false

    private val childWatch = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            sync(view)
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            if (timePx == 0f && replyKey == null) return
            resetShift(view)
        }
    }

    fun attach() {
        recyclerView.addOnItemTouchListener(this)
        recyclerView.addOnChildAttachStateChangeListener(childWatch)
    }

    fun detach() {
        recyclerView.removeOnItemTouchListener(this)
        recyclerView.removeOnChildAttachStateChangeListener(childWatch)
        cancelAnimators()
    }

    /** Re-apply an in-progress drag after a rebind. Idle binds are left alone. */
    fun sync(view: View) {
        if (timePx == 0f && replyKey == null) return
        val id = view.getTag(R.id.tag_message_id) as? String
        view.translationX = -timePx
        if (id != null && id == replyKey) {
            setShift(view, replyPx)
            if (showIcon) paintIcon(view, iconProgress, iconArmed)
        } else {
            setShift(view, 0f)
            hideIcon(view)
        }
    }

    fun onRecycled(view: View) {
        hideIcon(view)
        setShift(view, 0f)
    }

    /** True after a horizontal swipe has taken the pointer, until the next press. */
    fun horizontalClaimed(): Boolean = stole

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                    tracking = false
                    return false
                }
                val (x, y) = pointDp(e, 0)
                if (!session.down(x, y, windowWidthDp())) {
                    tracking = false
                    return false
                }
                snapToRest()
                pointerId = e.getPointerId(0)
                tracking = true
                stole = false
                row = rv.findChildViewUnder(e.x, e.y)
                downId = messageId(row)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                return claim(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pointerId) tracking = false
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> claim(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                    e.getPointerId(e.actionIndex) != pointerId
                ) {
                    return
                }
                finish(trigger = true)
            }
            MotionEvent.ACTION_CANCEL -> finish(trigger = false)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && session.mode == SwipeSession.Mode.NONE) {
            tracking = false
        }
    }

    private fun claim(e: MotionEvent): Boolean {
        val index = e.findPointerIndex(pointerId)
        if (index < 0) return false
        val (x, y) = pointDp(e, index)
        val move = session.move(x, y, canReply())
        return when (move) {
            SwipeSession.Move.Pending -> false
            SwipeSession.Move.Release -> {
                tracking = false
                false
            }
            is SwipeSession.Move.Reply -> {
                onClaimed()
                replyKey = downId
                showIcon = true
                iconProgress = move.progress
                iconArmed = move.armed
                replyPx = move.offset * density
                timePx = 0f
                val host = replyHost()
                row = host
                if (host != null) {
                    host.translationX = 0f
                    setShift(host, replyPx)
                    paintIcon(host, move.progress, move.armed)
                }
                if (move.justArmed) tick()
                true
            }
            is SwipeSession.Move.Time -> {
                onClaimed()
                showIcon = false
                replyKey = null
                replyPx = 0f
                timePx = move.offset * density
                applyTime(timePx)
                true
            }
        }
    }

    private fun onClaimed() {
        if (stole) return
        stole = true
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        recyclerView.stopScroll()
        recyclerView.itemAnimator?.endAnimations()
    }

    private fun finish(trigger: Boolean) {
        val reply = trigger && session.mode == SwipeSession.Mode.REPLY && session.armed
        val key = downId
        val host = replyHost()
        val message = key?.let(messageFor)
        val replyFrom = session.replyShift * density
        val timeFrom = session.timeShift * density
        session.clear()
        tracking = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        row = null
        downId = null
        showIcon = false
        spring(host, key, replyFrom, timeFrom)
        if (reply && message != null && SwipeMath.canReply(message)) onReply(message)
    }

    private fun spring(host: View?, key: String?, replyFrom: Float, timeFrom: Float) {
        if (host != null && replyFrom != 0f) {
            replyKey = key
            replyPx = replyFrom
            fadeIcon(host)
            val animator = ValueAnimator.ofFloat(replyFrom, 0f).apply {
                duration = SwipeMath.SPRING_MS
                interpolator = ease
            }
            replyAnimator = animator
            animator.addUpdateListener { value ->
                if (replyAnimator !== animator) return@addUpdateListener
                val px = value.animatedValue as Float
                replyPx = px
                if (host.parent != null) setShift(host, px)
            }
            animator.addListener(endListener {
                if (replyAnimator !== animator) return@endListener
                replyAnimator = null
                replyPx = 0f
                replyKey = null
                if (host.parent != null) setShift(host, 0f)
            })
            animator.start()
        } else {
            replyKey = null
            replyPx = 0f
            if (host != null) hideIcon(host)
        }
        if (timeFrom != 0f) {
            timePx = timeFrom
            val animator = ValueAnimator.ofFloat(timeFrom, 0f).apply {
                duration = SwipeMath.SPRING_MS
                interpolator = ease
            }
            timeAnimator = animator
            animator.addUpdateListener { value ->
                if (timeAnimator !== animator) return@addUpdateListener
                timePx = value.animatedValue as Float
                applyTime(timePx)
            }
            animator.addListener(endListener {
                if (timeAnimator !== animator) return@endListener
                timeAnimator = null
                timePx = 0f
                applyTime(0f)
            })
            animator.start()
        } else if (replyFrom == 0f) {
            timePx = 0f
        }
    }

    private fun applyTime(px: Float) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            child.translationX = -px
            if (replyKey == null) setShift(child, 0f)
        }
    }

    private fun snapToRest() {
        cancelAnimators()
        timePx = 0f
        replyPx = 0f
        replyKey = null
        showIcon = false
        iconProgress = 0f
        iconArmed = false
        for (i in 0 until recyclerView.childCount) {
            resetShift(recyclerView.getChildAt(i))
        }
    }

    /** Drop the animator field before cancel so its end callback cannot clear a newer drag. */
    private fun cancelAnimators() {
        val time = timeAnimator
        val reply = replyAnimator
        timeAnimator = null
        replyAnimator = null
        time?.cancel()
        reply?.cancel()
    }

    private fun canReply(): Boolean {
        val id = downId ?: return false
        val message = messageFor(id) ?: return false
        return SwipeMath.canReply(message)
    }

    private fun replyHost(): View? {
        val id = downId ?: replyKey ?: return null
        val current = row
        if (current != null && current.parent != null && messageId(current) == id) return current
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (messageId(child) == id) return child
        }
        return null
    }

    private fun pointDp(e: MotionEvent, index: Int): Pair<Float, Float> {
        val root = recyclerView.rootView
        val rootLoc = IntArray(2)
        val rvLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        recyclerView.getLocationOnScreen(rvLoc)
        val x = rvLoc[0] - rootLoc[0] + e.getX(index)
        val y = rvLoc[1] - rootLoc[1] + e.getY(index)
        return x / density to y / density
    }

    private fun windowWidthDp(): Float {
        val width = recyclerView.rootView.width
        val px = if (width > 0) width.toFloat() else recyclerView.resources.displayMetrics.widthPixels.toFloat()
        return px / density
    }

    private fun tick() {
        val vibrated = runCatching { vibrate(recyclerView.context) }.getOrDefault(false)
        if (!vibrated) {
            recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun vibrate(context: Context): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(VibrationEffect.createOneShot(SwipeMath.HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        return true
    }

    private fun paintIcon(row: View, progress: Float, armed: Boolean) {
        val icon = row.findViewById<ImageView>(R.id.reply_icon) ?: return
        icon.animate().cancel()
        icon.alpha = progress
        val scale = SwipeMath.replyIconScale(progress)
        icon.scaleX = scale
        icon.scaleY = scale
        icon.backgroundTintList = android.content.res.ColorStateList.valueOf(if (armed) accent else panel)
    }

    private fun fadeIcon(row: View) {
        val icon = row.findViewById<View>(R.id.reply_icon) ?: return
        icon.animate()
            .alpha(0f)
            .setDuration(SwipeMath.ICON_FADE_MS)
            .setInterpolator(ease)
            .withEndAction {
                icon.alpha = 0f
                icon.scaleX = SwipeMath.ICON_MIN_SCALE
                icon.scaleY = SwipeMath.ICON_MIN_SCALE
            }
            .start()
    }

    private fun hideIcon(view: View) {
        val icon = view.findViewById<View>(R.id.reply_icon) ?: return
        icon.animate().cancel()
        icon.alpha = 0f
        icon.scaleX = SwipeMath.ICON_MIN_SCALE
        icon.scaleY = SwipeMath.ICON_MIN_SCALE
    }

    private fun setShift(view: View, px: Float) {
        for (id in SHIFT_IDS) {
            view.findViewById<View>(id)?.translationX = px
        }
    }

    private fun resetShift(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        setShift(view, 0f)
        hideIcon(view)
    }

    private fun endListener(block: () -> Unit) = object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            block()
        }
    }

    private fun messageId(view: View?): String? = view?.getTag(R.id.tag_message_id) as? String

    companion object {
        private val SHIFT_IDS = intArrayOf(R.id.bubble, R.id.receipt, R.id.reveal_time)
    }
}
