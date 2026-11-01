package com.ubermicrostudios.messagesenhanced.ui.thread

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ubermicrostudios.messagesenhanced.databinding.DialogEmojiBinding
import com.ubermicrostudios.messagesenhanced.util.EmojiCatalog

/**
 * Category grid shared by the reaction "+" button and the composer.
 * [keepOpen] inserts without closing, the way the web composer picker stays up.
 */
object EmojiPicker {
    fun show(context: Context, keepOpen: Boolean = false, onPick: (String) -> Unit) {
        val dialog = Dialog(context)
        val binding = DialogEmojiBinding.inflate(LayoutInflater.from(context))
        val categories = EmojiCatalog.categories()
        var current = 0
        var searching = false
        val adapter = EmojiAdapter { emoji ->
            EmojiCatalog.remember(emoji)
            if (!keepOpen) dialog.dismiss()
            onPick(emoji)
        }
        binding.emojiGrid.layoutManager = GridLayoutManager(context, 8)
        binding.emojiGrid.adapter = adapter

        fun paintTabs() {
            for (i in 0 until binding.emojiTabs.childCount) {
                val tab = binding.emojiTabs.getChildAt(i) as TextView
                val on = !searching && i == current
                tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                tab.setTextColor(if (on) 0xFFF2F4F7.toInt() else 0xFF9AA3AE.toInt())
            }
        }

        fun showCategory(index: Int) {
            if (categories.isEmpty()) return
            current = index
            searching = false
            binding.emojiTabScroll.isVisible = true
            val items = categories[index].items
            val emptyRecent = categories[index].id == "recent" && items.isEmpty()
            binding.emojiEmpty.isVisible = emptyRecent
            binding.emojiEmpty.text = "Emoji you use show up here"
            binding.emojiGrid.isVisible = !emptyRecent
            adapter.submit(items.map { it.emoji })
            binding.emojiGrid.scrollToPosition(0)
            paintTabs()
        }

        categories.forEachIndexed { index, category ->
            val tab = TextView(context).apply {
                text = category.label
                textSize = 14f
                setPadding(28, 16, 28, 16)
                contentDescription = category.label
                setOnClickListener {
                    binding.emojiSearch.setText("")
                    showCategory(index)
                }
            }
            binding.emojiTabs.addView(tab)
        }
        binding.emojiSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                if (query.isBlank()) {
                    showCategory(current)
                    return
                }
                searching = true
                binding.emojiTabScroll.isVisible = false
                val hits = EmojiCatalog.search(query)
                binding.emojiGrid.isVisible = hits.isNotEmpty()
                binding.emojiEmpty.isVisible = hits.isEmpty()
                binding.emojiEmpty.text = "No emoji match \"$query\""
                adapter.submit(hits.map { it.emoji })
                paintTabs()
            }
        })
        if (categories.isNotEmpty()) showCategory(0)
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.show()
    }

    private class EmojiAdapter(
        private val onPick: (String) -> Unit,
    ) : RecyclerView.Adapter<EmojiAdapter.Holder>() {
        private var items: List<String> = emptyList()

        fun submit(next: List<String>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt(),
                )
                gravity = Gravity.CENTER
                textSize = 22f
            }
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val emoji = items[position]
            holder.view.text = emoji
            holder.view.contentDescription = EmojiCatalog.nameOf(emoji).ifBlank { emoji }
            holder.view.setOnClickListener { onPick(emoji) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)
    }
}
