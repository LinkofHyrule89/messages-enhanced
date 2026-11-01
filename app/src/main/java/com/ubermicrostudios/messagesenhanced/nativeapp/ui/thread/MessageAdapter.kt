package com.ubermicrostudios.messagesenhanced.nativeapp.ui.thread

import com.ubermicrostudios.messagesenhanced.nativeapp.util.EmojiOnly
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import com.ubermicrostudios.messagesenhanced.nativeapp.util.Mentions
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.imageview.ShapeableImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import java.io.File
import com.ubermicrostudios.messagesenhanced.nativeapp.R
import com.ubermicrostudios.messagesenhanced.nativeapp.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.nativeapp.data.model.LinkPreviewDto
import com.ubermicrostudios.messagesenhanced.nativeapp.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.nativeapp.data.model.ParticipantDto
import com.ubermicrostudios.messagesenhanced.nativeapp.databinding.ItemMessageMeBinding
import com.ubermicrostudios.messagesenhanced.nativeapp.databinding.ItemMessageThemBinding
import com.ubermicrostudios.messagesenhanced.nativeapp.ui.conversations.icon
import com.ubermicrostudios.messagesenhanced.nativeapp.ui.conversations.tint
import com.ubermicrostudios.messagesenhanced.nativeapp.util.BubbleColors
import com.ubermicrostudios.messagesenhanced.nativeapp.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.nativeapp.util.RecyclerIds
import com.ubermicrostudios.messagesenhanced.nativeapp.util.IMAGE_PLACEHOLDER_COLOR
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ImageAspects
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ImageCacheKeys
import com.ubermicrostudios.messagesenhanced.nativeapp.util.LocalImageBounds
import com.ubermicrostudios.messagesenhanced.nativeapp.util.cachedMedia
import com.ubermicrostudios.messagesenhanced.nativeapp.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.nativeapp.util.stableCacheKey
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ConversationEncryption
import com.ubermicrostudios.messagesenhanced.nativeapp.util.Encryption
import com.ubermicrostudios.messagesenhanced.nativeapp.util.Formatters
import com.ubermicrostudios.messagesenhanced.nativeapp.util.LinkUrls
import com.ubermicrostudios.messagesenhanced.nativeapp.util.MessageRuns
import com.ubermicrostudios.messagesenhanced.nativeapp.util.MessageStatus
import com.ubermicrostudios.messagesenhanced.nativeapp.util.Participants
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ReceiptKind
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ReadReceipts
import com.ubermicrostudios.messagesenhanced.nativeapp.util.SharedLocation
import com.ubermicrostudios.messagesenhanced.nativeapp.util.RichContent
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ReplyTexts
import com.ubermicrostudios.messagesenhanced.nativeapp.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.nativeapp.util.formatPhoneForDisplay
import com.ubermicrostudios.messagesenhanced.nativeapp.util.formatPhonesInText

