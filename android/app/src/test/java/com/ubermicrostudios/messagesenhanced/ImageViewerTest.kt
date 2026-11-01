package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.ImageStore
import com.ubermicrostudios.messagesenhanced.util.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerTest {

    @Test
    fun openSource_serverPhotoUsesTheMediaUrl() {
        val message = MessageDto(messageId = "m1", mimeType = "image/jpeg")
        val source = ImageStore.openSource(message, "https://messages.example/api/media/m1")
        assertEquals(ImageStore.Source.Remote("https://messages.example/api/media/m1"), source)
    }

    @Test
    fun openSource_pendingSendUsesTheLocalFile() {
        val message = MessageDto(
            messageId = "local-1",
            mimeType = "image/png",
            localPreview = "/cache/pending/a.png",
        )
        assertEquals(
            ImageStore.Source.Local("/cache/pending/a.png"),
            ImageStore.openSource(message, "https://messages.example/api/media/local-1"),
        )
        val prefixed = message.copy(localPreview = "file:///cache/pending/a.png")
        assertEquals(
            ImageStore.Source.Local("/cache/pending/a.png"),
            ImageStore.openSource(prefixed, "https://messages.example/api/media/local-1"),
        )
    }

    @Test
    fun openSource_httpPreviewAndNonImagesStayPut() {
        val preview = MessageDto(messageId = "local-2", mimeType = "image/gif", localPreview = "https://cdn.example/a.gif")
        assertEquals(
            ImageStore.Source.Remote("https://cdn.example/a.gif"),
            ImageStore.openSource(preview, "https://messages.example/api/media/local-2"),
        )
        assertNull(ImageStore.openSource(MessageDto(messageId = "m", mimeType = "video/mp4"), "https://x/api/media/m"))
        assertNull(ImageStore.openSource(MessageDto(messageId = "local-3", mimeType = "image/jpeg"), "https://x/api/media/local-3"))
        assertNull(ImageStore.openSource(MessageDto(mimeType = "image/jpeg"), ""))
    }

    @Test
    fun fileName_sanitizesAndKeepsTheImageExtension() {
        assertEquals("jpg", ImageStore.extension("image/jpeg; charset=binary"))
        assertEquals("png", ImageStore.extension("IMAGE/PNG"))
        assertEquals("gif", ImageStore.extension("image/gif"))
        assertEquals("webp", ImageStore.extension("image/webp"))
        assertEquals("heic", ImageStore.extension("image/heif"))
        assertEquals("svg", ImageStore.extension("image/svg+xml"))
        assertEquals("jpg", ImageStore.extension(""))
        assertEquals("abcd-5.jpg", ImageStore.fileName("ab/c d", "image/jpeg", 5L))
        assertEquals("photo-9.png", ImageStore.fileName("///", "image/png", 9L))
        assertEquals("Pictures/${ImageStore.ALBUM}", ImageStore.RELATIVE_PATH)
        assertEquals("share", ImageStore.SHARE_DIR)
    }

    @Test
    fun fitScale_letterboxesAndRejectsEmptySizes() {
        assertEquals(1f, ZoomMath.fitScale(0f, 10f, 10f, 10f), 0.001f)
        assertEquals(2f, ZoomMath.fitScale(100f, 200f, 50f, 50f), 0.001f)
        assertEquals(0.25f, ZoomMath.fitScale(100f, 100f, 400f, 200f), 0.001f)
    }

    @Test
    fun clampAndDoubleTap_toggleBetweenFitAndTheMiddleStop() {
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.clampScale(0.2f), 0.001f)
        assertEquals(2f, ZoomMath.clampScale(2f), 0.001f)
        assertEquals(ZoomMath.MAX_SCALE, ZoomMath.clampScale(9f), 0.001f)
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, ZoomMath.doubleTapTarget(1f), 0.001f)
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, ZoomMath.doubleTapTarget(2.44f), 0.001f)
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.doubleTapTarget(2.45f), 0.001f)
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.doubleTapTarget(ZoomMath.MAX_SCALE), 0.001f)
    }

    @Test
    fun panCorrection_centersASmallImageAndPullsAnOverflowBack() {
        val centered = ZoomMath.panCorrection(10f, 20f, 50f, 80f, 100f, 100f)
        assertEquals(20f, centered.first, 0.001f)
        assertEquals(0f, centered.second, 0.001f)

        val gapOnLeft = ZoomMath.panCorrection(10f, 0f, 130f, 100f, 100f, 100f)
        assertEquals(-10f, gapOnLeft.first, 0.001f)
        val gapOnRight = ZoomMath.panCorrection(-30f, 0f, 90f, 100f, 100f, 100f)
        assertEquals(10f, gapOnRight.first, 0.001f)
        val covered = ZoomMath.panCorrection(-10f, 0f, 110f, 100f, 100f, 100f)
        assertEquals(0f, covered.first, 0.001f)
        assertEquals(0f, covered.second, 0.001f)
    }

    @Test
    fun shouldDismiss_needsADownwardDragOrAFlick() {
        assertFalse(ZoomMath.shouldDismiss(0f, 1000f, 5000f))
        assertFalse(ZoomMath.shouldDismiss(-20f, 1000f, 2000f))
        assertFalse(ZoomMath.shouldDismiss(40f, 0f, 2000f))
        assertFalse(ZoomMath.shouldDismiss(219f, 1000f, 0f))
        assertTrue(ZoomMath.shouldDismiss(220f, 1000f, 0f))
        assertFalse(ZoomMath.shouldDismiss(80f, 1000f, 1599f))
        assertTrue(ZoomMath.shouldDismiss(80f, 1000f, 1600f))
        assertFalse(ZoomMath.shouldDismiss(79f, 1000f, 4000f))
    }
}
