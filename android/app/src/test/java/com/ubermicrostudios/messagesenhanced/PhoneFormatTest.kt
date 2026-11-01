package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.displayCountryIso
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import com.ubermicrostudios.messagesenhanced.util.formatPhonesInText
import com.ubermicrostudios.messagesenhanced.util.looksLikePhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFormatTest {
    @Test
    fun detectionAcceptsPhoneShapesWithAtLeastSevenDigits() {
        assertTrue(looksLikePhone("+18015551234"))
        assertTrue(looksLikePhone("  +1 801-555-1234 "))
        assertTrue(looksLikePhone("(801) 555-1234"))
        assertTrue(looksLikePhone("801.555.1234"))
        assertTrue(looksLikePhone("555-1212"))
        assertTrue(looksLikePhone("555 555 5555"))
    }

    @Test
    fun detectionRejectsEmailsNamesAndShortCodes() {
        assertFalse(looksLikePhone(null))
        assertFalse(looksLikePhone(""))
        assertFalse(looksLikePhone("   "))
        assertFalse(looksLikePhone("ada@example.com"))
        assertFalse(looksLikePhone("5551212@example.com"))
        assertFalse(looksLikePhone("Ada Quinn"))
        assertFalse(looksLikePhone("Bea Ruiz (you)"))
        assertFalse(looksLikePhone("You"))
        assertFalse(looksLikePhone("55512"))
        assertFalse(looksLikePhone("555-121"))
        assertFalse(looksLikePhone("911"))
        assertFalse(looksLikePhone("++18015551234"))
        assertFalse(looksLikePhone("call 8015551234"))
        assertFalse(looksLikePhone("+18015551234 ext 2"))
    }

    @Test
    fun passthroughLeavesNonPhonesUntouched() {
        assertEquals("", formatPhoneForDisplay(null, "US"))
        assertEquals("", formatPhoneForDisplay("", "US"))
        assertEquals("   ", formatPhoneForDisplay("   ", "US"))
        assertEquals("ada@example.com", formatPhoneForDisplay("ada@example.com", "US"))
        assertEquals("Ada Quinn", formatPhoneForDisplay("Ada Quinn", "US"))
        assertEquals("Bea Ruiz (you)", formatPhoneForDisplay("Bea Ruiz (you)", "US"))
        assertEquals("55512", formatPhoneForDisplay("55512", "US"))
        assertEquals("555-121", formatPhoneForDisplay("555-121", "US"))
        assertEquals("You: ", formatPhoneForDisplay("You: ", "US"))
    }

    @Test
    fun usNumberUsesNationalFormatWhenThePlatformCanFormat() {
        val raw = "+18015551234"
        val shown = formatPhoneForDisplay(raw, "US")
        assertTrue(shown == "(801) 555-1234" || shown == raw)
    }

    @Test
    fun otherCountryPassesThroughWhenThePlatformFormatterIsUnavailable() {
        val raw = "+447555555555"
        val shown = formatPhoneForDisplay(raw, "GB")
        assertTrue(shown.isNotBlank())
        assertTrue(shown == raw || shown.count { it.isDigit() } >= 7)
    }

    @Test
    fun readByListFormatsOnlyThePhoneNames() {
        val raw = "Read by Ada and +18015551234"
        val shown = formatPhonesInText(raw, listOf("Ada", "+18015551234"), "US")
        assertTrue(shown == raw || shown == "Read by Ada and (801) 555-1234")
        assertEquals("Seen by Ada, Bea", formatPhonesInText("Seen by Ada, Bea", listOf("Ada", "Bea"), "US"))
    }

    @Test
    fun countryPrefersNetworkThenSimThenLocaleThenUs() {
        assertEquals("US", displayCountryIso("us", "GB", "FR"))
        assertEquals("GB", displayCountryIso(" ", "gb", "FR"))
        assertEquals("FR", displayCountryIso("usa", "9", "fr"))
        assertEquals("US", displayCountryIso(null, "", "  "))
    }
}