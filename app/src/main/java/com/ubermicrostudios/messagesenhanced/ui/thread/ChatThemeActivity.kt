package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.WallpaperCategoryDto
import com.ubermicrostudios.messagesenhanced.data.model.WallpaperItemDto
import com.ubermicrostudios.messagesenhanced.databinding.ActivityChatThemeBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.util.ThemePalettes
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class ChatThemeActivity : SessionActivity() {
    private val viewModel: ChatThemeViewModel by viewModels()
    private lateinit var binding: ActivityChatThemeBinding
    private var shownBackground: String? = null
    private var gridQueued = false
    private var wasCategory = false
    private val optionsBackground = GradientDrawable()

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importPhoto(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (id.isBlank()) {
            finish()
            return
        }
        Insets.enable(this)
        binding = ActivityChatThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.themeRoot, includeIme = false)
        observeAuthLoss()
        binding.preview.clipToOutline = true
        binding.preview.outlineProvider = ViewOutlineProvider.BACKGROUND
        val radius = dpf(20f)
        optionsBackground.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        optionsBackground.setColor(getColor(R.color.panel))
        binding.optionsPanel.background = optionsBackground
        binding.credits.paintFlags = binding.credits.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        val leave = {
            if (!viewModel.back()) finish()
        }
        binding.back.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
        binding.apply.setOnClickListener { viewModel.primary() }
        binding.reset.setOnClickListener { viewModel.reset() }
        binding.choose.setOnClickListener { pickPhoto.launch("image/*") }
        binding.remove.setOnClickListener { viewModel.removePhoto() }
        binding.credits.setOnClickListener { openCredits() }
        viewModel.start(id, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getBooleanExtra(EXTRA_GROUP, false))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { ui ->
                    val entered = ui.inCategory && !wasCategory
                    wasCategory = ui.inCategory
                    render(ui)
                    if (entered) binding.bodyScroll.scrollTo(0, 0)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is ChatThemeViewModel.Effect.Toast ->
                            Toast.makeText(this@ChatThemeActivity, effect.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun render(ui: ChatThemeViewModel.Ui) {
        if (ui.done) {
            if (!saveAnnounced()) {
                Toast.makeText(this, ThemeScreen.SAVED, Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        val draft = ui.draft
        val colors = ThemePalettes.colors(ThemeScreen.toDto(draft))
        val enabled = ThemeScreen.applyEnabled(ui.inCategory, ui.busy, draft, ui.saved, ui.catBefore)
        title = ThemeScreen.title(ui.inCategory, ui.categoryName)
        binding.title.text = ThemeScreen.title(ui.inCategory, ui.categoryName)
        binding.subtitle.text = ThemeScreen.subtitle(ui.inCategory, formatPhoneForDisplay(ui.contactName))
        binding.apply.text = ThemeScreen.applyLabel(ui.inCategory, ui.busy)
        binding.apply.isEnabled = enabled
        binding.apply.setTextColor(if (enabled) INK else getColor(R.color.muted))
        binding.apply.background = pill(if (enabled) colors.light else getColor(R.color.panel2), 20f)
        val resetOn = ThemeScreen.resetEnabled(ui.busy, draft)
        binding.reset.isEnabled = resetOn
        val resetColor = if (resetOn) colors.light else getColor(R.color.muted)
        binding.resetLabel.setTextColor(resetColor)
        binding.resetIcon.imageTintList = ColorStateList.valueOf(resetColor)
        binding.reset.alpha = if (resetOn) 1f else 0.5f
        binding.choose.isEnabled = !ui.busy
        binding.choose.alpha = if (ui.busy) 0.5f else 1f
        binding.choose.background = pill(colors.light, 24f)
        binding.chooseLabel.setTextColor(INK)
        binding.chooseIcon.imageTintList = ColorStateList.valueOf(INK)
        binding.remove.isVisible = ThemeScreen.removeVisible(draft)
        binding.remove.isEnabled = !ui.busy
        binding.remove.text = ThemeScreen.removeLabel(draft)
        binding.remove.background = GradientDrawable().apply {
            cornerRadius = dpf(24f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), 0x40FFFFFF)
        }
        optionsBackground.setColor(ThemeScreen.panelSurface(draft) ?: getColor(R.color.panel))
        bindPreview(ui, colors)
        bindSwatches(draft, colors.light)
        binding.themeMain.isVisible = !ui.inCategory
        binding.themeCategory.isVisible = ui.inCategory
        if (ui.inCategory) {
            binding.cats.removeAllViews()
            bindPhotos(ui, colors.light)
        } else {
            binding.photos.removeAllViews()
            bindCategories(ui, colors.light)
        }
    }

    private fun bindPreview(ui: ChatThemeViewModel.Ui, colors: ThemeColors) {
        val draft = ui.draft
        binding.previewThem.text = ThemeScreen.PREVIEW_THEM
        binding.previewMe.text = ThemeScreen.PREVIEW_ME
        binding.previewTime.text = ThemeScreen.PREVIEW_TIME
        binding.previewHint.text = ThemeScreen.PREVIEW_HINT
        val sender = ThemeScreen.previewSender(ui.isGroup)
        binding.previewName.text = sender
        binding.previewName.isVisible = sender.isNotEmpty()
        binding.previewName.setTextColor(colors.light)
        binding.previewThemBubble.background = bubble(colors.them, 16f, 16f, 16f, 4f)
        binding.previewMe.background = bubble(colors.me, 16f, 16f, 4f, 16f)
        binding.previewMic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors.accent)
        }
        binding.previewMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
        val pending = draft.pendingPath
        val remote = if (pending.isNotBlank()) "" else viewModel.backgroundUrl(ui.conversationId, ThemeScreen.toDto(draft)).orEmpty()
        val key = pending.ifBlank { remote }
        val hasImage = key.isNotBlank()
        binding.previewScrim.isVisible = hasImage
        if (hasImage) {
            binding.previewTime.setTextColor(Color.WHITE)
            binding.previewTime.background = GradientDrawable().apply {
                cornerRadius = dpf(6f)
                setColor(0x8C000000.toInt())
            }
        } else {
            binding.previewTime.setTextColor(0xBFFFFFFF.toInt())
            binding.previewTime.background = null
        }
        if (key == shownBackground) return
        shownBackground = key
        if (pending.isNotBlank()) {
            binding.previewImage.isVisible = true
            binding.previewImage.load(File(pending))
        } else if (remote.isNotBlank()) {
            binding.previewImage.isVisible = true
            binding.previewImage.load(remote)
        } else {
            binding.previewImage.isVisible = false
            binding.previewImage.setImageDrawable(null)
        }
    }

    private fun bindSwatches(draft: ThemeScreen.ThemeFields, ring: Int) {
        binding.colors.removeAllViews()
        val defaults = ThemeColors.Default
        binding.colors.addView(
            swatch(
                light = defaults.light,
                them = defaults.them,
                me = defaults.me,
                ring = ring,
                selected = ThemeScreen.defaultSwatchSelected(draft),
                label = ThemeScreen.defaultSwatchLabel(),
            ) { viewModel.selectDefault() },
        )
        for (palette in ThemePalettes.all) {
            binding.colors.addView(
                swatch(
                    light = palette.light,
                    them = palette.them,
                    me = palette.me,
                    ring = ring,
                    selected = ThemeScreen.paletteSwatchSelected(draft, palette.id),
                    label = ThemeScreen.paletteSwatchLabel(palette.name),
                ) { viewModel.selectPalette(palette.id) },
            )
        }
        val custom = ThemeScreen.customSwatchHex(draft)
        if (custom != null) {
            val color = custom.removePrefix("#").toInt(16) or 0xFF000000.toInt()
            binding.colors.addView(
                swatch(
                    light = color,
                    them = color,
                    me = color,
                    ring = ring,
                    selected = true,
                    label = ThemeScreen.customSwatchLabel(),
                    solid = color,
                ) { },
            )
        }
    }

    private fun swatch(
        light: Int,
        them: Int,
        me: Int,
        ring: Int,
        selected: Boolean,
        label: String,
        solid: Int? = null,
        onClick: () -> Unit,
    ): ThemeSwatchView {
        val size = dp(48)
        return ThemeSwatchView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            lightColor = light
            themColor = them
            meColor = me
            ringColor = ring
            solidColor = solid
            selectedSwatch = selected
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    private fun bindCategories(ui: ChatThemeViewModel.Ui, ring: Int) {
        val status = when {
            ui.wallpaperError.isNotBlank() -> ui.wallpaperError
            ui.wallpapers == null -> ThemeScreen.WALLPAPER_LOADING
            else -> ""
        }
        binding.wallpaperStatus.isVisible = status.isNotBlank()
        binding.wallpaperStatus.text = status
        val categories = ui.wallpapers.orEmpty().filter { it.items.isNotEmpty() }
        fillGrid(binding.cats, categories.size, photo = false) { index, tile ->
            categoryCell(categories[index], ring, ui.draft.wallpaper, tile)
        }
    }

    private fun bindPhotos(ui: ChatThemeViewModel.Ui, ring: Int) {
        val category = ui.wallpapers.orEmpty().firstOrNull { it.id == ui.categoryId }
            ?: ui.wallpapers.orEmpty().firstOrNull { it.name == ui.categoryName }
        val items = category?.items.orEmpty()
        val selected = items.firstOrNull { it.id == ui.draft.wallpaper }
        binding.credit.text = if (selected == null) {
            ""
        } else {
            ThemeScreen.credit(selected.title, selected.author, selected.license, true)
        }
        fillGrid(binding.photos, items.size, photo = true) { index, _ ->
            val item = items[index]
            photoCell(item, index, items.size, ring, item.id == ui.draft.wallpaper)
        }
    }

    private fun fillGrid(grid: GridLayout, count: Int, photo: Boolean, cell: (index: Int, tile: Int) -> View) {
        if (count == 0) {
            grid.removeAllViews()
            return
        }
        if (grid.width == 0) {
            if (!gridQueued) {
                gridQueued = true
                grid.post {
                    gridQueued = false
                    if (!isFinishing) render(viewModel.ui.value)
                }
            }
            return
        }
        val gap = dp(8)
        val columns = ThemeScreen.columnCount(grid.width, dp(104))
        val tile = (grid.width - gap * (columns - 1)) / columns
        if (tile <= 0) return
        grid.removeAllViews()
        grid.columnCount = columns
        for (index in 0 until count) {
            val params = GridLayout.LayoutParams().apply {
                width = tile
                height = if (photo) tile * 4 / 3 else GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % columns)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(if (index % columns == 0) 0 else gap, if (index < columns) 0 else gap, 0, 0)
            }
            grid.addView(cell(index, tile), params)
        }
    }

    private fun categoryCell(category: WallpaperCategoryDto, ring: Int, wallpaperId: String, tile: Int): View {
        val selected = ThemeScreen.categorySelected(category.items.map { it.id }, wallpaperId)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val image = roundedImage(category.items.first())
        if (selected) image.foreground = ring(ring)
        column.addView(image, LinearLayout.LayoutParams(tile, tile))
        column.addView(TextView(this).apply {
            text = category.name.ifBlank { category.id }
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(tile, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        column.setOnClickListener { viewModel.openCategory(category.id, category.name.ifBlank { category.id }) }
        column.contentDescription = category.name.ifBlank { category.id }
        return column
    }

    private fun photoCell(item: WallpaperItemDto, index: Int, count: Int, ring: Int, selected: Boolean): View {
        val frame = FrameLayout(this)
        val image = roundedImage(item)
        frame.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        if (selected) {
            frame.foreground = ring(ring)
            val badge = TextView(this).apply {
                text = "\u2713"
                gravity = Gravity.CENTER
                setTextColor(INK)
                textSize = 12f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ring)
                }
            }
            val size = dp(22)
            frame.addView(badge, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(6)
                marginEnd = dp(6)
            })
        }
        frame.contentDescription = ThemeScreen.photoContentDescription(item.title.ifBlank { item.id }, index + 1, count)
        frame.setOnClickListener { viewModel.selectWallpaper(item.id) }
        return frame
    }

    private fun roundedImage(item: WallpaperItemDto): ImageView {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(16f))
                }
            }
            clipToOutline = true
            setBackgroundColor(getColor(R.color.panel2))
            val path = item.thumbPath()
            if (path.isNotBlank()) load(ComposerPlan.absoluteUrl(viewModel.origin(), path))
        }
    }

    private fun ring(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(16f)
        setStroke(dp(3), color)
        setColor(Color.TRANSPARENT)
    }

    private fun saveAnnounced(): Boolean {
        if (viewModel.saveAnnounced) return true
        viewModel.saveAnnounced = true
        return false
    }

    private fun openCredits() {
        val url = ComposerPlan.absoluteUrl(viewModel.origin(), "/wallpapers/CREDITS.md")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun pill(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(color)
    }

    private fun bubble(color: Int, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): GradientDrawable {
        val tl = dpf(topLeft)
        val tr = dpf(topRight)
        val br = dpf(bottomRight)
        val bl = dpf(bottomLeft)
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val EXTRA_ID = "conversation_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_GROUP = "is_group"
        private const val INK = 0xFF0B1A2E.toInt()
    }
}
