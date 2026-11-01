package com.ubermicrostudios.messagesenhanced.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/** Circular bitmap for notification Person / large icons. */
object CircleBitmaps {
    fun of(source: Bitmap): Bitmap {
        val size = min(source.width, source.height).coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dest = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(dest, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        canvas.drawBitmap(
            source,
            Rect(left, top, left + size, top + size),
            dest,
            paint,
        )
        return out
    }
}
