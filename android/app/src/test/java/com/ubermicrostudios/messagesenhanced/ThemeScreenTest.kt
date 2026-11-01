package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeScreenTest {
    private fun fields(
        palette: String = "",
        color: String = "",
        wallpaper: String = "",
        custom: String = "",
        pending: String = "",
    ) = ThemeScreen.ThemeFields(palette, color, wallpaper, custom, pending)

    @Test
    fun keyBlanksColorWhenAPaletteIsSet() {
        assertEquals("blue||wall|cust|pend", ThemeScreen.key(fields("blue", "#abcdef", "wall", "cust", "pend")))
        assertEquals("|#abcdef|wall||", ThemeScreen.key(fields(color = "#abcdef", wallpaper = "wall")))
        assertEquals("||||", ThemeScreen.key(ThemeScreen.ThemeFields()))
    }

    @Test
    fun titlesAndPreviewCopyMatchTheWebPanel() {
        assertEquals(ThemeScreen.TITLE, ThemeScreen.title(false, "Animals"))
        assertEquals("Animals", ThemeScreen.title(true, "Animals"))
        assertEquals(
            "Ada Quinn \u00B7 Shows on every device you use Messages on",
            ThemeScreen.subtitle(false, "Ada Quinn"),
        )
        assertEquals(ThemeScreen.DEVICE_LINE, ThemeScreen.subtitle(false, "  "))
        assertEquals(ThemeScreen.CATEGORY_HINT, ThemeScreen.subtitle(true, "Ada Quinn"))
        assertEquals("Alex", ThemeScreen.previewSender(true))
        assertEquals("", ThemeScreen.previewSender(false))
        assertEquals("Want to grab dinner after work?", ThemeScreen.PREVIEW_THEM)
        assertEquals("Yes, that sounds great!", ThemeScreen.PREVIEW_ME)
        assertEquals("4:35 PM", ThemeScreen.PREVIEW_TIME)
        assertEquals("Message", ThemeScreen.PREVIEW_HINT)
        assertEquals("Chat theme saved", ThemeScreen.SAVED)
    }

    @Test
    fun applyAndResetFollowDirtyAndCategoryChanges() {
        val saved = ThemeScreen.ThemeFields()
        val draft = fields(wallpaper = "a")
        assertFalse(ThemeScreen.applyEnabled(false, false, saved, saved, saved))
        assertTrue(ThemeScreen.applyEnabled(false, false, draft, saved, saved))
        assertEquals("Apply", ThemeScreen.applyLabel(false, false))
        assertFalse(ThemeScreen.applyEnabled(true, false, draft, saved, draft))
        assertTrue(ThemeScreen.applyEnabled(true, false, fields(wallpaper = "b"), saved, draft))
        assertEquals("Next", ThemeScreen.applyLabel(true, false))
        assertFalse(ThemeScreen.applyEnabled(true, true, fields(wallpaper = "b"), saved, draft))
        assertEquals("Saving\u2026", ThemeScreen.applyLabel(false, true))
        assertFalse(ThemeScreen.resetEnabled(false, saved))
        assertTrue(ThemeScreen.resetEnabled(false, draft))
        assertFalse(ThemeScreen.resetEnabled(true, draft))
    }

    @Test
    fun categoryBackRestoresAndNextKeepsThePick() {
        val before = fields(wallpaper = "old", custom = "tok")
        val picked = ThemeScreen.selectWallpaper(before, "new")
        assertEquals("", picked.custom)
        assertEquals("", picked.pendingPath)
        assertEquals(before, ThemeScreen.finishCategory(picked, before, keepPick = false))
        assertEquals(picked, ThemeScreen.finishCategory(picked, before, keepPick = true))
    }

    @Test
    fun paletteSelectionClearsColorAndWallpaperSelectionClearsThePhoto() {
        val custom = fields(color = "#112233", wallpaper = "w", custom = "tok")
        val palette = ThemeScreen.selectPalette(custom, "slate")
        assertEquals("slate", palette.palette)
        assertEquals("", palette.color)
        assertEquals("w", palette.wallpaper)
        assertNull(ThemeScreen.customSwatchHex(palette))
        assertTrue(ThemeScreen.paletteSwatchSelected(palette, "slate"))
        assertFalse(ThemeScreen.defaultSwatchSelected(palette))

        val staged = ThemeScreen.stagePhoto(palette, "/tmp/p", "image/jpeg", "p.jpg")
        assertEquals("", staged.wallpaper)
        assertEquals("", staged.custom)
        assertEquals("/tmp/p", staged.pendingPath)
        val cleared = ThemeScreen.selectDefault(staged)
        assertTrue(ThemeScreen.defaultSwatchSelected(cleared))
        assertEquals("/tmp/p", cleared.pendingPath)
    }

    @Test
    fun customHexSwatchIsSelectedInsteadOfDefault() {
        val draft = fields(color = "#AaBbCc")
        assertEquals("#AaBbCc", ThemeScreen.customSwatchHex(draft))
        assertFalse(ThemeScreen.defaultSwatchSelected(draft))
        assertNull(ThemeScreen.customSwatchHex(fields(palette = "blue", color = "#aabbcc")))
        assertNull(ThemeScreen.customSwatchHex(fields(color = "blue")))
    }

    @Test
    fun removeLabelsAndResetClearOnlyTheDraft() {
        assertFalse(ThemeScreen.removeVisible(ThemeScreen.ThemeFields()))
        assertEquals("Remove photo", ThemeScreen.removeLabel(fields(custom = "v")))
        assertEquals("Remove photo", ThemeScreen.removeLabel(fields(pending = "/p")))
        assertEquals("Remove wallpaper", ThemeScreen.removeLabel(fields(wallpaper = "w")))
        assertTrue(ThemeScreen.removeVisible(fields(wallpaper = "w")))
        val removed = ThemeScreen.removePhoto(fields(palette = "gold", wallpaper = "w", custom = "v", pending = "/p"))
        assertEquals("gold", removed.palette)
        assertEquals("", removed.wallpaper)
        assertEquals("", removed.custom)
        assertEquals("", removed.pendingPath)
        assertEquals(ThemeScreen.ThemeFields(), ThemeScreen.resetDraft())
    }

    @Test
    fun photoRulesMatchTheWebClientChecks() {
        assertNull(ThemeScreen.photoError(ThemeScreen.MAX_UPLOAD_BYTES.toLong(), "image/jpeg"))
        assertEquals(
            ThemeScreen.PHOTO_TOO_LARGE,
            ThemeScreen.photoError(ThemeScreen.MAX_UPLOAD_BYTES.toLong() + 1, "image/png"),
        )
        assertNull(ThemeScreen.photoError(10, ""))
        assertNull(ThemeScreen.photoError(10, "image/WEBP"))
        assertNull(ThemeScreen.photoError(-1, "image/gif"))
        assertEquals(ThemeScreen.PHOTO_TYPE, ThemeScreen.photoError(10, "image/bmp"))
        assertEquals(ThemeScreen.PHOTO_TYPE, ThemeScreen.photoError(10, "image/jpg"))
        assertEquals("a.jpg", ThemeScreen.uploadName("/tmp/a.jpg"))
        assertEquals("photo.jpg", ThemeScreen.uploadName(" \n "))
        assertEquals("badname", ThemeScreen.uploadName("bad\"name"))
    }

    @Test
    fun applyPlanUploadsAPendingPhotoAndDeletesAnEmptyDraft() {
        val pending = ThemeScreen.stagePhoto(
            fields(palette = "blue", wallpaper = "w", custom = "tok"),
            "/p",
            "image/jpeg",
            "a.jpg",
        )
        val upload = ThemeScreen.applyPlan(pending) as ThemeScreen.ApplyPlan.Upload
        assertEquals("blue", upload.palette)
        assertEquals("", upload.color)
        assertTrue(ThemeScreen.applyPlan(ThemeScreen.ThemeFields()) is ThemeScreen.ApplyPlan.Delete)
        val kept = ThemeScreen.applyPlan(fields(custom = "v1", palette = "rose")) as ThemeScreen.ApplyPlan.Put
        assertTrue(kept.custom)
        assertEquals("", kept.wallpaper)
        assertEquals("rose", kept.palette)
        val wall = ThemeScreen.applyPlan(
            ThemeScreen.selectWallpaper(fields(custom = "v", pending = "/p"), "animals-1"),
        ) as ThemeScreen.ApplyPlan.Put
        assertEquals("animals-1", wall.wallpaper)
        assertFalse(wall.custom)
    }

    @Test
    fun creditsCategorySelectionAndSaveToast() {
        assertEquals("Photo: Moon \u00B7 Ada \u00B7 CC0", ThemeScreen.credit("Moon", "Ada", "CC0", true))
        assertEquals("", ThemeScreen.credit("Moon", "Ada", "CC0", false))
        assertEquals("Moon (2 of 9)", ThemeScreen.photoContentDescription("Moon", 2, 9))
        assertTrue(ThemeScreen.categorySelected(listOf("a", "b"), "b"))
        assertFalse(ThemeScreen.categorySelected(listOf("a"), ""))
        assertFalse(ThemeScreen.categorySelected(listOf("a"), "z"))
        assertNull(ThemeScreen.saveToast("login required"))
        assertEquals("Couldn't save the theme: image is too large (limit 15 MB)", ThemeScreen.saveToast("image is too large (limit 15 MB)"))
        assertEquals("Couldn't load wallpapers: offline", ThemeScreen.wallpaperError("offline"))
        assertEquals("Loading wallpapers\u2026", ThemeScreen.WALLPAPER_LOADING)
    }

    @Test
    fun swatchGeometryAndColumns() {
        assertEquals(180f, ThemeScreen.SWATCH_ROTATION_DEGREES)
        assertEquals(
            listOf(
                ThemeScreen.SwatchSegment("light", 0f, 0.5f),
                ThemeScreen.SwatchSegment("them", 0.5f, 0.75f),
                ThemeScreen.SwatchSegment("me", 0.75f, 1f),
            ),
            ThemeScreen.swatchSegments(),
        )
        assertEquals(3, ThemeScreen.columnCount(0, 100))
        assertEquals(3, ThemeScreen.columnCount(250, 100))
        assertEquals(6, ThemeScreen.columnCount(900, 100))
        assertEquals("slate", ThemeScreen.fromDto(ChatThemeDto(palette = "slate", color = "#112233")).palette)
        assertNull(ThemeScreen.panelSurface(ThemeScreen.ThemeFields()))
        assertEquals(0xFF1E2227.toInt(), ThemeScreen.panelSurface(fields(palette = "slate")))
    }
}
