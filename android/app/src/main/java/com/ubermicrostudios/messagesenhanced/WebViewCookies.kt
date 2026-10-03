package com.ubermicrostudios.messagesenhanced

import android.webkit.CookieManager
import com.ubermicrostudios.messagesenhanced.net.CookieLogic

/**
 * Reads Google cookies from THIS app's own WebView cookie store only (where the
 * phone's owner signed in himself). Nothing else is ever read.
 *
 * CookieManager.getCookie(url) returns "name=value; ..." for everything that
 * would be sent to that URL, which includes HttpOnly cookies and host-only
 * cookies of that host. Reading messages.google.com therefore returns the
 * messages.google.com OSID, which [CookieLogic.selectCookies] prefers.
 */
object WebViewCookies {

    val SOURCE_URLS = listOf(
        "https://messages.google.com",   // first: correct OSID + .google.com cookies
        "https://www.google.com",
        "https://accounts.google.com",
    )

    fun collect(cm: CookieManager = CookieManager.getInstance()): Map<String, String> {
        cm.flush()
        val all = ArrayList<CookieLogic.HostCookie>()
        for (url in SOURCE_URLS) {
            all += CookieLogic.parseCookieHeader(CookieLogic.normalizeHost(url), cm.getCookie(url))
        }
        return CookieLogic.selectCookies(all)
    }
}
