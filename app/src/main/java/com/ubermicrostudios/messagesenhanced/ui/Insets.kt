package com.ubermicrostudios.messagesenhanced.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ubermicrostudios.messagesenhanced.util.EdgeChrome
import kotlin.math.max

object Insets {
    fun enable(activity: ComponentActivity) {
        // Dark transparent styles turn off the system contrast scrim, so the
        // status bar and the gesture area stay clear on API 29 and above.
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }

    /**
     * Pads [root] for the status bar and the navigation/gesture bar.
     * When [includeIme] is true the bottom padding also clears the keyboard.
     * Login, Settings, and the chat theme screen use this. The conversation
     * list and the thread pad their own floating chrome instead, so content
     * can draw under the bars.
     */
    fun apply(root: View, includeIme: Boolean) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = if (includeIme) max(bars.bottom, ime) else bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * [chrome] floats over a full-bleed list. Its background should be a scrim,
     * and each scrolling view needs `clipToPadding = false` so rows draw under
     * that scrim and under the gesture area. Top padding is the chrome height.
     * Bottom padding is the taller of the navigation bar and the keyboard, so
     * the last row can rest in view.
     * [insetEnd] is false for the tablet list: the thread pane owns the right inset.
     * Insets are not consumed.
     */
    /**
     * Tablet shell: a full-width app bar owns the status bar and both side
     * cutouts. The list chrome (search / error) overlays only the list pane
     * and does not add the status inset a second time.
     */
    fun applyTabletShell(
        root: View,
        appBar: View,
        chrome: View,
        scrolling: List<View>,
        fab: View? = null,
        onChromeHeight: (Int) -> Unit,
    ) {
        val appBase = padsOf(appBar)
        val appBarHeight = appBar.layoutParams.height.takeIf { it > 0 } ?: appBar.resources.getDimensionPixelSize(com.ubermicrostudios.messagesenhanced.R.dimen.app_bar_height)
        val chromeBase = padsOf(chrome)
        val bases = scrolling.map { padsOf(it) }
        val fabBase = fab?.let { marginsOf(it) }
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var ime = 0
        fun paint() {
            val extra = fabSpace(fab)
            scrolling.forEachIndexed { index, view ->
                val pad = EdgeChrome.scrolling(
                    base = bases[index],
                    chromeHeight = chrome.height,
                    barsLeft = left,
                    barsRight = right,
                    barsBottom = bottom,
                    ime = ime,
                    insetEnd = false,
                )
                view.setPadding(pad.left, pad.top, pad.right, pad.bottom + extra)
            }
            if (fab != null && fabBase != null) placeFab(fab, fabBase, left, right, bottom, ime, insetEnd = false)
            onChromeHeight(chrome.height)
        }
        chrome.addOnLayoutChangeListener { _, l, t, r, b, oldLeft, oldTop, oldRight, oldBottom ->
            if (l != oldLeft || t != oldTop || r != oldRight || b != oldBottom) paint()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
            ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val barPad = EdgeChrome.overlay(appBase, left, top, right, insetEnd = true)
            appBar.setPadding(barPad.left, barPad.top, barPad.right, barPad.bottom)
            // The bar keeps its full height below the status bar instead of squeezing into it.
            appBar.layoutParams = appBar.layoutParams.apply { height = appBarHeight + barPad.top }
            val chromePad = EdgeChrome.Pads(
                chromeBase.left + left,
                chromeBase.top,
                chromeBase.right,
                chromeBase.bottom,
            )
            chrome.setPadding(chromePad.left, chromePad.top, chromePad.right, chromePad.bottom)
            paint()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun applyScrollingChrome(
        root: View,
        chrome: View,
        scrolling: List<View>,
        insetEnd: Boolean,
        fab: View? = null,
        onChromeHeight: (Int) -> Unit,
    ) {
        val chromeBase = padsOf(chrome)
        val bases = scrolling.map { padsOf(it) }
        val fabBase = fab?.let { marginsOf(it) }
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var ime = 0
        fun paint() {
            val extra = fabSpace(fab)
            scrolling.forEachIndexed { index, view ->
                val pad = EdgeChrome.scrolling(
                    base = bases[index],
                    chromeHeight = chrome.height,
                    barsLeft = left,
                    barsRight = right,
                    barsBottom = bottom,
                    ime = ime,
                    insetEnd = insetEnd,
                )
                view.setPadding(pad.left, pad.top, pad.right, pad.bottom + extra)
            }
            if (fab != null && fabBase != null) placeFab(fab, fabBase, left, right, bottom, ime, insetEnd)
            onChromeHeight(chrome.height)
        }
        chrome.addOnLayoutChangeListener { _, l, t, r, b, oldLeft, oldTop, oldRight, oldBottom ->
            if (l != oldLeft || t != oldTop || r != oldRight || b != oldBottom) paint()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
            ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val pad = EdgeChrome.overlay(chromeBase, left, top, right, insetEnd)
            chrome.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            paint()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Thread chrome floats over a full-bleed wallpaper.
     * Phones pad the header for the status bar and both sides, and the composer
     * for the navigation bar or the keyboard, whichever is taller.
     * On a tablet the list pane owns the left inset. This pane still clears
     * the status bar, the right cutout, and the gesture area, because the
     * parent no longer pads the window.
     */
    fun applyThreadChrome(root: View, header: View, dock: View, twoPane: Boolean, onChanged: () -> Unit) {
        val headerBase = padsOf(header)
        val dockBase = padsOf(dock)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val headerPad = EdgeChrome.threadHeader(headerBase, bars.left, bars.top, bars.right, twoPane)
            val dockPad = EdgeChrome.threadDock(dockBase, bars.left, bars.right, bars.bottom, ime, twoPane)
            header.setPadding(headerPad.left, headerPad.top, headerPad.right, headerPad.bottom)
            dock.setPadding(dockPad.left, dockPad.top, dockPad.right, dockPad.bottom)
            onChanged()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun padsOf(view: View) = EdgeChrome.Pads(
        view.paddingLeft,
        view.paddingTop,
        view.paddingRight,
        view.paddingBottom,
    )

    private fun marginsOf(view: View): EdgeChrome.Pads {
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        return EdgeChrome.Pads(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
    }

    private fun fabSpace(fab: View?): Int {
        if (fab == null) return 0
        return (72 * fab.resources.displayMetrics.density).toInt()
    }

    private fun placeFab(
        fab: View,
        base: EdgeChrome.Pads,
        barsLeft: Int,
        barsRight: Int,
        barsBottom: Int,
        ime: Int,
        insetEnd: Boolean,
    ) {
        val lp = fab.layoutParams as ViewGroup.MarginLayoutParams
        lp.bottomMargin = base.bottom + max(barsBottom, ime)
        lp.marginEnd = base.right + if (insetEnd) barsRight else 0
        lp.marginStart = base.left + barsLeft
        fab.layoutParams = lp
    }
}
