package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto

/**
 * A message that is one photo or GIF and no caption. Stickers already draw
 * without a bubble. A caption, or more than one image, keeps the bubble.
 */
object BareImages {
    fun solo(message: MessageDto, imageCount: Int = 1): Boolean {
        if (imageCount != 1) return false
        if (!message.isImageMedia()) return false
        if (ImageAspects.isSticker(message.mimeType, message.body)) return false
        return ReplyTexts.imageCaption(message).isEmpty()
    }
}
