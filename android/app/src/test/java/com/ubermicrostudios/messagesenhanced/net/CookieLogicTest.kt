package com.ubermicrostudios.messagesenhanced.net

import com.ubermicrostudios.messagesenhanced.net.CookieLogic.HostCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/** All values below are fake placeholders. */
class CookieLogicTest {

    private val msgs = "messages.google.com"
    private val www = "www.google.com"
    private val acct = "accounts.google.com"

    @Test
    fun nameListsMatchServer() {
        assertEquals(listOf("SID", "HSID", "SSID", "OSID", "APISID", "SAPISID"), CookieLogic.REQUIRED)
        assertEquals(
            listOf("__Secure-1PSID", "__Secure-3PSID", "__Secure-1PSIDTS", "__Secure-3PSIDTS",
                "__Secure-1PAPISID", "__Secure-3PAPISID", "NID", "SIDCC"),
            CookieLogic.OPTIONAL,
        )
        // If the server source is next to us on this machine, check it too.
        val go = listOf(
            File("/workspace/tesla-messages/app/internal/webapp/cookies.go"),
        ).firstOrNull { it.exists() } ?: return
        val src = go.readText()
        fun grab(v: String): List<String> =
            Regex("$v = \\[\\]string\\{([^}]*)\\}").find(src)!!.groupValues[1]
                .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(grab("RequiredGoogleCookies"), CookieLogic.REQUIRED)
        assertEquals(grab("OptionalGoogleCookies"), CookieLogic.OPTIONAL)
    }

    @Test
    fun parseCookieHeader_splitsPairsAndKeepsEqualsInValue() {
        val parsed = CookieLogic.parseCookieHeader(msgs, "SID=aaa; OSID=b=c==; ;junk; NID=x")
        assertEquals(
            listOf(HostCookie(msgs, "SID", "aaa"), HostCookie(msgs, "OSID", "b=c=="), HostCookie(msgs, "NID", "x")),
            parsed,
        )
        assertTrue(CookieLogic.parseCookieHeader(msgs, null).isEmpty())
        assertTrue(CookieLogic.parseCookieHeader(msgs, "   ").isEmpty())
    }

    @Test
    fun osid_prefersMessagesHost_evenWhenListedLater() {
        val cookies = listOf(
            HostCookie(acct, "OSID", "accounts-osid"),
            HostCookie(www, "OSID", "www-osid"),
            HostCookie(msgs, "OSID", "messages-osid"),
        )
        assertEquals("messages-osid", CookieLogic.selectCookies(cookies)["OSID"])
    }

    @Test
    fun osid_fromOtherHostOnly_isReportedMissing() {
        val cookies = listOf(
            HostCookie("mail.google.com", "OSID", "mail-osid"),
            HostCookie(acct, "OSID", "accounts-osid"),
            HostCookie(www, "SID", "s"),
        )
        val map = CookieLogic.selectCookies(cookies)
        assertNull(map["OSID"])
        assertTrue("OSID" in CookieLogic.requiredStatus(map).missing)
    }

    @Test
    fun hostMatching_acceptsUrlsAndLeadingDot() {
        val cookies = listOf(HostCookie("https://messages.google.com/web/", "OSID", "m"))
        assertEquals("m", CookieLogic.selectCookies(cookies)["OSID"])
        assertEquals("messages.google.com", CookieLogic.normalizeHost(".Messages.Google.com"))
        assertEquals("www.google.com", CookieLogic.normalizeHost("https://www.google.com:443/x"))
    }

    @Test
    fun selection_keepsOnlyWantedNames_skipsEmpty_andMergesHosts() {
        val cookies = CookieLogic.parseCookieHeader(msgs, "SID=s1; HSID=; OSID=m; RANDOM=r; __Secure-3PSID=p3") +
            CookieLogic.parseCookieHeader(www, "SID=s2; HSID=h; SSID=ss; APISID=a; SAPISID=sa; NID=n; SIDCC=c") +
            CookieLogic.parseCookieHeader(acct, "__Secure-1PSIDTS=t1; LSID=nope")
        val map = CookieLogic.selectCookies(cookies)
        assertEquals(
            setOf("SID", "HSID", "SSID", "OSID", "APISID", "SAPISID", "__Secure-3PSID", "__Secure-1PSIDTS", "NID", "SIDCC"),
            map.keys,
        )
        assertFalse(map.containsKey("RANDOM"))
        assertFalse(map.containsKey("LSID"))
        assertEquals("h", map["HSID"]) // empty value on messages ignored
        assertEquals("m", map["OSID"])
        assertTrue(CookieLogic.requiredStatus(map).missing.isEmpty())
    }

    @Test
    fun nonOsid_prefersWwwThenFirstSeen() {
        val cookies = listOf(
            HostCookie(msgs, "SID", "from-messages"),
            HostCookie(www, "SID", "from-www"),
        )
        assertEquals("from-www", CookieLogic.selectCookies(cookies)["SID"])
        val onlyMsgs = listOf(HostCookie(msgs, "SID", "from-messages"), HostCookie("foo.google.com", "SID", "x"))
        assertEquals("from-messages", CookieLogic.selectCookies(onlyMsgs)["SID"])
    }

    @Test
    fun requiredStatus_reportsMissingInOrder() {
        val st = CookieLogic.requiredStatus(mapOf("SID" to "x", "SAPISID" to "y"))
        assertEquals(listOf("SID", "SAPISID"), st.found)
        assertEquals(listOf("HSID", "SSID", "OSID", "APISID"), st.missing)
        assertEquals(CookieLogic.REQUIRED, CookieLogic.requiredStatus(emptyMap()).missing)
    }

    @Test
    fun presentNames_sortedNamesOnly() {
        assertEquals(listOf("A", "B"), CookieLogic.presentNames(mapOf("B" to "secret1", "A" to "secret2")))
    }

    @Test
    fun buildFormBody_roundTripsThroughFormDecoding() {
        val m = linkedMapOf("SID" to "a+b/c=d&e", "__Secure-1PSID" to "g.h%i", "OSID" to "ü\"q\\z")
        val body = CookieLogic.buildFormBody(m)
        assertTrue(body.startsWith("cookies="))
        assertFalse(body.contains('&'))
        val json = URLDecoder.decode(body.removePrefix("cookies="), "UTF-8")
        assertEquals("{\"SID\":\"a+b/c=d&e\",\"OSID\":\"ü\\\"q\\\\z\",\"__Secure-1PSID\":\"g.h%i\"}", json)
    }

    @Test
    fun toJsonObject_escapesControlChars() {
        assertEquals("{\"SID\":\"a\\nb\\u0001\"}", CookieLogic.toJsonObject(mapOf("SID" to "a\nb\u0001")))
    }
}
