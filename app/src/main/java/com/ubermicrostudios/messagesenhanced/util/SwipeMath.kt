package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import kotlin.math.abs

/**
 * Drag distances from the web app (`app.js` swipe block).
 *
 * `SWIPE_LOCK`..`TIME_MAX` are CSS pixels there. The pointer handler multiplies
 * movement by the page zoom before comparing, so the numbers live in layout
 * pixels. The Android thread feeds the same numbers in dp.
 *
 * Locking a direction rebases the origin (`x0 = clientX`) so the row does not
 * jump by the travel already spent deciding. Rubber-band curves match the web:
 * reply `dx < REPLY_AT ? dx : REPLY_AT + (dx - REPLY_AT) * 0.35`, capped at
 * [REPLY_MAX]; time uses a knee at `0.8 * TIME_MAX` then `* 0.3`, capped at
 * [TIME_MAX]. Spring is CSS `ease` (0.22s). Arming vibrates for 10ms.
 */
object SwipeMath {
    const val SWIPE_LOCK = 18f
    const val SWIPE_EDGE = 28f
    const val REPLY_AT = 96f
    const val REPLY_MAX = 170f
    const val TIME_MAX = 200f

    /** `.swipe-time-label` sits at `left: calc(100% + 70px)`. */
    const val TIME_LABEL_GAP = 70f

    const val HORIZONTAL_BIAS = 1.6f
    const val REPLY_RUBBER = 0.35f
    const val TIME_KNEE = 0.8f
    const val TIME_RUBBER = 0.3f

    const val SPRING_MS = 220L
    const val ICON_FADE_MS = 150L
    const val HAPTIC_MS = 10L

    const val ICON_MIN_SCALE = 0.6f
    const val ICON_SCALE_SPAN = 0.4f

    /** CSS `ease` is `cubic-bezier(0.25, 0.1, 0.25, 1)`. */
    const val EASE_X1 = 0.25f
    const val EASE_Y1 = 0.1f
    const val EASE_X2 = 0.25f
    const val EASE_Y2 = 1f

    enum class Decision { PENDING, VERTICAL, REPLY, TIME }

    /**
     * Direction while the gesture is still undecided. Vertical wins when
     * `|dy| > SWIPE_LOCK` and `|dy| > |dx|`. Horizontal locks only when
     * `|dx| >= SWIPE_LOCK` and `|dx| >= 1.6 * |dy|`. Right is reply, left is
     * the shared time column.
     */
    fun decide(dx: Float, dy: Float): Decision {
        val adx = abs(dx)
        val ady = abs(dy)
        if (ady > SWIPE_LOCK && ady > adx) return Decision.VERTICAL
        if (adx < SWIPE_LOCK || adx < HORIZONTAL_BIAS * ady) return Decision.PENDING
        return if (dx > 0f) Decision.REPLY else Decision.TIME
    }

    /** Right-drag distance after the lock point. Negative travel stays at 0. */
    fun replyOffset(dx: Float): Float {
        val raw = if (dx < REPLY_AT) dx else REPLY_AT + (dx - REPLY_AT) * REPLY_RUBBER
        return raw.coerceIn(0f, REPLY_MAX)
    }

    fun replyProgress(offset: Float): Float = (offset / REPLY_AT).coerceAtMost(1f).coerceAtLeast(0f)

    fun replyArmed(offset: Float): Boolean = offset >= REPLY_AT

    fun replyIconScale(progress: Float): Float = ICON_MIN_SCALE + ICON_SCALE_SPAN * progress

    /** Left-drag distance after the lock point. [dx] is negative when moving left. */
    fun timeOffset(dx: Float): Float {
        val left = -dx
        val knee = TIME_MAX * TIME_KNEE
        val raw = if (left < knee) left else knee + (left - knee) * TIME_RUBBER
        return raw.coerceIn(0f, TIME_MAX)
    }

    /** True inside the screen-edge bands the web leaves for the system back gesture. */
    fun blockedByEdge(x: Float, width: Float): Boolean = x < SWIPE_EDGE || x > width - SWIPE_EDGE

    /**
     * Optimistic rows and in-flight placeholders cannot be replied to.
     * Same rule as the web: `m.local || isSendPlaceholder(m)`.
     */
    fun canReply(message: MessageDto): Boolean {
        if (message.messageId.startsWith("local-")) return false
        if (!message.isFromMe) return true
        return !isSendPlaceholder(message)
    }

    fun isSendPlaceholder(message: MessageDto): Boolean {
        if (!message.isFromMe) return false
        val id = message.messageId
        if (!id.startsWith("tm-") && !id.startsWith("tmp_")) return false
        val status = message.status.uppercase()
        if (status.contains("FAIL") || status.contains("CANCEL")) return false
        return status.contains("SENDING") ||
            status.contains("YET_TO_SEND") ||
            status.contains("VALIDATING") ||
            status.contains("AWAITING_RETRY")
    }
}

/**
 * Pointer session for one drag. Coordinates use the same unit as [SwipeMath]
 * (dp on Android). [down] rejects edge starts. [move] returns whether the
 * list should keep scrolling.
 */
class SwipeSession {
    enum class Mode { NONE, REPLY, TIME }

    sealed interface Move {
        data object Pending : Move
        data object Release : Move
        data class Reply(
            val offset: Float,
            val progress: Float,
            val armed: Boolean,
            val justArmed: Boolean,
        ) : Move
        data class Time(val offset: Float) : Move
    }

    var mode: Mode = Mode.NONE
        private set
    var armed: Boolean = false
        private set
    var replyShift: Float = 0f
        private set
    var timeShift: Float = 0f
        private set

    private var x0 = 0f
    private var y0 = 0f
    private var dead = false

    /** @return false when the pointer starts in the system-gesture edge. */
    fun down(x: Float, y: Float, width: Float): Boolean {
        mode = Mode.NONE
        armed = false
        replyShift = 0f
        timeShift = 0f
        dead = false
        if (SwipeMath.blockedByEdge(x, width)) {
            dead = true
            return false
        }
        x0 = x
        y0 = y
        return true
    }

    fun move(x: Float, y: Float, canReply: Boolean): Move {
        if (dead) return Move.Release
        if (mode == Mode.NONE) {
            when (SwipeMath.decide(x - x0, y - y0)) {
                SwipeMath.Decision.PENDING -> return Move.Pending
                SwipeMath.Decision.VERTICAL -> {
                    dead = true
                    return Move.Release
                }
                SwipeMath.Decision.REPLY -> {
                    if (!canReply) {
                        dead = true
                        return Move.Release
                    }
                    mode = Mode.REPLY
                    x0 = x
                    y0 = y
                }
                SwipeMath.Decision.TIME -> {
                    mode = Mode.TIME
                    x0 = x
                    y0 = y
                }
            }
        }
        return if (mode == Mode.REPLY) replyMove(x) else timeMove(x)
    }

    fun clear() {
        dead = true
        mode = Mode.NONE
        armed = false
        replyShift = 0f
        timeShift = 0f
    }

    private fun replyMove(x: Float): Move {
        val offset = SwipeMath.replyOffset(x - x0)
        val now = SwipeMath.replyArmed(offset)
        val just = now && !armed
        armed = now
        replyShift = offset
        return Move.Reply(offset, SwipeMath.replyProgress(offset), now, just)
    }

    private fun timeMove(x: Float): Move {
        val offset = SwipeMath.timeOffset(x - x0)
        timeShift = offset
        return Move.Time(offset)
    }
}
