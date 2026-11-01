package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import kotlin.math.hypot

/**
 * Long-press on a bubble opens the message sheet. Movement past the web's
 * 12px slop, or a horizontal swipe that already claimed the pointer, cancels
 * it. While messages are selected, a tap toggles the row instead.
 */
class MessagePress(
    private val recyclerView: RecyclerView,
    private val messageFor: (String) -> MessageDto?,
    private val claimed: () -> Boolean,
    private val selecting: () -> Boolean,
    private val sheetOpen: () -> Boolean,
    private val onToggle: (String) -> Unit,
    private val onTap: (MessageDto) -> Unit,
    private val onLongPress: (View, MessageDto) -> Unit,
) : RecyclerView.OnItemTouchListener {

    private val density = recyclerView.resources.displayMetrics.density
    private val slop = MessageActions.LONG_PRESS_SLOP_DP * density
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var bubble: View? = null
    private var message: MessageDto? = null
    private var selectDownX = 0f
    private var selectDownY = 0f
    private var selectMoved = false
    /** Long-press fired: swallow the rest of the gesture so the link card's tap doesn't open the link. */
    private var fired = false

    private val fire = Runnable {
        if (!tracking || claimed() || selecting() || sheetOpen()) return@Runnable
        val target = bubble ?: return@Runnable
        val item = message ?: return@Runnable
        if (!MessageActions.canOpenMenu(item, selecting = false)) return@Runnable
        tracking = false
        fired = true
        buzz()
        onLongPress(target, item)
    }

    fun attach() {
        recyclerView.addOnItemTouchListener(this)
    }

    fun detach() {
        cancel()
        recyclerView.removeOnItemTouchListener(this)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        if (selecting()) return selectionTap(event)
        if (fired) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
            return true
        }
        if (sheetOpen()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(event)
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (claimed() || moved(event)) cancel()
            }
            MotionEvent.ACTION_UP -> {
                if (tracking && !claimed() && !moved(event) && !excluded(event)) {
                    val item = message
                    cancel()
                    if (item != null && MessageActions.canShowDetail(item, selecting = false)) onTap(item)
                } else {
                    cancel()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
    }

    /** A short tap toggles the row. A drag keeps scrolling. */
    private fun selectionTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectDownX = event.x
                selectDownY = event.y
                selectMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - selectDownX, event.y - selectDownY) > slop) selectMoved = true
            }
            MotionEvent.ACTION_UP -> {
                if (!selectMoved) {
                    toggleAt(event)
                    return true
                }
            }
        }
        return false
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) cancel()
    }

    private fun start(event: MotionEvent) {
        cancel()
        fired = false
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return
        // Link previews open on tap but still take a long-press (like the phone app).
        if (excluded(event, LONG_PRESS_EXCLUDE)) return
        val hit = bubbleAt(event) ?: return
        val item = hit.second
        if (!MessageActions.canOpenMenu(item, selecting = false)) return
        tracking = true
        downX = event.x
        downY = event.y
        bubble = hit.first
        message = item
        recyclerView.postDelayed(fire, MessageActions.LONG_PRESS_MS)
    }

    private fun cancel() {
        tracking = false
        bubble = null
        message = null
        recyclerView.removeCallbacks(fire)
    }

    private fun moved(event: MotionEvent): Boolean = hypot(event.x - downX, event.y - downY) > slop

    private fun toggleAt(event: MotionEvent) {
        val row = recyclerView.findChildViewUnder(event.x, event.y) ?: return
        val id = row.getTag(R.id.tag_message_id) as? String ?: return
        onToggle(id)
    }

    private fun excluded(event: MotionEvent, ids: IntArray = EXCLUDE): Boolean {
        val row = recyclerView.findChildViewUnder(event.x, event.y) ?: return false
        for (id in ids) {
            val child = row.findViewById<View>(id) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (hit(child, event)) return true
        }
        return false
    }

    private fun hit(view: View, event: MotionEvent): Boolean {
        val rv = IntArray(2)
        val box = IntArray(2)
        recyclerView.getLocationOnScreen(rv)
        view.getLocationOnScreen(box)
        val x = rv[0] + event.x
        val y = rv[1] + event.y
        return x >= box[0] && y >= box[1] && x < box[0] + view.width && y < box[1] + view.height
    }

    private fun bubbleAt(event: MotionEvent): Pair<View, MessageDto>? {
        val row = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
        val id = row.getTag(R.id.tag_message_id) as? String ?: return null
        val item = messageFor(id) ?: return null
        val bubble = row.findViewById<View>(R.id.bubble) ?: return null
        val rv = IntArray(2)
        val box = IntArray(2)
        recyclerView.getLocationOnScreen(rv)
        bubble.getLocationOnScreen(box)
        val x = rv[0] + event.x
        val y = rv[1] + event.y
        if (x < box[0] || y < box[1] || x >= box[0] + bubble.width || y >= box[1] + bubble.height) return null
        return bubble to item
    }

    private fun buzz() {
        val vibrated = runCatching { vibrate(recyclerView.context) }.getOrDefault(false)
        if (!vibrated) recyclerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun vibrate(context: Context): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(
            VibrationEffect.createOneShot(MessageActions.LONG_PRESS_HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE),
        )
        return true
    }

    companion object {
        private val EXCLUDE = intArrayOf(
            R.id.image,
            R.id.link_card,
            R.id.reply_quote,
            R.id.reactions,
        )
        private val LONG_PRESS_EXCLUDE = intArrayOf(
            R.id.image,
            R.id.reply_quote,
            R.id.reactions,
        )
    }
}
