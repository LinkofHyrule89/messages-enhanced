package com.ubermicrostudios.messagesenhanced.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Round initials avatar (chat-list colors) for notifications and share shortcuts when no photo loads. */
object InitialsBitmap {
    fun of(name: String, seed: String, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Participants.avatarColor(seed.ifBlank { name }) }
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, bg)
        val label = if (name.any { it.isLetter() }) Formatters.initials(name) else "#"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = px * 0.40f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = px / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, px / 2f, y, text)
        return out
    }
}
