package com.ubermicrostudios.messagesenhanced.ui.thread

import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy
import coil.size.Size
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.databinding.ItemViewerPageBinding
import com.ubermicrostudios.messagesenhanced.util.ImageStore
import com.ubermicrostudios.messagesenhanced.util.stableCacheKey
import java.io.File

/**
 * One rounded photo per page, over a blurred copy of that same photo.
 * API 31+ uses [RenderEffect]. Older releases decode a small bitmap and dim it.
 */
class ImagePageAdapter(
    private val imageLoader: ImageLoader,
    private val mediaUrl: (MessageDto) -> String,
    private val onZoomed: (Int, Boolean) -> Unit,
    private val onDrag: (Float) -> Unit,
    private val onDismiss: () -> Unit,
    private val onLoadError: (Int) -> Unit,
) : RecyclerView.Adapter<ImagePageAdapter.Page>() {

    private var items: List<MessageDto> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(next: List<MessageDto>) {
        items = next
        notifyDataSetChanged()
    }

    fun sameIds(next: List<MessageDto>): Boolean =
        items.size == next.size && items.indices.all { items[it].messageId == next[it].messageId }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].messageId.hashCode().toLong()

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Page {
        val binding = ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val radius = 16f * parent.resources.displayMetrics.density
        binding.photo.clipToOutline = true
        binding.photo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        styleBackdrop(binding.backdrop)
        return Page(binding)
    }

    override fun onBindViewHolder(holder: Page, position: Int) {
        val message = items[position]
        val photo = holder.binding.photo
        photo.onZoomed = { zoomed ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onZoomed(index, zoomed)
        }
        photo.onDrag = onDrag
        photo.onDismiss = onDismiss
        val source = ImageStore.openSource(message, mediaUrl(message))
        val data: Any? = when (source) {
            is ImageStore.Source.Local -> File(source.path)
            is ImageStore.Source.Remote -> source.url
            null -> null
        }
        holder.binding.pageProgress.isVisible = data != null
        if (data == null) {
            photo.setImageDrawable(null)
            holder.binding.backdrop.setImageDrawable(null)
            return
        }
        val legacy = Build.VERSION.SDK_INT < 31
        holder.binding.backdrop.load(data, imageLoader) {
            if (legacy) size(96) else size(Size.ORIGINAL)
            diskCachePolicy(CachePolicy.ENABLED)
            crossfade(false)
            stableCacheKey(data)
        }
        photo.load(data, imageLoader) {
            size(Size.ORIGINAL)
            diskCachePolicy(CachePolicy.ENABLED)
            crossfade(false)
            stableCacheKey(data)
            listener(
                onSuccess = { _, result ->
                    holder.binding.pageProgress.isVisible = false
                    val animated = (photo.drawable as? Animatable) ?: (result.drawable as? Animatable)
                    animated?.start()
                },
                onError = { _, _ ->
                    holder.binding.pageProgress.isVisible = false
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) onLoadError(index)
                },
                onCancel = {
                    holder.binding.pageProgress.isVisible = false
                },
            )
        }
    }

    private fun styleBackdrop(view: ImageView) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = 24f * view.resources.displayMetrics.density
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        } else {
            view.imageAlpha = 110
            view.colorFilter = PorterDuffColorFilter(0xCC000000.toInt(), PorterDuff.Mode.SRC_ATOP)
            view.scaleX = 1.2f
            view.scaleY = 1.2f
        }
    }

    override fun onViewRecycled(holder: Page) {
        (holder.binding.photo.drawable as? Animatable)?.stop()
        holder.binding.photo.setImageDrawable(null)
        holder.binding.backdrop.setImageDrawable(null)
    }

    class Page(val binding: ItemViewerPageBinding) : RecyclerView.ViewHolder(binding.root)
}
