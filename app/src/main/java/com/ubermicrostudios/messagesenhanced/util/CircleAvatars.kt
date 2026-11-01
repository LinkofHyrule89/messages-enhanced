package com.ubermicrostudios.messagesenhanced.util

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel

/** Clip an avatar view to a circle, including duo collage photos. */
object CircleAvatars {
    private val oval = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width.coerceAtLeast(0)
            val h = view.height.coerceAtLeast(0)
            outline.setOval(0, 0, w, h)
        }
    }

    private val circleShape: ShapeAppearanceModel by lazy {
        ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
    }

    fun clip(view: View) {
        if (view is ShapeableImageView) {
            view.shapeAppearanceModel = circleShape
        }
        view.outlineProvider = oval
        view.clipToOutline = true
    }
}
