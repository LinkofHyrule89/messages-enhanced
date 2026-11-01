package com.ubermicrostudios.messagesenhanced.util

/**
 * Corner radii of a chat bubble, in pixels. Solo bubbles are [RADIUS_DP] on
 * every corner. A run tucks the shared edge to [TAIL_DP], the same radii the
 * bubble background uses.
 */
object BubbleCorners {
    const val RADIUS_DP = 20f
    const val TAIL_DP = 4f

    data class Px(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float,
    ) {
        fun toArray(): FloatArray = floatArrayOf(
            topLeft, topLeft,
            topRight, topRight,
            bottomRight, bottomRight,
            bottomLeft, bottomLeft,
        )

        companion object {
            val ZERO = Px(0f, 0f, 0f, 0f)
        }
    }

    fun px(density: Float, fromMe: Boolean, pos: MessageRuns.Pos): Px {
        val radius = RADIUS_DP * density
        val tail = TAIL_DP * density
        var topLeft = radius
        var topRight = radius
        var bottomRight = radius
        var bottomLeft = radius
        fun tailTop() {
            if (fromMe) topRight = tail else topLeft = tail
        }
        fun tailBottom() {
            if (fromMe) bottomRight = tail else bottomLeft = tail
        }
        when (pos) {
            MessageRuns.Pos.SOLO -> Unit
            MessageRuns.Pos.FIRST -> tailBottom()
            MessageRuns.Pos.MID -> {
                tailTop()
                tailBottom()
            }
            MessageRuns.Pos.LAST -> tailTop()
        }
        return Px(topLeft, topRight, bottomRight, bottomLeft)
    }
}
