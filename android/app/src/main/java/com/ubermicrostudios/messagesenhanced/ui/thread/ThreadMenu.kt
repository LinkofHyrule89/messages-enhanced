package com.ubermicrostudios.messagesenhanced.ui.thread

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.databinding.ItemThreadMenuRowBinding
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay

object ThreadMenu {
    /** Error red used for Move to trash in this menu and its confirm button. */
    const val DANGER = 0xFFFF8589.toInt()

    fun show(anchor: View, state: ThreadUiState, actions: Actions) {
        val context = anchor.context
        showPopup(anchor, state.title.ifBlank { context.getString(R.string.thread_title) }) {
            val sms = smsBacked(state.sourcePlatform)
            val muted = state.notificationMode == "muted"
            val localPin = state.localPinnedAtMs > 0L
            row(R.drawable.ic_palette, context.getString(R.string.menu_chat_theme)) { actions.theme() }
            row(
                R.drawable.ic_bell,
                context.getString(if (muted) R.string.menu_unmute else R.string.menu_mute),
            ) { actions.mute() }
            row(
                R.drawable.ic_info,
                context.getString(if (state.isGroup) R.string.menu_group_details else R.string.menu_contact_details),
            ) { actions.details() }
            if (sms) {
                row(R.drawable.ic_refresh, context.getString(R.string.menu_refresh), context.getString(R.string.menu_refresh_note)) {
                    actions.refresh()
                }
            }
            if (!state.googlePinned) {
                val note = if (localPin) "" else context.getString(R.string.menu_pin_note)
                val label = context.getString(if (localPin) R.string.unpin_conversation else R.string.pin_conversation)
                row(R.drawable.ic_pin, label, note) { actions.pin() }
            }
            if (sms) {
                row(R.drawable.ic_archive, context.getString(R.string.menu_archive)) { actions.archive() }
                row(R.drawable.ic_trash, context.getString(R.string.menu_trash), danger = true) { actions.trash() }
                if (!state.isGroup) {
                    row(R.drawable.ic_block, context.getString(R.string.menu_block), danger = true) { actions.block(false) }
                    row(R.drawable.ic_report, context.getString(R.string.menu_block_report), danger = true) { actions.block(true) }
                }
            }
            row(R.drawable.ic_settings, context.getString(R.string.settings)) { actions.settings() }
        }
    }

    /**
     * Rounded dark Material You popup, anchored to [anchor]. Shared with the
     * conversation-list long-press menu.
     */
    fun showPopup(anchor: View, title: String, populate: Rows.() -> Unit) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        lateinit var popup: PopupWindow
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_menu)
            val pad = (8 * density).toInt()
            setPadding(pad / 2, pad, pad / 2, pad)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        content.addView(TextView(context).apply {
            text = formatPhoneForDisplay(title)
            setTextColor(context.getColor(R.color.muted))
            textSize = 13f
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            maxLines = 1
        })
        Rows(content) { popup.dismiss() }.populate()
        popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 16f * density
        popup.isOutsideTouchable = true
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    class Rows(
        private val content: LinearLayout,
        private val dismiss: () -> Unit,
    ) {
        fun row(icon: Int, label: String, subtitle: String = "", danger: Boolean = false, click: () -> Unit) {
            val context = content.context
            val binding = ItemThreadMenuRowBinding.inflate(android.view.LayoutInflater.from(context), content, false)
            binding.icon.setImageResource(icon)
            binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(if (danger) DANGER else Color.WHITE)
            binding.title.text = label
            binding.title.setTextColor(if (danger) DANGER else context.getColor(R.color.text))
            binding.subtitle.isVisible = subtitle.isNotBlank()
            binding.subtitle.text = subtitle
            binding.root.setOnClickListener {
                dismiss()
                click()
            }
            content.addView(binding.root)
        }
    }

    private fun smsBacked(platform: String): Boolean {
        val value = platform.trim().lowercase()
        return value.isEmpty() || value == "sms"
    }

    data class Actions(
        val theme: () -> Unit,
        val mute: () -> Unit,
        val details: () -> Unit,
        val refresh: () -> Unit,
        val pin: () -> Unit,
        val archive: () -> Unit,
        val trash: () -> Unit,
        val block: (report: Boolean) -> Unit = {},
        val settings: () -> Unit,
    )
}
