package com.ubermicrostudios.messagesenhanced.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressAndPolicyTest {

    @Test
    fun normalize_defaultsAndTrims() {
        assertEquals("", ServerAddress.DEFAULT)
        val r = ServerAddress.normalize("  192.168.1.20:7117/admin ")
        assertTrue(r.ok)
        assertEquals("http://192.168.1.20:7117", r.origin)
        assertEquals("http://localhost:7117", ServerAddress.normalize("localhost:7117").origin)
        assertEquals("https://tm.example.com", ServerAddress.normalize("https://TM.example.com/x?y=1").origin)
        assertEquals("https://tm.example.com", ServerAddress.normalize("https://tm.example.com:443").origin)
        assertEquals("http://192.168.1.5", ServerAddress.normalize("192.168.1.5:80").origin)
        assertEquals("https://tm.example.com:8443", ServerAddress.normalize("https://tm.example.com:8443/").origin)
    }

    @Test
    fun normalize_rejectsBadInput() {
        assertFalse(ServerAddress.normalize("").ok)
        assertFalse(ServerAddress.normalize("ftp://192.168.1.2").ok)
        assertFalse(ServerAddress.normalize("http://").ok)
        assertFalse(ServerAddress.normalize("http://:7117").ok)
        assertFalse(ServerAddress.normalize("http://192.168.1.2:99999").ok)
        assertFalse(ServerAddress.normalize("http://user@192.168.1.2").ok)
        // Plain http to a public host is refused (https required).
        assertFalse(ServerAddress.normalize("http://example.com:7117").ok)
        assertFalse(ServerAddress.normalize("8.8.8.8:7117").ok)
    }

    @Test
    fun endpoints() {
        assertEquals("http://h:1/login", ServerAddress.loginUrl("http://h:1/"))
        assertEquals("http://h:1/admin/cookies", ServerAddress.cookiesEndpoint("http://h:1"))
    }

    @Test
    fun cleartextHosts() {
        for (h in listOf("192.168.1.20", "10.0.0.2", "172.16.0.1", "172.31.255.255", "127.0.0.1",
            "localhost", "100.100.1.1", "169.254.1.1", "laptop.local", "box.lan", "[::1]", "fd12::1")) {
            assertTrue(h, WebPolicy.isCleartextAllowedHost(h))
        }
        for (h in listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "example.com", "google.com", "1.2.3", "300.1.1.1")) {
            assertFalse(h, WebPolicy.isCleartextAllowedHost(h))
        }
    }

    @Test
    fun userAgent_dropsWebViewMarkers() {
        val wv = "Mozilla/5.0 (Linux; Android 15; Pixel 8a Build/AP4A.250105.002; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.51 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8a Build/AP4A.250105.002) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.7339.51 Mobile Safari/537.36",
            WebPolicy.chromeLikeUserAgent(wv),
        )
        val plain = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        assertEquals(plain, WebPolicy.chromeLikeUserAgent(plain))
    }

    @Test
    fun googleBlockDetection() {
        assertTrue(WebPolicy.isGoogleSignInBlocked("https://accounts.google.com/signin/rejected?x=1"))
        assertTrue(WebPolicy.isGoogleSignInBlocked("https://accounts.google.com/v3/signin/rejected?rrk=1"))
        assertTrue(WebPolicy.isGoogleSignInBlocked("https://accounts.google.com/o/oauth2/auth?error=disallowed_useragent"))
        assertFalse(WebPolicy.isGoogleSignInBlocked("https://accounts.google.com/v3/signin/identifier"))
        assertFalse(WebPolicy.isGoogleSignInBlocked("https://messages.google.com/web/conversations"))
        assertFalse(WebPolicy.isGoogleSignInBlocked(null))
    }

    @Test
    fun webViewOnlyLoadsHttps() {
        assertTrue(WebPolicy.isAllowedInWebView("https://messages.google.com/web/"))
        assertFalse(WebPolicy.isAllowedInWebView("http://messages.google.com/"))
        assertFalse(WebPolicy.isAllowedInWebView("intent://x#Intent;end"))
        assertFalse(WebPolicy.isAllowedInWebView("market://details?id=x"))
    }
}