class MessageAdapter(
    private val mediaUrl: (MessageDto) -> String,
    private val previewOf: (String) -> LinkPreviewDto?,
    private val imageOf: (LinkPreviewDto) -> String?,
    private val requestPreview: (String) -> Unit,
    private val chrome: () -> ThreadChrome,
    private val conversation: () -> ConversationDto,
    private val origin: () -> String,
    private val messageById: (String) -> MessageDto?,
    private val onQuote: (String) -> Unit,
    private val syncGesture: (View) -> Unit,
    private val onRecycled: (View) -> Unit,
    private val onImage: (MessageDto) -> Unit,
    private val onMessageAction: (MessageDto, String) -> Unit = { _, _ -> },
    private val animatedEmoji: () -> Boolean = { true },
    /** Fetches a small text attachment (vCard, .ics, location push, rich card JSON). */
    private val richText: (MessageDto, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
) : ListAdapter<MessageRuns.Row, RecyclerView.ViewHolder>(Diff) {

    var starredIds: Set<String> = emptySet()
    var selectedIds: Set<String> = emptySet()
    var selecting: Boolean = false
    var detailId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    fun setMarks(starred: Set<String>, selected: Set<String>, selecting: Boolean) {
        if (starred == starredIds && selected == selectedIds && selecting == this.selecting) return
        starredIds = starred
        selectedIds = selected
        this.selecting = selecting
        notifyDataSetChanged()
    }

    object Diff : DiffUtil.ItemCallback<MessageRuns.Row>() {
        override fun areItemsTheSame(a: MessageRuns.Row, b: MessageRuns.Row) =
            a.message.messageId == b.message.messageId

        override fun areContentsTheSame(a: MessageRuns.Row, b: MessageRuns.Row) = a == b
    }

    override fun getItemId(position: Int): Long =
        RecyclerIds.of(1, getItem(position).message.messageId)

    override fun getItemViewType(position: Int): Int {
        val m = getItem(position).message
        return when {
            RichContent.isNotice(m.status) && RichContent.notice(m.status, m.body) != null -> TYPE_SYSTEM
            m.isFromMe -> TYPE_ME
            else -> TYPE_THEM
        }
    }

    private val richCache = HashMap<String, String>()
    private val richPending = HashSet<String>()

    /** Voice note playback state, driven by the fragment. */
    var voiceId: String = ""
        private set
    private var voiceProgress = 0f
    private var voicePlaying = false
    private var voiceDurationMs = 0
    private val voiceDurations = HashMap<String, Int>()

    fun setVoiceState(id: String, playing: Boolean, progress: Float, durationMs: Int) {
        val old = voiceId
        voiceId = id; voicePlaying = playing; voiceProgress = progress; voiceDurationMs = durationMs
        if (id.isNotBlank() && durationMs > 0) voiceDurations[id] = durationMs
        listOf(old, id).filter { it.isNotBlank() }.distinct().forEach { mid ->
            val pos = currentList.indexOfFirst { it.message.messageId == mid }
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    private fun richTextOf(item: MessageDto): String? {
        richCache[item.messageId]?.let { return it }
        if (item.messageId.isBlank() || item.messageId.startsWith("local-") || !richPending.add(item.messageId)) return null
        richText(item) { text ->
            richPending.remove(item.messageId)
            richCache[item.messageId] = text.orEmpty()
            val pos = currentList.indexOfFirst { it.message.messageId == item.messageId }
            if (pos >= 0) notifyItemChanged(pos)
        }
        return null
    }

    inner class SystemHolder(val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(row: MessageRuns.Row) {
            val m = row.message
            val n = RichContent.notice(m.status, m.body) ?: return
            val icon = when (n.icon) {
                "call" -> "\u260E\uFE0F"; "lock" -> "\uD83D\uDD12"; "unlock" -> "\uD83D\uDD13"; "shield" -> "\uD83D\uDEE1\uFE0F"
                "warn" -> "\u26A0\uFE0F"; "group" -> "\uD83D\uDC65"; "edit" -> "\u270F\uFE0F"; else -> "\u2139\uFE0F"
            }
            val clock = Formatters.clockTime(m.timestampMs)
            text.text = "$icon  ${n.text}" + if (clock.isNotBlank()) "  \u00B7  $clock" else ""
            text.setTextColor(chrome().colors.light)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_SYSTEM) {
            val d = parent.resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
                textSize = 12f
                alpha = 0.75f
            }
            return SystemHolder(tv)
        }
        val holder = if (viewType == TYPE_ME) {
            MeHolder(ItemMessageMeBinding.inflate(inflater, parent, false))
        } else {
            ThemHolder(ItemMessageThemBinding.inflate(inflater, parent, false))
        }
        (holder.itemView as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        return holder
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        onRecycled(holder.itemView)
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is MeHolder -> holder.bind(row)
            is ThemHolder -> holder.bind(row)
            is SystemHolder -> holder.bind(row)
        }
    }

    inner class MeHolder(private val b: ItemMessageMeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: MessageRuns.Row) = bindBubble(
            row,
            root = b.root,
            bubble = b.bubble,
            fromMe = true,
            sender = b.sender,
            body = b.body,
            time = b.time,
            lockIcon = b.lockIcon,
            reactions = b.reactions,
            image = b.image,
            attachment = b.attachment,
            linkCard = b.linkCard,
            linkImage = b.linkImage,
            linkSite = b.linkSite,
            linkTitle = b.linkTitle,
            linkDesc = b.linkDesc,
            receipt = b.receipt,
            receiptAvatars = b.receiptAvatars,
            receiptLabel = b.receiptLabel,
            receiptIcon = b.receiptIcon,
            tapDetail = b.tapDetail,
            tapDetailText = b.tapDetailText,
            tapDetailLock = b.tapDetailLock,
        )
    }

    inner class ThemHolder(private val b: ItemMessageThemBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: MessageRuns.Row) {
            bindBubble(
                row,
                root = b.root,
                bubble = b.bubble,
                fromMe = false,
                sender = b.sender,
                body = b.body,
                time = b.time,
                lockIcon = b.lockIcon,
                reactions = b.reactions,
                image = b.image,
                attachment = b.attachment,
                linkCard = b.linkCard,
                linkImage = b.linkImage,
                linkSite = b.linkSite,
                linkTitle = b.linkTitle,
                linkDesc = b.linkDesc,
                receipt = b.receipt,
                receiptAvatars = b.receiptAvatars,
                receiptLabel = b.receiptLabel,
                receiptIcon = b.receiptIcon,
                tapDetail = b.tapDetail,
                tapDetailText = b.tapDetailText,
                tapDetailLock = b.tapDetailLock,
            )
            bindSenderAvatar(row, b.senderAvatar, b.senderAvatarPhoto)
        }
    }

    private fun bindBubble(
        row: MessageRuns.Row,
        root: View,
        bubble: View,
        fromMe: Boolean,
        sender: TextView,
        body: TextView,
        time: TextView,
        lockIcon: ImageView,
        reactions: TextView,
        image: ImageView,
        attachment: TextView,
        linkCard: View,
        linkImage: ImageView,
        linkSite: TextView,
        linkTitle: TextView,
        linkDesc: TextView,
        receipt: View,
        receiptAvatars: LinearLayout,
        receiptLabel: TextView,
        receiptIcon: ImageView,
        tapDetail: View,
        tapDetailText: TextView,
        tapDetailLock: ImageView,
    ) {
        val item = row.message
        val look = chrome()
        val density = bubble.resources.displayMetrics.density
        val grouped = row.pos == MessageRuns.Pos.MID || row.pos == MessageRuns.Pos.LAST
        root.setPadding(root.paddingLeft, if (grouped) (1 * density).toInt() else (6 * density).toInt(), root.paddingRight, root.paddingBottom)

        val senderName = item.senderName.ifBlank { item.senderNumber }
        sender.isVisible = row.showSender && senderName.isNotBlank()
        sender.text = formatPhoneForDisplay(senderName)
        run {
            val conv = conversation()
            val person = item.senderNumber.takeIf { it.any(Char::isDigit) }?.let { Participants.memberByName(conv, it) }
                ?: item.senderName.takeIf { it.isNotBlank() }?.let { Participants.memberByName(conv, it) }
            val base = Participants.senderColor(person, item.senderNumber.ifBlank { item.senderName })
            val night = (sender.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            // Lift dark tones on dark theme, deepen light tones on light theme, so names stay readable.
            val lum = androidx.core.graphics.ColorUtils.calculateLuminance(base)
            sender.setTextColor(when {
                night && lum < 0.35 -> androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.WHITE, 0.45f)
                !night && lum > 0.3 -> androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.BLACK, 0.4f)
                else -> base
            })
        }

        bubble.background = bubbleDrawable(density, fromMe, row.pos, BubbleColors.fill(look.colors, fromMe))
        // Hide the previewed URL when its card shows; no card -> keep the link.
        val deleted = MessageStatus.isDeleted(item.status)
        val shownText = if (deleted) MessageStatus.DELETED_TEXT else LinkUrls.firstHttpUrl(item.body)
            ?.takeIf { previewOf(it) != null }
            ?.let { LinkUrls.stripUrl(item.body, it) } ?: item.body
        linkify(body, shownText, if (fromMe) Color.WHITE else look.colors.light)
        body.setTypeface(null, if (deleted) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
        body.alpha = if (deleted) 0.7f else 1f
        body.isVisible = shownText.isNotBlank()

        val kind = if (item.isFromMe) MessageStatus.statusKind(item) else null
        val clock = Formatters.clockTime(item.timestampMs)
        val statusOnly = MessageStatus.bubbleLabel(item)
        time.isVisible = statusOnly.isNotEmpty()
        time.text = statusOnly
        time.setTextColor(if (kind == ReceiptKind.FAILED) 0xFFFF8589.toInt() else 0x99FFFFFF.toInt())
        // Google Messages: tap a failed message to retry.
        if (kind == ReceiptKind.FAILED && item.messageId.isNotBlank() && !item.messageId.startsWith("local-")) {
            time.setOnClickListener { if (!selecting) onMessageAction(item, "resend") }
        } else {
            time.setOnClickListener(null)
            time.isClickable = false
        }

        // Emoji-only messages: animated Noto emoji (single) or large text (2-3), no bubble.
        val baseSize = (body.getTag(R.id.tag_body_size) as? Float) ?: body.textSize.also { body.setTag(R.id.tag_body_size, it) }
        val emojis = if (!deleted && !item.hasAttachment()) EmojiOnly.split(item.body) else null
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, if (emojis != null) baseSize * 2.2f else baseSize)
        if (emojis != null) bubble.background = null

        val locked = Encryption.messageEncrypted(item, look.encryption) ||
            Encryption.protocolEncrypted(look.displayProtocol)
        lockIcon.isVisible = false
        bindReveal(root, clock, locked)
        bindQuote(root, item)
        root.setTag(R.id.tag_message_id, item.messageId)
        syncGesture(root)

        val reactionText = Formatters.formatReactions(item.reactions)
        reactions.text = reactionText
        reactions.isVisible = reactionText.isNotBlank()
        root.findViewById<TextView>(R.id.star_mark)?.isVisible = item.messageId in starredIds
        bubble.foreground = if (selecting && item.messageId in selectedIds) selectionStroke(density) else null

        val mmsPending = !item.isFromMe && MMS_DOWNLOAD.containsMatchIn(item.status)
        attachment.setOnClickListener(null)
        attachment.isClickable = false
        when {
            emojis != null && emojis.size == 1 && animatedEmoji() -> {
                attachment.isVisible = false
                image.isVisible = true
                body.isVisible = false
                image.load(EmojiOnly.notoUrl(emojis[0]), EmojiLoader.get(image.context)) {
                    size((64 * density).toInt())
                    listener(onError = { _, _ ->
                        image.isVisible = false
                        body.isVisible = true
                    })
                }
            }
            mmsPending -> {
                image.isVisible = false
                attachment.isVisible = true
                val expired = item.status.contains("EXPIRED")
                attachment.text = if (expired) "Multimedia message expired" else attachment.context.getString(R.string.mms_tap_download)
                if (!expired) {
                    attachment.setOnClickListener {
                        if (selecting) return@setOnClickListener
                        attachment.text = attachment.context.getString(R.string.mms_downloading)
                        onMessageAction(item, "download")
                    }
                }
            }
            item.isImageMedia() && item.localPreview.isNotBlank() -> {
                image.isVisible = true
                attachment.isVisible = false
                val src = item.localPreview
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    image.load(src) {
                        cachedMedia(src)
                        listener(onSuccess = { _, result -> rememberDecoded(src, result.drawable) })
                    }
                } else {
                    val path = src.removePrefix("file://")
                    image.load(File(path)) {
                        listener(onSuccess = { _, result -> rememberDecoded(path, result.drawable) })
                    }
                }
            }
            item.isImageMedia() && item.messageId.isNotBlank() && !item.messageId.startsWith("local-") -> {
                image.isVisible = true
                attachment.isVisible = false
                val url = mediaUrl(item)
                image.load(url) {
                    cachedMedia(url)
                    listener(onSuccess = { _, result -> rememberDecoded(url, result.drawable) })
                }
            }
            // The attachment is the link/location preview itself: show just the card, no label.
            item.hasAttachment() && !item.mimeType.startsWith("audio/") && !item.mimeType.startsWith("video/") &&
                (SharedLocation.parse(item.body) != null || LinkUrls.firstHttpUrl(item.body)?.let { previewOf(it) } != null) -> {
                image.isVisible = false
                attachment.isVisible = false
            }
            item.hasAttachment() -> {
                image.isVisible = false
                attachment.isVisible = true
                bindRichAttachment(item, attachment, body)
            }
            else -> {
                image.isVisible = false
                val chip = if (deleted) "" else RichContent.otp(item.body)
                val pay = if (deleted || chip.isNotEmpty()) "" else RichContent.payment(item.body)
                attachment.isVisible = chip.isNotEmpty() || pay.isNotEmpty()
                if (chip.isNotEmpty()) {
                    attachment.text = attachment.context.getString(R.string.rich_copy_code, chip)
                    attachment.setOnClickListener { if (!selecting) onMessageAction(item, "copy:$chip") }
                } else if (pay.isNotEmpty()) {
                    attachment.text = "\uD83D\uDCB3  $pay"
                    attachment.setOnClickListener { if (!selecting) onMessageAction(item, "copy:$pay") }
                }
            }
        }
        // Stickers: no bubble behind the image.
        if (image.isVisible && emojis == null && (item.mimeType.contains("webp", true) || item.body.trim().equals("[Sticker]", true))) {
            bubble.background = null
            if (item.body.trim().equals("[Sticker]", true)) body.isVisible = false
        }

        bindLink(item.body, linkCard, linkImage, linkSite, linkTitle, linkDesc)
        layoutEdgeToEdge(item, bubble, image, body, linkCard, sender, emojis != null)
        bindReceipt(row, kind, receipt, receiptAvatars, receiptLabel, receiptIcon, look)
        bindTapDetail(item, locked, tapDetail, tapDetailText, tapDetailLock)
        if (image.isVisible && emojis == null) {
            image.setOnClickListener { if (!selecting) onImage(item) }
        } else {
            image.setOnClickListener(null)
        }
        image.isClickable = image.isVisible && emojis == null
        reactions.isClickable = reactions.isVisible
    }

    private fun bindRichAttachment(item: MessageDto, attachment: TextView, body: TextView) {
        val ctx = attachment.context
        val kind = RichContent.kind(item.mimeType, item.body)
        val generic = item.body.isBlank() || item.body.trim().startsWith("[") || item.body.trim() == item.body.trim().substringAfterLast('/') && '.' in item.body
        fun hideGenericBody() { if (generic) body.isVisible = false }
        fun tap(action: String) = attachment.setOnClickListener { if (!selecting) onMessageAction(item, action) }
        when (kind) {
            RichContent.Kind.VCARD -> {
                hideGenericBody()
                val c = richTextOf(item)?.takeIf { it.isNotBlank() }?.let { RichContent.vcard(it) }
                attachment.text = if (c == null) "\uD83D\uDC64  " + ctx.getString(R.string.rich_contact)
                else (listOf("\uD83D\uDC64  ${c.name}") + c.phones.take(3).map(::formatPhoneForDisplay) + c.emails.take(2) +
                    ctx.getString(R.string.rich_add_contact)).joinToString("\n")
                tap("add_contact")
            }
            RichContent.Kind.ICS -> {
                hideGenericBody()
                val e = richTextOf(item)?.takeIf { it.isNotBlank() }?.let { RichContent.ics(it) }
                attachment.text = if (e == null) "\uD83D\uDCC5  " + ctx.getString(R.string.rich_event)
                else listOfNotNull(
                    "\uD83D\uDCC5  ${e.title}",
                    e.startMs?.let {
                        val f = if (e.allDay) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                        else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        f.format(java.util.Date(it))
                    },
                    e.location.ifBlank { null },
                    ctx.getString(R.string.rich_add_event),
                ).joinToString("\n")
                tap("add_event")
            }
            RichContent.Kind.PUSH_LOCATION -> {
                hideGenericBody()
                val loc = richTextOf(item)?.let { RichContent.pushLocation(it) }
                attachment.text = "\uD83D\uDCCD  " + (loc?.label?.ifBlank { null } ?: ctx.getString(R.string.rich_location)) + "\n" + ctx.getString(R.string.rich_open_maps)
                if (loc != null) tap("open_url:${loc.url}")
            }
            RichContent.Kind.RICH_CARD -> {
                hideGenericBody()
                val r = richTextOf(item)?.let { RichContent.richCard(it) }
                if (r == null) {
                    attachment.text = "\uD83D\uDCAC  " + ctx.getString(R.string.rich_card)
                    return
                }
                val lines = ArrayList<String>()
                r.cards.forEachIndexed { i, c ->
                    if (i > 0) lines.add("\u2014")
                    if (c.title.isNotBlank()) lines.add(c.title)
                    if (c.description.isNotBlank()) lines.add(c.description)
                    c.suggestions.forEach { lines.add("\u203A ${it.text}") }
                }
                r.suggestions.forEach { lines.add("\u203A ${it.text}") }
                attachment.text = lines.joinToString("\n")
                val all = r.cards.flatMap { it.suggestions } + r.suggestions
                attachment.setOnClickListener { v ->
                    if (selecting || all.isEmpty()) return@setOnClickListener
                    val menu = android.widget.PopupMenu(v.context, v)
                    all.forEachIndexed { i, sug -> menu.menu.add(0, i, i, sug.text) }
                    menu.setOnMenuItemClickListener { mi ->
                        val sug = all[mi.itemId]
                        onMessageAction(item, if (sug.href.isNotBlank()) "open_url:${sug.href}" else "reply_text:${sug.text}")
                        true
                    }
                    menu.show()
                }
            }
            RichContent.Kind.AUDIO -> {
                hideGenericBody()
                val mine = voiceId == item.messageId
                val progress = if (mine) voiceProgress else 0f
                val dur = if (mine && voiceDurationMs > 0) voiceDurationMs else voiceDurations[item.messageId] ?: 0
                attachment.text = voiceText(item.messageId, mine && voicePlaying, progress, dur)
                tap("voice")
            }
            RichContent.Kind.VIDEO -> {
                hideGenericBody()
                attachment.text = "\uD83C\uDFAC  " + ctx.getString(R.string.rich_video)
                tap("open_attachment")
            }
            else -> {
                val name = item.body.trim().takeIf { generic && it.isNotBlank() && !it.startsWith("[") }
                hideGenericBody()
                attachment.text = "\uD83D\uDCCE  " + (name ?: ctx.getString(R.string.rich_file))
                tap("open_attachment")
            }
        }
    }

    private fun voiceText(id: String, playing: Boolean, progress: Float, durationMs: Int): CharSequence {
        val bars = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587"
        var seed = id.hashCode().toLong() and 0xffffffffL
        val wave = StringBuilder()
        repeat(24) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            wave.append(bars[((seed shr 16) % bars.length).toInt()])
        }
        val played = (progress.coerceIn(0f, 1f) * wave.length).toInt()
        fun mmss(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
        val time = if (durationMs > 0) "  ${mmss((durationMs * progress).toInt())} / ${mmss(durationMs)}" else ""
        val head = if (playing) "\u23F8  " else "\u25B6\uFE0F  "
        val sb = android.text.SpannableStringBuilder(head).append(wave).append(time)
        if (played < wave.length) sb.setSpan(android.text.style.ForegroundColorSpan(0x66FFFFFF), head.length + played, head.length + wave.length, 0)
        return sb
    }

    private fun bindTapDetail(
        item: MessageDto,
        locked: Boolean,
        box: View,
        text: TextView,
        lock: ImageView,
    ) {
        val open = item.messageId == detailId
        box.isVisible = open
        if (!open) return
        text.text = displayReadBy(MessageStatus.detailLine(item), item.statusText)
        lock.isVisible = locked
        lock.imageTintList = ColorStateList.valueOf(box.context.getColor(R.color.muted))
    }

    private fun selectionStroke(density: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f * density
        setStroke((2 * density).toInt(), 0xFF3E8BFF.toInt())
    }

    private fun bindReveal(root: View, clock: String, locked: Boolean) {
        val time = root.findViewById<TextView>(R.id.reveal_clock) ?: return
        val icon = root.findViewById<ImageView>(R.id.reveal_lock) ?: return
        time.text = clock
        icon.isVisible = locked
        icon.imageTintList = ColorStateList.valueOf(root.context.getColor(R.color.muted))
    }

    /**
     * Letter avatar left of the first bubble in a group sender run.
     * Mid-run rows keep an invisible spacer so bubbles stay aligned.
     * Reply status does not affect visibility — only [MessageRuns.Row.showSender].
     */
    private fun bindSenderAvatar(row: MessageRuns.Row, avatar: TextView, photo: ImageView) {
        val item = row.message
        photo.visibility = View.GONE
        if (!conversation().isGroup || item.isFromMe) {
            avatar.visibility = View.GONE
            return
        }
        if (!row.showSender) {
            avatar.visibility = View.INVISIBLE
            avatar.text = ""
            return
        }
        // Match the sender to a group participant (number first, then name) so
        // we can load the same contact photo the chat list uses.
        val conv = conversation()
        val person = item.senderNumber.takeIf { it.any(Char::isDigit) }?.let { Participants.memberByName(conv, it) }
            ?: item.senderName.takeIf { it.isNotBlank() }?.let { Participants.memberByName(conv, it) }
        val display = person?.name?.ifBlank { null } ?: item.senderName.ifBlank { item.senderNumber }
        val seed = item.senderNumber.ifBlank { item.senderName }.ifBlank { "?" }
        // Initials from the name (not the phone number's leading "1").
        val label = if (display.any(Char::isLetter)) Formatters.initials(display).take(1) else "#"
        avatar.visibility = View.VISIBLE
        avatar.text = label
        val url = person?.let { Participants.personAvatarUrl(origin(), it) }
        if (!url.isNullOrBlank()) {
            CircleAvatars.clip(photo as ShapeableImageView)
            photo.visibility = View.VISIBLE
            photo.load(url) {
                circleAvatar(url)
                listener(onError = { _, _ -> photo.visibility = View.GONE })
            }
        }
        val bg = ContextCompat.getDrawable(avatar.context, R.drawable.bg_sender_avatar)?.mutate()
        bg?.setTint(Participants.phoneColor(person) ?: Participants.avatarColor(seed))
        avatar.background = bg
    }

    /** Attachment pixel size from the message, the URL cache, or a decoded memory bitmap. */
    private fun mediaDims(view: View, item: MessageDto): ImageAspects.Px? {
        val remote = when {
            item.localPreview.startsWith("http://") || item.localPreview.startsWith("https://") -> item.localPreview
            item.messageId.isNotBlank() && !item.messageId.startsWith("local-") -> mediaUrl(item)
            else -> null
        }
        if (remote != null) {
            knownOrMemory(view, remote, item.mediaWidth, item.mediaHeight)?.let { return it }
        }
        val local = item.localPreview
            .takeIf { it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") }
            ?.removePrefix("file://")
        if (local != null) {
            ImageAspects.resolve(local, item.mediaWidth, item.mediaHeight)?.let { return it }
            LocalImageBounds.of(local)?.also { ImageAspects.remember(local, it.width, it.height) }?.let { return it }
        }
        return ImageAspects.known(item.mediaWidth, item.mediaHeight, null)
    }

    private fun knownOrMemory(view: View, url: String, dtoWidth: Int, dtoHeight: Int): ImageAspects.Px? {
        ImageAspects.resolve(url, dtoWidth, dtoHeight)?.let { return it }
        val bitmap = view.context.imageLoader.memoryCache
            ?.get(MemoryCache.Key(ImageCacheKeys.diskKey(url)))
            ?.bitmap
            ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        ImageAspects.remember(url, bitmap.width, bitmap.height)
        return ImageAspects.Px(bitmap.width, bitmap.height)
    }

    private fun rememberDecoded(key: String, drawable: Drawable) {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w > 0 && h > 0) ImageAspects.remember(key, w, h)
    }

    /**
     * Google Messages layout: photos and link cards run edge to edge across the bubble
     * (rounded by the bubble's own outline), with the caption/time below inside it.
     */
    private fun layoutEdgeToEdge(
        item: MessageDto,
        bubble: View,
        image: ImageView,
        body: TextView,
        linkCard: View,
        sender: TextView,
        emoji: Boolean,
    ) {
        val dm = bubble.resources.displayMetrics
        val d = dm.density
        val wide = ImageAspects.frameWidth(dm.widthPixels, d)
        val sticker = image.isVisible && !emoji && ImageAspects.isSticker(item.mimeType, item.body)
        val photo = image.isVisible && !emoji && !sticker && bubble.background != null
        val lp = image.layoutParams as LinearLayout.LayoutParams
        val pv = image as? BubblePhotoView
        val dims = if (image.isVisible && !emoji) mediaDims(image, item) else null
        // The bubble has no padding of its own: text rows carry it, so photos and link
        // cards sit flush with the bubble edges and are clipped to its corners.
        lp.marginStart = 0
        lp.marginEnd = 0
        lp.topMargin = 0
        lp.bottomMargin = 0
        if (sticker) {
            val max = (180 * d).toInt()
            val (w, h) = if (dims != null) {
                PhotoFit.fit(dims.width, dims.height, max, max, max)
            } else {
                max to max
            }
            lp.width = w
            lp.height = h
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.minimumHeight = 0
            pv?.photoMode = false
            pv?.reserveWidth = 0
            pv?.reserveHeight = 0
            pv?.caption = null
            if (image.drawable == null) image.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
        } else if (photo) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            image.scaleType = ImageView.ScaleType.FIT_XY
            image.minimumHeight = if (dims != null) 0 else (120 * d).toInt()
            pv?.photoMode = true
            pv?.reserveWidth = dims?.width ?: 0
            pv?.reserveHeight = dims?.height ?: 0
            pv?.maxPhotoWidth = wide
            pv?.maxPhotoHeight = minOf((400 * d).toInt(), (dm.heightPixels * 0.6f).toInt())
            pv?.caption = body
            if (image.drawable == null) image.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
        } else if (emoji && image.isVisible) {
            // Big animated emoji (Google Messages size ~64dp): a real square in the
            // layout, scaled to fit, with normal spacing so it never draws under
            // neighbouring messages.
            lp.width = (64 * d).toInt()
            lp.height = (64 * d).toInt()
            lp.topMargin = (4 * d).toInt()
            lp.bottomMargin = (4 * d).toInt()
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.minimumHeight = 0
            pv?.photoMode = false
            pv?.reserveWidth = 0
            pv?.reserveHeight = 0
            pv?.caption = null
            body.maxWidth = Int.MAX_VALUE
        } else {
            lp.width = (220 * d).toInt()
            lp.height = (160 * d).toInt()
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.minimumHeight = 0
            pv?.photoMode = false
            pv?.reserveWidth = 0
            pv?.reserveHeight = 0
            pv?.caption = null
            body.maxWidth = Int.MAX_VALUE
        }
        image.layoutParams = lp
        if (linkCard.isVisible) {
            val cp = linkCard.layoutParams as LinearLayout.LayoutParams
            cp.width = wide
            val quoteShown = bubble.findViewById<View>(R.id.reply_quote)?.isVisible == true
            val senderInside = sender.parent === bubble && sender.isVisible
            val first = !body.isVisible && !quoteShown && !senderInside && !image.isVisible
            cp.topMargin = if (first) 0 else (8 * d).toInt()
            cp.marginStart = 0
            cp.marginEnd = 0
            linkCard.layoutParams = cp
            if (!photo) body.maxWidth = wide
        } else if (!photo) {
            body.maxWidth = Int.MAX_VALUE
        }
        bubble.clipToOutline = true
    }

    /**
     * The quote sizes to its own content (name and text, wrapping to at most 3 lines only
     * when it is longer than the bubble can hold) and the bubble widens to fit it, so a
     * short reply such as "Oh nice" never squeezes the quote. The measure spec clamps
     * the minimum to the bubble's max width.
     */
    private fun sizeQuote(box: View, name: TextView, text: TextView) {
        val nameW = if (name.isVisible) name.paint.measureText(name.text.toString()) else 0f
        val textW = text.paint.measureText(text.text.toString().replace('\n', ' '))
        val pad = box.paddingLeft + box.paddingRight
        val quoteW = QuoteWidth.intrinsic(nameW, textW, pad)
        box.minimumWidth = quoteW
        // The bubble itself must take the quote's width: ConstraintLayout's "wrap" pass
        // measures the bubble from its text, so a short reply squeezed the quote. The
        // bubble's minimum width is clamped to the available width by the parent spec,
        // so long quotes fill the max bubble width and clamp their lines.
        val lp = box.layoutParams as? ViewGroup.MarginLayoutParams
        val bubble = box.parent as? View ?: return
        bubble.minimumWidth = quoteW + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
    }

    private fun bindQuote(root: View, item: MessageDto) {
        val box = root.findViewById<View>(R.id.reply_quote) ?: return
        val name = root.findViewById<TextView>(R.id.reply_quote_name)
        val text = root.findViewById<TextView>(R.id.reply_quote_text)
        val replyId = item.replyToId
        if (replyId.isBlank()) {
            (box.parent as? View)?.minimumWidth = 0
            box.minimumWidth = 0
            box.isVisible = false
            box.setOnClickListener(null)
            box.isClickable = false
            return
        }
        box.isVisible = true
        val original = messageById(replyId)
        if (original == null) {
            name.isVisible = false
            text.text = ReplyTexts.MISSING
            text.setTypeface(null, Typeface.ITALIC)
            sizeQuote(box, name, text)
            // Not loaded yet: tapping pages older history to find it.
            box.isClickable = true
            box.setOnClickListener { onQuote(replyId) }
            return
        }
        val conv = conversation()
        name.isVisible = true
        name.text = formatPhoneForDisplay(ReplyTexts.author(original, conv.name, conv.isGroup))
        text.setTypeface(null, Typeface.NORMAL)
        text.text = ReplyTexts.quoteText(ReplyTexts.snippetSource(original))
        sizeQuote(box, name, text)
        box.isClickable = true
        box.setOnClickListener { onQuote(replyId) }
    }

    private fun bindLink(
        text: String,
        card: View,
        image: ImageView,
        site: TextView,
        title: TextView,
        desc: TextView,
    ) {
        val url = LinkUrls.firstHttpUrl(text)
        if (url == null) {
            card.isVisible = false
            return
        }
        requestPreview(url)
        val preview = previewOf(url)
        if (preview == null) {
            card.isVisible = false
            return
        }
        card.isVisible = true
        card.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url.ifBlank { url }))
            runCatching { card.context.startActivity(intent) }
                .onFailure { Toast.makeText(card.context, R.string.link_open_failed, Toast.LENGTH_SHORT).show() }
        }
        (image.getTag(R.id.link_image) as? View.OnLayoutChangeListener)?.let {
            image.removeOnLayoutChangeListener(it)
        }
        image.setTag(R.id.link_image, null)
        val picture = imageOf(preview)
        if (picture.isNullOrBlank()) {
            image.isVisible = false
            image.setImageDrawable(null)
        } else {
            image.isVisible = true
            val dm = image.resources.displayMetrics
            val cap = minOf((400 * dm.density).toInt(), (dm.heightPixels * 0.6f).toInt())
            val minPx = (60 * dm.density).toInt()
            val frame = ImageAspects.frameWidth(dm.widthPixels, dm.density)
            if (preview.imageWidth > 0 && preview.imageHeight > 0) {
                ImageAspects.remember(picture, preview.imageWidth, preview.imageHeight)
            }
            val known = knownOrMemory(image, picture, preview.imageWidth, preview.imageHeight)
            // Videos stay 16:9 so a 4:3 thumbnail's baked-in bars fall outside the frame.
            val height = when {
                preview.isVideo -> ImageAspects.linkHeight(frame, 16, 9, true, minPx, cap)
                known != null -> ImageAspects.linkHeight(frame, known.width, known.height, false, minPx, cap)
                else -> null
            }
            image.adjustViewBounds = false
            image.foreground = if (preview.isVideo) ContextCompat.getDrawable(image.context, R.drawable.fg_link_play) else null
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.maxHeight = Int.MAX_VALUE
            image.minimumHeight = 0
            val ilp = image.layoutParams
            ilp.width = ViewGroup.LayoutParams.MATCH_PARENT
            ilp.height = height ?: (160 * dm.density).toInt().coerceIn(minPx, cap)
            image.layoutParams = ilp
            if (image.drawable == null) image.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
            image.load(picture) {
                cachedMedia(picture)
                listener(onSuccess = { _, result ->
                    val w = result.drawable.intrinsicWidth
                    val h = result.drawable.intrinsicHeight
                    if (w > 0 && h > 0) ImageAspects.remember(picture, w, h)
                    if (height == null && !preview.isVideo && w > 0 && h > 0) {
                        val next = ImageAspects.linkHeight(frame, w, h, false, minPx, cap)
                        if (next != null && ilp.height != next) {
                            ilp.height = next
                            image.layoutParams = ilp
                        }
                    }
                })
            }
        }
        val siteLabel = preview.footerLabel()
        site.isVisible = siteLabel.isNotBlank()
        site.text = siteLabel
        val icon = (card as? ViewGroup)?.findViewById<ImageView>(R.id.link_icon)
        if (icon != null) {
            val iconSrc = if (preview.iconUrl.isBlank()) null else imageOf(preview.copy(imageUrl = preview.iconUrl))
            if (iconSrc.isNullOrBlank() || siteLabel.isBlank()) {
                icon.isVisible = false
                icon.setImageDrawable(null)
            } else {
                icon.isVisible = true
                icon.load(iconSrc) {
                    stableCacheKey(iconSrc)
                    listener(onError = { _, _ -> icon.isVisible = false })
                }
            }
        }
        title.isVisible = preview.title.isNotBlank()
        title.text = preview.title
        desc.isVisible = preview.description.isNotBlank()
        desc.text = preview.description
    }

    private fun bindReceipt(
        row: MessageRuns.Row,
        kind: ReceiptKind?,
        receipt: View,
        avatars: LinearLayout,
        label: TextView,
        icon: ImageView,
        look: ThreadChrome,
    ) {
        avatars.removeAllViews()
        if (!row.showReceipt || kind == null) {
            receipt.isVisible = false
            return
        }
        receipt.isVisible = true
        val conv = conversation()
        val names = if (conv.isGroup && kind != ReceiptKind.FAILED && kind != ReceiptKind.SENDING) {
            MessageStatus.readByNames(row.message.statusText)?.let { ReadReceipts.dedupe(it) }
        } else {
            null
        }
        val others = if (conv.isGroup) Participants.otherMembers(conv).size else 0
        if (names != null && names.isNotEmpty() && !(others > 0 && names.size >= others)) {
            icon.isVisible = false
            label.isVisible = true
            label.text = displayReadBy(row.message.statusText.trim())
            label.setTextColor(look.colors.light)
            val density = avatars.resources.displayMetrics.density
            // 20dp faces inside a 1.5dp ring matching the background, slightly overlapped.
            val ring = (1.5f * density).toInt().coerceAtLeast(1)
            val size = (20 * density).toInt() + ring * 2
            val overlap = (6 * density).toInt()
            names.take(4).forEachIndexed { index, name ->
                val person = Participants.memberByName(conv, name)
                avatars.addView(readByChip(avatars, person, name, size, ring, if (index == 0) 0 else -overlap))
            }
            return
        }
        label.isVisible = false
        if (kind == ReceiptKind.READ && !conv.isGroup) {
            // 1:1: one face (photo, or initials while/if it fails) instead of the generic dot pair.
            val other = Participants.otherMembers(conv).firstOrNull()
            val name = other?.name?.ifBlank { other.number }?.ifBlank { conv.name }.orEmpty().ifBlank { conv.name }
            if (name.isNotBlank()) {
                icon.isVisible = false
                val density = avatars.resources.displayMetrics.density
                val ring = (1.5f * density).toInt().coerceAtLeast(1)
                avatars.addView(readByChip(avatars, other, name, (20 * density).toInt() + ring * 2, ring, 0))
                avatars.contentDescription = displayReadBy(row.message.statusText.ifBlank { kind.label })
                return
            }
        }
        icon.isVisible = true
        icon.setImageResource(kind.icon())
        val tint = if (kind == ReceiptKind.READ) look.colors.accent else kind.tint(icon.context)
        icon.imageTintList = ColorStateList.valueOf(tint)
        icon.contentDescription = displayReadBy(row.message.statusText.ifBlank { kind.label })
    }

    private fun applyMentionChips(view: TextView, sb: SpannableStringBuilder, ranges: List<IntRange>, color: Int) {
        val density = view.resources.displayMetrics.density
        for (r in ranges) {
            if (r.last >= sb.length) continue
            sb.setSpan(MentionChipSpan(0x33FFFFFF, color, density), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun linkify(view: TextView, text: String, linkColor: Int) {
        val loc = SharedLocation.parse(text)
        if (loc != null) {
            // Location card: pin, place text, opens Maps on tap.
            val head = "\uD83D\uDCCD " + view.context.getString(R.string.shared_location)
            val sb = SpannableStringBuilder(head)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (loc.label.isNotBlank()) sb.append("\n").append(loc.label)
            val tail = "\n" + view.context.getString(R.string.open_in_maps)
            val start = sb.length
            sb.append(tail)
            sb.setSpan(android.text.style.UnderlineSpan(), start + 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.text = sb
            view.movementMethod = null
            view.setOnClickListener {
                runCatching { it.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loc.url))) }
                    .onFailure { _ -> Toast.makeText(it.context, R.string.link_open_failed, Toast.LENGTH_SHORT).show() }
            }
            return
        }
        view.setOnClickListener(null)
        view.isClickable = false
        val parts = LinkUrls.parts(text)
        val mentions = Mentions.ranges(text)
        if (parts.none { it.url != null }) {
            if (mentions.isEmpty()) {
                view.text = text
            } else {
                view.text = SpannableStringBuilder(text).also { applyMentionChips(view, it, mentions, linkColor) }
            }
            view.movementMethod = null
            return
        }
        val spanned = SpannableStringBuilder()
        for (part in parts) {
            val start = spanned.length
            spanned.append(part.text)
            val url = part.url ?: continue
            spanned.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        runCatching { widget.context.startActivity(intent) }
                            .onFailure {
                                Toast.makeText(widget.context, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
                            }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = true
                    }
                },
                start,
                spanned.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (spanned.toString() == text) applyMentionChips(view, spanned, mentions, linkColor)
        view.text = spanned
        view.movementMethod = LinkMovementMethod.getInstance()
        view.highlightColor = Color.TRANSPARENT
    }

    private fun bubbleDrawable(density: Float, fromMe: Boolean, pos: MessageRuns.Pos, color: Int): GradientDrawable {
        val radius = 20f * density
        val tail = 4f * density
        val radii = FloatArray(8) { radius }
        fun tailTop() {
            if (fromMe) {
                radii[2] = tail
                radii[3] = tail
            } else {
                radii[0] = tail
                radii[1] = tail
            }
        }
        fun tailBottom() {
            if (fromMe) {
                radii[4] = tail
                radii[5] = tail
            } else {
                radii[6] = tail
                radii[7] = tail
            }
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
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(color)
        }
    }

    private fun displayReadBy(text: String, nameSource: String = text): String {
        val names = MessageStatus.readByNames(nameSource)
        if (names.isNullOrEmpty()) return formatPhoneForDisplay(text)
        return formatPhonesInText(text, names)
    }

    private fun readByChip(
        parent: LinearLayout,
        person: ParticipantDto?,
        name: String,
        size: Int,
        ring: Int,
        marginStart: Int,
    ): View {
        val seed = person?.number?.ifBlank { person.phone }?.ifBlank { name } ?: name
        val display = person?.name?.ifBlank { name } ?: name
        val chip = FrameLayout(parent.context)
        chip.layoutParams = LinearLayout.LayoutParams(size, size).apply { this.marginStart = marginStart }
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(chrome().colors.surface)
        }
        val inner = size - ring * 2
        val initials = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(inner, inner).apply {
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            text = Formatters.initials(display)
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Participants.avatarColor(seed))
            }
        }
        chip.addView(initials)
        val url = person?.let { Participants.personAvatarUrl(origin(), it) }
        if (!url.isNullOrBlank()) {
            val photo = ShapeableImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(inner, inner).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            CircleAvatars.clip(photo)
            chip.addView(photo)
            photo.load(url) {
                circleAvatar(url)
                listener(onError = { _, _ -> photo.isVisible = false })
            }
        }
        return chip
    }

    companion object {
        private val MMS_DOWNLOAD = Regex("INCOMING_YET_TO_MANUAL_DOWNLOAD|INCOMING_DOWNLOAD_FAILED|INCOMING_DOWNLOAD_CANCELED|INCOMING_EXPIRED_OR_NOT_AVAILABLE")
        private const val TYPE_ME = 1
        private const val TYPE_THEM = 2
        private const val TYPE_SYSTEM = 3
    }
}

data class ThreadChrome(
    val colors: ThemeColors = ThemeColors.Default,
    val encryption: ConversationEncryption = ConversationEncryption(),
    val displayProtocol: String = "",
)
