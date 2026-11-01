package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.GoogleCookiesRequest
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.ui.settings.GoogleReconnectViewModel
import com.ubermicrostudios.messagesenhanced.util.GoogleCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCookiesTest {

    @Test
    fun filter_keepsRequiredAndOptional_dropsOthers() {
        val input = mapOf(
            "SID" to "s1",
            "HSID" to "s1",
            "SSID" to "s1",
            "OSID" to "s1",
            "APISID" to "s1",
            "SAPISID" to "s1",
            "__Secure-1PSID" to "s1",
            "__Secure-3PSID" to "s1",
            "__Secure-1PSIDTS" to "s1",
            "__Secure-3PSIDTS" to "s1",
            "__Secure-1PAPISID" to "s1",
            "__Secure-3PAPISID" to "s1",
            "NID" to "s1",
            "SIDCC" to "s1",
            "OTHER" to "drop-me",
            "GAPS" to "drop-me",
        )
        val filtered = GoogleCookies.filterAllowed(input)
        assertEquals(
            setOf(
                "SID", "HSID", "SSID", "OSID", "APISID", "SAPISID",
                "__Secure-1PSID", "__Secure-3PSID",
                "__Secure-1PSIDTS", "__Secure-3PSIDTS",
                "__Secure-1PAPISID", "__Secure-3PAPISID",
                "NID", "SIDCC",
            ),
            filtered.keys,
        )
        for (value in filtered.values) {
            assertEquals("s1", value)
        }
        assertFalse(filtered.containsKey("OTHER"))
        assertFalse(filtered.containsKey("GAPS"))
        assertTrue(GoogleCookies.hasRequired(filtered))
    }

    @Test
    fun mergeAndFilter_parsesCookieHeaders_andDropsUnknownNames() {
        val filtered = GoogleCookies.mergeAndFilter(
            listOf(
                "SID=s1; OTHER=drop-me",
                "HSID=s1; SSID=s1; APISID=s1",
                "OSID=s1; SAPISID=s1; NID=s1",
                "SIDCC=s1; __Secure-1PSID=s1",
            ),
        )
        assertTrue(GoogleCookies.hasRequired(filtered))
        assertEquals("s1", filtered["SID"])
        assertEquals("s1", filtered["NID"])
        assertEquals("s1", filtered["__Secure-1PSID"])
        assertFalse(filtered.containsKey("OTHER"))
        assertEquals(
            setOf(
                "SID", "HSID", "SSID", "OSID", "APISID", "SAPISID",
                "NID", "SIDCC", "__Secure-1PSID",
            ),
            filtered.keys,
        )
    }

    @Test
    fun looksSignedIn_needsSidHsidSapisid() {
        assertFalse(GoogleCookies.looksSignedIn(emptyMap()))
        assertFalse(GoogleCookies.looksSignedIn(mapOf("SID" to "s1", "HSID" to "s1")))
        assertTrue(
            GoogleCookies.looksSignedIn(
                mapOf("SID" to "s1", "HSID" to "s1", "SAPISID" to "s1"),
            ),
        )
        assertFalse(GoogleCookies.hasRequired(mapOf("SID" to "s1", "HSID" to "s1", "SAPISID" to "s1")))
    }

    @Test
    fun looksLikeGoogleBlock_detectsRejectedSignIn() {
        assertTrue(GoogleCookies.looksLikeGoogleBlock("https://accounts.google.com/signin/rejected", null))
        assertTrue(GoogleCookies.looksLikeGoogleBlock("https://accounts.google.com/", "Couldn't sign you in"))
        assertTrue(
            GoogleCookies.looksLikeGoogleBlock(
                "https://accounts.google.com/",
                "Google",
                "This browser or app may not be secure",
            ),
        )
        assertFalse(GoogleCookies.looksLikeGoogleBlock("https://messages.google.com/web/", "Messages"))
    }

    @Test
    fun googleCookiesRequest_encodesMap() {
        val json = JsonConfig.json.encodeToString(
            GoogleCookiesRequest.serializer(),
            GoogleCookiesRequest(mapOf("SID" to "s1")),
        )
        assertTrue(json.contains("\"cookies\""))
        assertTrue(json.contains("\"SID\""))
        assertTrue(json.contains("\"s1\""))
    }

    @Test
    fun parseServerError_readsMessageShapes() {
        assertEquals(
            "expired",
            GoogleReconnectViewModel.parseServerError("""{"error":{"message":"expired"}}"""),
        )
        assertEquals("nope", GoogleReconnectViewModel.parseServerError("""{"error":"nope"}"""))
        assertEquals("x", GoogleReconnectViewModel.parseServerError("""{"message":"x"}"""))
    }
}
