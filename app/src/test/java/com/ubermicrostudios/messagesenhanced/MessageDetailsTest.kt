package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.MessageDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MessageDetailsTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val locale = Locale.US

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun whenLabel_todayYesterdayAndDate() {
        val now = at(2026, Calendar.OCTOBER, 22, 21, 30)
        assertEquals("Today, 9:05 PM", MessageDetails.whenLabel(at(2026, Calendar.OCTOBER, 22, 21, 5), now, zone, locale))
        assertEquals("Yesterday, 9:05 PM", MessageDetails.whenLabel(at(2026, Calendar.OCTOBER, 21, 21, 5), now, zone, locale))
        assertEquals("Oct 2, 9:05 PM", MessageDetails.whenLabel(at(2026, Calendar.OCTOBER, 2, 21, 5), now, zone, locale))
        assertEquals("Mar 3, 2024, 9:05 AM", MessageDetails.whenLabel(at(2024, Calendar.MARCH, 3, 9, 5), now, zone, locale))
        assertEquals("", MessageDetails.whenLabel(0L, now, zone, locale))
    }

    @Test
    fun statusRows_receivedForIncoming_sentForOutgoing_bothWhenKnown() {
        val now = at(2026, Calendar.OCTOBER, 22, 21, 30)
        val today = at(2026, Calendar.OCTOBER, 22, 21, 5)
        val incoming = MessageDetails.statusRows(fromMe = false, timestampMs = today, nowMs = now, zone = zone, locale = locale)
        assertEquals(listOf("Received" to "Today, 9:05 PM"), incoming.map { it.label to it.value })

        val outgoing = MessageDetails.statusRows(fromMe = true, timestampMs = today, nowMs = now, zone = zone, locale = locale)
        assertEquals(listOf("Sent" to "Today, 9:05 PM"), outgoing.map { it.label to it.value })

        val both = MessageDetails.statusRows(
            fromMe = true,
            timestampMs = 0L,
            nowMs = now,
            zone = zone,
            locale = locale,
            sentAtMs = today,
            receivedAtMs = at(2026, Calendar.OCTOBER, 22, 21, 6),
        )
        assertEquals(listOf("Received", "Sent"), both.map { it.label })
        assertTrue(MessageDetails.statusRows(fromMe = true, timestampMs = 0L, nowMs = now, zone = zone, locale = locale).isEmpty())
    }

    @Test
    fun transportLabel_rcsSmsAndMms_omitsUnknown() {
        assertEquals(
            "End-to-End Encrypted RCS message",
            MessageDetails.transportLabel("sms", "RCS", encrypted = true, hasMedia = true),
        )
        assertEquals(
            "End-to-End Encrypted RCS message",
            MessageDetails.transportLabel("sms", "RCS E2EE", encrypted = false, hasMedia = false),
        )
        assertEquals("RCS message", MessageDetails.transportLabel("sms", "RCS", encrypted = false, hasMedia = true))
        assertEquals("SMS", MessageDetails.transportLabel("sms", "SMS", encrypted = false, hasMedia = false))
        assertEquals("MMS", MessageDetails.transportLabel("sms", "SMS", encrypted = false, hasMedia = true))
        assertEquals("MMS", MessageDetails.transportLabel("mms", "", encrypted = false, hasMedia = false))
        assertNull(MessageDetails.transportLabel("whatsapp", "RCS", encrypted = false, hasMedia = false))
        assertNull(MessageDetails.transportLabel("", "", encrypted = false, hasMedia = true))
        assertNull(MessageDetails.transportLabel("sms", "SMS/RCS (unknown)", encrypted = true, hasMedia = true))
    }

    @Test
    fun mediaRows_omitSizeAndContentCredentialsTheApiDoesNotHave() {
        val message = MessageDto(messageId = "m", mimeType = "image/jpeg", mediaId = "m")
        assertNull(MessageDetails.mediaSizeBytes(message))
        assertNull(MessageDetails.contentCredentials(message))
        val omitted = MessageDetails.mediaRows(
            type = "MMS",
            sizeBytes = MessageDetails.mediaSizeBytes(message),
            contentCredentials = MessageDetails.contentCredentials(message),
            formatSize = { "$it B" },
        )
        assertEquals(listOf("Type" to "MMS"), omitted.map { it.label to it.value })

        val present = MessageDetails.mediaRows(
            type = "RCS message",
            sizeBytes = 12_000L,
            contentCredentials = "Signed by Camera",
            formatSize = { "12 KB" },
        )
        assertEquals(
            listOf(
                "Content Credentials" to "Signed by Camera",
                "Type" to "RCS message",
                "Size" to "12 KB",
            ),
            present.map { it.label to it.value },
        )
        assertTrue(
            MessageDetails.mediaRows(type = null, sizeBytes = null, contentCredentials = "  ", formatSize = { "x" }).isEmpty(),
        )
    }
}
