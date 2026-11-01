package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.ui.effects.ScreenEffects
import com.ubermicrostudios.messagesenhanced.ui.effects.ScreenEffects.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenEffectsTest {
    @Test fun keywords() {
        assertEquals(Kind.CELEBRATE, ScreenEffects.detect("Happy birthday Sam!"))
        assertEquals(Kind.FIREWORKS, ScreenEffects.detect("happy new year!!"))
        assertEquals(Kind.LOVE, ScreenEffects.detect("love you"))
        assertEquals(Kind.GOODNIGHT, ScreenEffects.detect("Good night"))
        assertEquals(Kind.WINTER, ScreenEffects.detect("Merry Christmas"))
        assertEquals(Kind.THANKSGIVING, ScreenEffects.detect("Happy Thanksgiving"))
        assertEquals(Kind.LAUGH, ScreenEffects.detect("lol"))
        assertNull(ScreenEffects.detect("see you at 5"))
        assertNull(ScreenEffects.detect("lollipop"))
    }
}
