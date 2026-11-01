package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy
import coil.size.Size
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.databinding.ActivityMessageDetailsBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemDetailRowBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.util.ImageStore
import com.ubermicrostudios.messagesenhanced.util.MessageDetails
import com.ubermicrostudios.messagesenhanced.util.ReplyTexts
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Full-screen message details: bubble preview, sent/received time, and media facts the API actually has.
 * Content credentials and file size are omitted because the message payload does not carry them.
 */
@AndroidEntryPoint
class MessageDetailsActivity : SessionActivity() {

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var baseUrl: BaseUrlProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        val binding = ActivityMessageDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.detailsRoot, includeIme = false)
        observeAuthLoss()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationContentDescription = getString(R.string.back)

        val message = messageFrom(intent)
        if (message.messageId.isBlank() && message.body.isBlank() && message.mimeType.isBlank()) {
            finish()
            return
        }
        bindPreview(binding, message)
        bindRows(binding.statusCard, statusRows(message))
        binding.statusHeading.visibility = binding.statusCard.childCount.takeIf { it > 0 }?.let { View.VISIBLE } ?: View.GONE
        binding.statusCard.visibility = binding.statusHeading.visibility
        val media = mediaRows(message)
        bindRows(binding.mediaCard, media)
        val mediaVisible = if (media.isNotEmpty()) View.VISIBLE else View.GONE
        binding.mediaHeading.visibility = mediaVisible
        binding.mediaCard.visibility = mediaVisible
    }

    private fun bindPreview(binding: ActivityMessageDetailsBinding, message: MessageDto) {
        binding.previewBubble.setBackgroundResource(
            if (message.isFromMe) R.drawable.bg_bubble_me else R.drawable.bg_bubble_them,
        )
        val params = binding.previewBubble.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (message.isFromMe) Gravity.END else Gravity.START
        binding.previewBubble.layoutParams = params

        val radius = 12f * resources.displayMetrics.density
        binding.previewImage.clipToOutline = true
        binding.previewImage.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        val source = ImageStore.openSource(message, baseUrl.mediaUrl(message.messageId))
        if (source != null) {
            binding.previewImage.visibility = View.VISIBLE
            val data: Any = when (source) {
                is ImageStore.Source.Local -> File(source.path)
                is ImageStore.Source.Remote -> source.url
            }
            binding.previewImage.load(data, imageLoader) {
                size(Size.ORIGINAL)
                diskCachePolicy(CachePolicy.ENABLED)
            }
            val caption = ReplyTexts.imageCaption(message)
            binding.previewCaption.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
            binding.previewCaption.text = caption
        } else {
            binding.previewImage.visibility = View.GONE
            val text = message.body.trim()
            binding.previewCaption.setPadding(0, 0, 0, 0)
            binding.previewCaption.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            binding.previewCaption.text = text
        }
    }

    private fun statusRows(message: MessageDto): List<MessageDetails.Row> =
        MessageDetails.statusRows(
            fromMe = message.isFromMe,
            timestampMs = message.timestampMs,
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            locale = Locale.getDefault(),
        )

    private fun mediaRows(message: MessageDto): List<MessageDetails.Row> {
        val protocol = intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()
        val platform = message.sourcePlatform.ifBlank { intent.getStringExtra(EXTRA_CONV_PLATFORM).orEmpty() }
        val encrypted = intent.getBooleanExtra(EXTRA_ENCRYPTED, false)
        return MessageDetails.mediaRows(
            type = MessageDetails.transportLabel(
                sourcePlatform = platform,
                protocol = protocol,
                encrypted = encrypted,
                hasMedia = MessageDetails.hasTransportMedia(message),
            ),
            sizeBytes = MessageDetails.mediaSizeBytes(message),
            contentCredentials = MessageDetails.contentCredentials(message),
            formatSize = { bytes -> Formatter.formatShortFileSize(this, bytes) },
        )
    }

    private fun bindRows(card: LinearLayout, rows: List<MessageDetails.Row>) {
        card.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (row in rows) {
            val item = ItemDetailRowBinding.inflate(inflater, card, false)
            item.detailLabel.text = row.label
            item.detailValue.text = row.value
            card.addView(item.root)
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_CONV = "conv"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_TS = "ts"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_FROM_ME = "from_me"
        private const val EXTRA_MEDIA = "media"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_REPLY = "reply"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_STATUS_TEXT = "status_text"
        private const val EXTRA_LOCAL = "local"
        private const val EXTRA_PROTOCOL = "protocol"
        private const val EXTRA_ENCRYPTED = "encrypted"
        private const val EXTRA_CONV_PLATFORM = "conv_platform"

        fun open(
            context: Context,
            message: MessageDto,
            protocol: String,
            encrypted: Boolean,
            conversationPlatform: String,
        ) {
            val intent = Intent(context, MessageDetailsActivity::class.java).apply {
                putExtra(EXTRA_ID, message.messageId)
                putExtra(EXTRA_CONV, message.conversationId)
                putExtra(EXTRA_SENDER, message.senderName)
                putExtra(EXTRA_NUMBER, message.senderNumber)
                putExtra(EXTRA_BODY, message.body)
                putExtra(EXTRA_TS, message.timestampMs)
                putExtra(EXTRA_STATUS, message.status)
                putExtra(EXTRA_FROM_ME, message.isFromMe)
                putExtra(EXTRA_MEDIA, message.mediaId)
                putExtra(EXTRA_MIME, message.mimeType)
                putExtra(EXTRA_REPLY, message.replyToId)
                putExtra(EXTRA_PLATFORM, message.sourcePlatform)
                putExtra(EXTRA_STATUS_TEXT, message.statusText)
                putExtra(EXTRA_LOCAL, message.localPreview)
                putExtra(EXTRA_PROTOCOL, protocol)
                putExtra(EXTRA_ENCRYPTED, encrypted)
                putExtra(EXTRA_CONV_PLATFORM, conversationPlatform)
            }
            context.startActivity(intent)
        }

        private fun messageFrom(intent: Intent): MessageDto = MessageDto(
            messageId = intent.getStringExtra(EXTRA_ID).orEmpty(),
            conversationId = intent.getStringExtra(EXTRA_CONV).orEmpty(),
            senderName = intent.getStringExtra(EXTRA_SENDER).orEmpty(),
            senderNumber = intent.getStringExtra(EXTRA_NUMBER).orEmpty(),
            body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            timestampMs = intent.getLongExtra(EXTRA_TS, 0L),
            status = intent.getStringExtra(EXTRA_STATUS).orEmpty(),
            isFromMe = intent.getBooleanExtra(EXTRA_FROM_ME, false),
            mediaId = intent.getStringExtra(EXTRA_MEDIA).orEmpty(),
            mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty(),
            replyToId = intent.getStringExtra(EXTRA_REPLY).orEmpty(),
            sourcePlatform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty(),
            statusText = intent.getStringExtra(EXTRA_STATUS_TEXT).orEmpty(),
            localPreview = intent.getStringExtra(EXTRA_LOCAL).orEmpty(),
        )
    }
}
