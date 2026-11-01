package com.ubermicrostudios.messagesenhanced.util

import kotlin.math.max

/**
 * Padding for edge-to-edge list and thread chrome.
 * The window is not shrunk for system bars, so each pane adds the insets it
 * actually covers. A tablet list owns the left cutout; the thread pane beside
 * it owns the top, the right, and the bottom. List top padding is the measured
 * overlay height (that height already includes the status bar), so the status
 * inset is not added a second time.
 */
object EdgeChrome {
    data class Pads(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun overlay(
        base: Pads,
        barsLeft: Int,
        barsTop: Int,
        barsRight: Int,
        insetEnd: Boolean,
    ): Pads = Pads(
        left = base.left + barsLeft,
        top = base.top + barsTop,
        right = base.right + if (insetEnd) barsRight else 0,
        bottom = base.bottom,
    )

    fun scrolling(
        base: Pads,
        chromeHeight: Int,
        barsLeft: Int,
        barsRight: Int,
        barsBottom: Int,
        ime: Int,
        insetEnd: Boolean,
    ): Pads = Pads(
        left = base.left + barsLeft,
        top = chromeHeight,
        right = base.right + if (insetEnd) barsRight else 0,
        bottom = base.bottom + max(barsBottom, ime),
    )

    fun threadHeader(
        base: Pads,
        barsLeft: Int,
        barsTop: Int,
        barsRight: Int,
        twoPane: Boolean,
    ): Pads = if (twoPane) {
        // The tablet app bar already clears the status bar above both panes.
        Pads(base.left, base.top, base.right + barsRight, base.bottom)
    } else {
        Pads(base.left + barsLeft, base.top + barsTop, base.right + barsRight, base.bottom)
    }

    fun threadDock(
        base: Pads,
        barsLeft: Int,
        barsRight: Int,
        barsBottom: Int,
        ime: Int,
        twoPane: Boolean,
    ): Pads {
        val bottom = base.bottom + max(barsBottom, ime)
        return if (twoPane) {
            Pads(base.left, base.top, base.right + barsRight, bottom)
        } else {
            Pads(base.left + barsLeft, base.top, base.right + barsRight, bottom)
        }
    }
}
