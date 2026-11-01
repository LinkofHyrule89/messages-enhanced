package com.ubermicrostudios.messagesenhanced.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLocationTest {
    @Test fun mapsLink() {
        val i = SharedLocation.parse("Example Park\nhttps://maps.google.com/?q=Example+Park")!!
        assertEquals("https://maps.google.com/?q=Example+Park", i.url)
        assertEquals("Example Park", i.label)
    }
    @Test fun plainTextIsNotLocation() = assertNull(SharedLocation.parse("see https://example.com"))
}
