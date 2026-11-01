package com.ubermicrostudios.messagesenhanced.ui.thread

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.GifPage
import com.ubermicrostudios.messagesenhanced.databinding.DialogGifBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemGifBinding
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop GIF panel: search (trending when the box is empty), scroll for the next page,
 * favorites, and a tap sends.
 */
class GifPicker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val origin: String,
    private val search: suspend (query: String, page: Int) -> GifPage,
    private val onSend: (ComposerPlan.GifRef) -> Unit,
) {
    private var dialog: Dialog? = null
    private var job: Job? = null
    private var generation = 0
    private var loading = false
    private var hasMore = true
    private var page = 0
    private var query = ""
    private val results = ArrayList<ComposerPlan.GifRef>()
    private var favorites = GifStore.load(context).toMutableList()

    fun show() {
        if (dialog?.isShowing == true) return
        val binding = DialogGifBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        this.dialog = dialog
        val adapter = GifAdapter(
            origin = origin,
            favorite = { favorites.any { saved -> saved.url == it.url } },
            onStar = { toggleFavorite(it, binding) },
            onSend = { gif ->
                dialog.dismiss()
                onSend(gif)
            },
        )
        val grid = GridLayoutManager(context, 2)
        binding.gifGrid.layoutManager = grid
        binding.gifGrid.adapter = adapter
        binding.gifGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore) return
                val last = grid.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 4) fetch(binding, adapter, query, append = true)
            }
        })
        binding.gifSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString().orEmpty()
                job?.cancel()
                job = scope.launch {
                    delay(ComposerPlan.GIF_DEBOUNCE_MS)
                    fetch(binding, adapter, next, append = false)
                }
            }
        })
        renderFavorites(binding, adapter)
        dialog.setOnDismissListener {
            job?.cancel()
            generation++
            loading = false
            if (this.dialog === dialog) this.dialog = null
        }
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.show()
        fetch(binding, adapter, "", append = false)
    }

    private fun toggleFavorite(gif: ComposerPlan.GifRef, binding: DialogGifBinding) {
        favorites = ComposerPlan.toggleFavorite(favorites, gif).toMutableList()
        GifStore.save(context, favorites)
        renderFavorites(binding, binding.gifGrid.adapter as GifAdapter)
        (binding.gifGrid.adapter as GifAdapter).notifyDataSetChanged()
    }

    private fun renderFavorites(binding: DialogGifBinding, adapter: GifAdapter) {
        binding.gifFavoritesLabel.isVisible = favorites.isNotEmpty()
        binding.gifFavoritesScroll.isVisible = favorites.isNotEmpty()
        binding.gifFavorites.removeAllViews()
        val size = (72 * context.resources.displayMetrics.density).toInt()
        val pad = (6 * context.resources.displayMetrics.density).toInt()
        for (gif in favorites) {
            val tile = ImageView(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply { marginEnd = pad }
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = gif.title
                setOnClickListener {
                    dialog?.dismiss()
                    onSend(gif)
                }
            }
            tile.load(ComposerPlan.absoluteUrl(origin, gif.previewUrl))
            binding.gifFavorites.addView(tile)
        }
        adapter.favorite = { gif -> favorites.any { it.url == gif.url } }
    }

    private fun fetch(binding: DialogGifBinding, adapter: GifAdapter, nextQuery: String, append: Boolean) {
        val requested = nextQuery.trim()
        if (append && (loading || !hasMore || requested != query)) return
        val gen = if (append) generation else ++generation
        loading = true
        val pageToLoad = if (append) page + 1 else 1
        if (!append) {
            query = requested
            page = 0
            hasMore = true
            results.clear()
            adapter.submit(emptyList())
            binding.gifStatus.isVisible = true
            binding.gifStatus.text = ComposerPlan.GIF_LOADING
            binding.gifGrid.scrollToPosition(0)
        }
        binding.gifMore.isVisible = append
        binding.gifMore.text = "Loading more GIFs..."
        scope.launch {
            val outcome = runCatchingCancellable { search(requested, pageToLoad) }
            if (gen != generation || dialog?.isShowing != true) return@launch
            loading = false
            binding.gifMore.isVisible = false
            outcome.onSuccess { payload ->
                val incoming = payload.results.mapNotNull { row ->
                    ComposerPlan.normalizeGif(row.id, row.title, row.previewUrl, row.url, row.mimeType, row.width, row.height)
                }
                val merged = ComposerPlan.mergeGifs(
                    existing = if (append) results.toList() else emptyList(),
                    incoming = incoming,
                    reportedHasMore = payload.hasMore,
                )
                results.clear()
                results.addAll(merged.items)
                page = pageToLoad
                hasMore = merged.hasMore
                adapter.submit(results.toList())
                binding.gifStatus.isVisible = results.isEmpty()
                binding.gifStatus.text = ComposerPlan.GIF_EMPTY
            }.onFailure { error ->
                if (results.isEmpty()) {
                    binding.gifStatus.isVisible = true
                    binding.gifStatus.text = error.message?.ifBlank { null } ?: "GIF search failed."
                }
                hasMore = false
            }
        }
    }

    private class GifAdapter(
        private val origin: String,
        var favorite: (ComposerPlan.GifRef) -> Boolean,
        private val onStar: (ComposerPlan.GifRef) -> Unit,
        private val onSend: (ComposerPlan.GifRef) -> Unit,
    ) : RecyclerView.Adapter<GifAdapter.Holder>() {
        private var items: List<ComposerPlan.GifRef> = emptyList()

        fun submit(next: List<ComposerPlan.GifRef>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemGifBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val gif = items[position]
            val on = favorite(gif)
            holder.binding.preview.contentDescription = gif.title
            holder.binding.preview.load(ComposerPlan.absoluteUrl(origin, gif.previewUrl))
            holder.binding.star.setImageResource(if (on) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            holder.binding.star.contentDescription = if (on) "Remove from favorite GIFs" else "Add to favorite GIFs"
            holder.binding.star.setOnClickListener { onStar(gif) }
            holder.binding.preview.setOnClickListener { onSend(gif) }
            holder.binding.root.setOnClickListener { onSend(gif) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val binding: ItemGifBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
