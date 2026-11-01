package com.ubermicrostudios.messagesenhanced.ui.thread

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.databinding.FragmentThreadBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemSheetActionBinding
import com.ubermicrostudios.messagesenhanced.util.MessageActions

/**
 * Google Messages-style long-press sheet: a blurred copy of the thread, the
 * pressed bubble kept sharp, a reaction pill, and the action menu.
 */
class MessageSheet(
    private val binding: FragmentThreadBinding,
    private val host: Host,
    private val starred: (String) -> Boolean,
    private val onVisibility: () -> Unit = {},
) {
    interface Host {
        fun onReply(message: MessageDto)
        fun onForward(message: MessageDto)
        fun onCopy(text: String)
        fun onStar(message: MessageDto)
        fun onDelete(message: MessageDto)
        fun onSelect(message: MessageDto)
        fun onInfo(message: MessageDto)
        fun onDetails(message: MessageDto)
        fun onReact(message: MessageDto, emoji: String)
    }

    private var current: MessageDto? = null
    private var openedAt = 0L
    private var openGen = 0
    private var blurBitmap: Bitmap? = null
    private var highlightBitmap: Bitmap? = null

    fun isOpen(): Boolean = binding.messageSheet.isVisible

    fun open(bubble: View, message: MessageDto) {
        if (bubble.width <= 0 || bubble.height <= 0) return
        val gen = ++openGen
        current = message
        replaceHighlight(runCatching { snapshot(bubble) }.getOrNull())
        buildReactions()
        buildMenu(message)
        binding.sheetDim.setOnClickListener {
            if (ready()) close()
        }
        // Copy the window before the sheet is drawn, then show it. A hardware
        // bitmap (a photo in a bubble) cannot be drawn into a software canvas.
        captureWindow(binding.threadRoot) { blur ->
            if (gen != openGen) {
                blur?.recycle()
                return@captureWindow
            }
            replaceBlur(blur)
            binding.sheetBlur.isVisible = blur != null
            openedAt = SystemClock.uptimeMillis()
            binding.messageSheet.isVisible = true
            binding.messageSheet.post {
                if (gen == openGen && current?.messageId == message.messageId) place(bubble, message.isFromMe)
            }
            onVisibility()
        }
    }

    fun close() {
        if (!binding.messageSheet.isVisible && current == null) return
        openGen++
        binding.messageSheet.isVisible = false
        current = null
        binding.sheetHighlight.setImageDrawable(null)
        binding.sheetBlur.setImageDrawable(null)
        highlightBitmap?.recycle()
        highlightBitmap = null
        blurBitmap?.recycle()
        blurBitmap = null
        onVisibility()
    }

    fun destroy() {
        close()
    }

    private fun ready(): Boolean = SystemClock.uptimeMillis() - openedAt >= 300L

    private fun buildReactions() {
        val row = binding.sheetReactions
        row.removeAllViews()
        row.isVisible = true
        val density = row.resources.displayMetrics.density
        val size = (40 * density).toInt()
        for (emoji in MessageActions.QUICK_REACTIONS) {
            val button = TextView(row.context).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                contentDescription = "React $emoji"
                setOnClickListener {
                    val message = current ?: return@setOnClickListener
                    if (!ready()) return@setOnClickListener
                    close()
                    host.onReact(message, emoji)
                }
            }
            row.addView(button)
        }
        val more = ImageView(row.context).apply {
            setImageResource(R.drawable.ic_add_reaction)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE3E3E3.toInt())
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = ViewGroup.MarginLayoutParams(size, size)
            contentDescription = row.context.getString(R.string.more_reactions)
            setOnClickListener {
                val message = current ?: return@setOnClickListener
                if (!ready()) return@setOnClickListener
                val context = row.context
                close()
                EmojiPicker.show(context) { emoji -> host.onReact(message, emoji) }
            }
        }
        row.addView(more)
    }

    private fun buildMenu(message: MessageDto) {
        val menu = binding.sheetMenu
        menu.removeAllViews()
        val sheet = MessageActions.sheetMenu(message, starred(message.messageId))
        val inflater = LayoutInflater.from(menu.context)
        fun add(icon: Int, label: String, action: (MessageDto) -> Unit) {
            val row = ItemSheetActionBinding.inflate(inflater, menu, false)
            row.icon.setImageResource(icon)
            row.icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFE3E3E3.toInt())
            row.label.text = label
            row.root.setOnClickListener {
                val currentMessage = current ?: return@setOnClickListener
                if (!ready()) return@setOnClickListener
                close()
                action(currentMessage)
            }
            menu.addView(row.root)
        }
        if (sheet.showReply) add(R.drawable.ic_reply, "Reply") { host.onReply(it) }
        if (sheet.showForward) add(R.drawable.ic_forward, "Forward") { host.onForward(it) }
        if (sheet.showCopyUrl) add(R.drawable.ic_link, "Copy URL") { host.onCopy(sheet.copyUrl.orEmpty()) }
        if (sheet.showCopy) add(R.drawable.ic_copy, "Copy") { host.onCopy(it.body.trim()) }
        add(if (sheet.starLabel == "Unstar") R.drawable.ic_star_filled else R.drawable.ic_star_outline, sheet.starLabel) {
            host.onStar(it)
        }
        if (sheet.showDelete) add(R.drawable.ic_delete, "Delete") { host.onDelete(it) }
        if (sheet.showSelect) add(R.drawable.ic_select, "Select more") { host.onSelect(it) }
        if (sheet.showInfo) add(R.drawable.ic_info_outline, "Info") { host.onInfo(it) }
        add(R.drawable.ic_info, "Details") { host.onDetails(it) }
    }

    private fun place(bubble: View, mine: Boolean) {
        val root = binding.threadRoot
        if (root.width <= 0 || root.height <= 0) return
        val density = root.resources.displayMetrics.density
        val rootLoc = IntArray(2)
        val bubbleLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        bubble.getLocationOnScreen(bubbleLoc)
        val bubbleBox = MessageActions.Box(
            left = (bubbleLoc[0] - rootLoc[0]).toFloat(),
            top = (bubbleLoc[1] - rootLoc[1]).toFloat(),
            width = bubble.width.toFloat(),
            height = bubble.height.toFloat(),
        )
        val widthSpec = View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        binding.sheetReactions.measure(widthSpec, heightSpec)
        binding.sheetMenu.measure(widthSpec, heightSpec)
        val placed = MessageActions.placeSheet(
            bubble = bubbleBox,
            mine = mine,
            viewWidth = root.width.toFloat(),
            viewHeight = root.height.toFloat(),
            reactWidth = binding.sheetReactions.measuredWidth.toFloat(),
            reactHeight = binding.sheetReactions.measuredHeight.toFloat(),
            reactHidden = !binding.sheetReactions.isVisible,
            menuWidth = binding.sheetMenu.measuredWidth.toFloat(),
            menuHeight = binding.sheetMenu.measuredHeight.toFloat(),
            pad = MessageActions.SHEET_PAD * density,
            gap = MessageActions.SHEET_GAP * density,
            minClone = MessageActions.SHEET_MIN_CLONE * density,
        )
        move(binding.sheetReactions, placed.react, forceHeight = false)
        move(binding.sheetHighlight, placed.clone, forceHeight = true)
        move(binding.sheetMenu, placed.menu, forceHeight = false)
    }

    private fun move(view: View, box: MessageActions.Box, forceHeight: Boolean) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.START or Gravity.TOP
        params.leftMargin = box.left.toInt()
        params.topMargin = box.top.toInt()
        params.marginStart = box.left.toInt()
        params.width = box.width.toInt().coerceAtLeast(1)
        params.height = if (forceHeight) box.height.toInt().coerceAtLeast(1) else ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = params
    }

    private fun snapshot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun replaceHighlight(bitmap: Bitmap?) {
        highlightBitmap?.recycle()
        highlightBitmap = bitmap
        binding.sheetHighlight.setImageBitmap(bitmap)
        binding.sheetHighlight.scaleType = ImageView.ScaleType.MATRIX
    }

    private fun replaceBlur(bitmap: Bitmap?) {
        blurBitmap?.recycle()
        blurBitmap = bitmap
        binding.sheetBlur.setImageBitmap(bitmap)
    }

    private fun captureWindow(root: View, done: (Bitmap?) -> Unit) {
        val activity = root.context as? Activity
        val window = activity?.window
        val width = root.width
        val height = root.height
        if (window == null || width < 8 || height < 8) {
            done(null)
            return
        }
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    done(null)
                } else {
                    val blurred = runCatching { shrinkBlur(bitmap) }.getOrNull()
                    if (blurred !== bitmap) bitmap.recycle()
                    done(blurred)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) {
            bitmap.recycle()
            done(null)
        }
    }

    private fun shrinkBlur(source: Bitmap): Bitmap {
        val sampleWidth = (source.width / 8).coerceAtLeast(1)
        val sampleHeight = (source.height / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, sampleWidth, sampleHeight, true)
        val blurred = boxBlur(small, 2)
        if (blurred !== small) small.recycle()
        val full = Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
        if (full !== blurred) blurred.recycle()
        return full
    }

    /** Two-pass box blur. [radius] is in source pixels of the already-shrunk bitmap. */
    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val horizontal = IntArray(pixels.size)
        val div = radius * 2 + 1
        for (y in 0 until height) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (k in -radius..radius) {
                val color = pixels[y * width + k.coerceIn(0, width - 1)]
                alpha += color ushr 24 and 0xFF
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
            }
            for (x in 0 until width) {
                horizontal[y * width + x] =
                    (alpha / div shl 24) or (red / div shl 16) or (green / div shl 8) or (blue / div)
                val remove = pixels[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = pixels[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                red += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                green += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                blue += (add and 0xFF) - (remove and 0xFF)
            }
        }
        val vertical = IntArray(pixels.size)
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (k in -radius..radius) {
                val color = horizontal[k.coerceIn(0, height - 1) * width + x]
                alpha += color ushr 24 and 0xFF
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
            }
            for (y in 0 until height) {
                vertical[y * width + x] =
                    (alpha / div shl 24) or (red / div shl 16) or (green / div shl 8) or (blue / div)
                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                red += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                green += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                blue += (add and 0xFF) - (remove and 0xFF)
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(vertical, 0, width, 0, 0, width, height)
        return out
    }
}
