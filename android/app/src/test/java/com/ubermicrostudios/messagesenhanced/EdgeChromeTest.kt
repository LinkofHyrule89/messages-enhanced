package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.EdgeChrome
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeChromeTest {
    private val barsLeft = 10
    private val barsTop = 40
    private val barsRight = 6
    private val barsBottom = 48

    @Test
    fun overlayClearsTheStatusBarAndOnlyTheSidesThisPaneOwns() {
        val base = EdgeChrome.Pads(0, 0, 0, 0)
        assertEquals(
            EdgeChrome.Pads(10, 40, 6, 0),
            EdgeChrome.overlay(base, barsLeft, barsTop, barsRight, insetEnd = true),
        )
        assertEquals(
            EdgeChrome.Pads(10, 40, 0, 0),
            EdgeChrome.overlay(base, barsLeft, barsTop, barsRight, insetEnd = false),
        )
    }

    @Test
    fun scrollingRestsBelowTheChromeAndAboveTheTallerOfNavAndKeyboard() {
        val base = EdgeChrome.Pads(1, 99, 2, 8)
        assertEquals(
            EdgeChrome.Pads(11, 120, 8, 56),
            EdgeChrome.scrolling(base, 120, barsLeft, barsRight, barsBottom, ime = 0, insetEnd = true),
        )
        assertEquals(
            EdgeChrome.Pads(11, 120, 8, 308),
            EdgeChrome.scrolling(base, 120, barsLeft, barsRight, barsBottom, ime = 300, insetEnd = true),
        )
        assertEquals(
            EdgeChrome.Pads(11, 120, 2, 56),
            EdgeChrome.scrolling(base, 120, barsLeft, barsRight, barsBottom, ime = 20, insetEnd = false),
        )
    }

    @Test
    fun phoneThreadPadsBothSidesAndTheTwoPaneThreadLeavesTheLeftInsetToTheList() {
        val header = EdgeChrome.Pads(4, 4, 4, 18)
        val dock = EdgeChrome.Pads(10, 8, 10, 10)
        assertEquals(
            EdgeChrome.Pads(14, 44, 10, 18),
            EdgeChrome.threadHeader(header, barsLeft, barsTop, barsRight, twoPane = false),
        )
        assertEquals(
            EdgeChrome.Pads(20, 8, 16, 58),
            EdgeChrome.threadDock(dock, barsLeft, barsRight, barsBottom, ime = 0, twoPane = false),
        )
        assertEquals(
            EdgeChrome.Pads(4, 4, 10, 18),
            EdgeChrome.threadHeader(header, barsLeft, barsTop, barsRight, twoPane = true),
        )
        assertEquals(
            EdgeChrome.Pads(10, 8, 16, 410),
            EdgeChrome.threadDock(dock, barsLeft, barsRight, barsBottom, ime = 400, twoPane = true),
        )
        assertEquals(
            EdgeChrome.Pads(10, 8, 16, 58),
            EdgeChrome.threadDock(dock, barsLeft, barsRight, barsBottom, ime = 20, twoPane = true),
        )
    }
}
