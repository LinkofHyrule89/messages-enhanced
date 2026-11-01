package com.ubermicrostudios.messagesenhanced.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RichContentTest {
    @Test fun kinds() {
        assertEquals(RichContent.Kind.VCARD, RichContent.kind("text/x-vcard"))
        assertEquals(RichContent.Kind.ICS, RichContent.kind("text/calendar"))
        assertEquals(RichContent.Kind.PUSH_LOCATION, RichContent.kind("application/vnd.gsma.rcspushlocation+xml"))
        assertEquals(RichContent.Kind.RICH_CARD, RichContent.kind("application/vnd.gsma.botmessage.v1.0+json"))
        assertEquals(RichContent.Kind.AUDIO, RichContent.kind("audio/amr"))
        assertEquals(RichContent.Kind.FILE, RichContent.kind("application/pdf"))
    }

    @Test fun vcard() {
        val c = RichContent.vcard("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Tester;Alex;;;\r\nTEL;TYPE=CELL:+1 555 0100\r\nEMAIL:alex@example.com\r\nEND:VCARD")
        assertEquals("Alex Tester", c.name)
        assertEquals(listOf("+1 555 0100"), c.phones)
        assertEquals(listOf("alex@example.com"), c.emails)
    }

    @Test fun ics() {
        val e = RichContent.ics("BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Team sync\nDTSTART:20260105T150000Z\nDTEND:20260105T160000Z\nLOCATION:Room 1\nEND:VEVENT\nEND:VCALENDAR", TimeZone.getTimeZone("UTC"))
        assertNotNull(e)
        assertEquals("Team sync", e!!.title)
        assertEquals(3_600_000L, e.endMs!! - e.startMs!!)
        assertEquals("Room 1", e.location)
        assertNull(RichContent.ics("hello"))
    }

    @Test fun pushLocation() {
        val i = RichContent.pushLocation("<rcsenvelope><rcspushlocation label=\"Cafe &amp; Co\"><geopriv><location-info><gml:Point><gml:pos>10.5 -20.25</gml:pos></gml:Point></location-info></geopriv></rcspushlocation></rcsenvelope>")
        assertEquals("https://maps.google.com/?q=10.5,-20.25", i!!.url)
        assertEquals("Cafe & Co", i.label)
        assertNull(RichContent.pushLocation("<x/>"))
    }

    @Test fun richCard() {
        val r = RichContent.richCard("""{"message":{"generalPurposeCardCarousel":{"content":[{"title":"A","description":"d","suggestions":[{"action":{"displayText":"Open","urlAction":{"openUrl":{"url":"https://example.com"}}}}]},{"title":"B"}]}},"suggestions":[{"reply":{"displayText":"Yes"}},{"action":{"displayText":"Bad","urlAction":{"openUrl":{"url":"javascript:x"}}}}]}""")
        assertNotNull(r)
        assertTrue(r!!.carousel)
        assertEquals(2, r.cards.size)
        assertEquals("https://example.com", r.cards[0].suggestions[0].href)
        assertTrue(r.suggestions[0].reply)
        assertEquals("", r.suggestions[1].href)
        assertNull(RichContent.richCard("not json"))
    }

    @Test fun otpAndPayment() {
        assertEquals("123456", RichContent.otp("Your verification code is 123456"))
        assertEquals("123456", RichContent.otp("G-123456 is your Google verification code"))
        assertEquals("", RichContent.otp("See you at 123456 Main"))
        assertEquals("", RichContent.otp("Your login was in 2026, verify nothing"))
        assertEquals("$25.00", RichContent.payment("You sent $25.00 to Alex"))
        assertEquals("", RichContent.payment("It costs $5"))
    }

    @Test fun notices() {
        assertTrue(RichContent.isNotice("MESSAGE_STATUS_TOMBSTONE_MISSED_VIDEO_CALL"))
        assertEquals("Missed video call", RichContent.notice("TOMBSTONE_MISSED_VIDEO_CALL", "")!!.text)
        assertEquals("Google text", RichContent.notice("TOMBSTONE_ACTIVE_SELF_IDENTITY_CHANGED", "Google text")!!.text)
        assertEquals("shield", RichContent.notice("MESSAGE_STATUS_TOMBSTONE_ACTIVE_SELF_IDENTITY_CHANGED", "")!!.icon)
    }

    @Test fun fileNames() {
        assertEquals("m-a_b.vcf", RichContent.fileName("a/b", "text/vcard", ""))
        assertEquals("m-x.pdf", RichContent.fileName("x", "application/octet-stream", "doc.pdf"))
    }
}
