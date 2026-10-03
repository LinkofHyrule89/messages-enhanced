package com.ubermicrostudios.messagesenhanced.net

import com.ubermicrostudios.messagesenhanced.net.ResponseInterpreter.Kind
import com.ubermicrostudios.messagesenhanced.net.ResponseInterpreter.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseInterpreterTest {

    private fun page(inner: String) = "<main>$inner<form></form></main>"
    private val url = "http://192.168.1.20:7117/admin/cookies"

    @Test
    fun saved() {
        val r = ResponseInterpreter.interpret(200, url,
            page("<p class=\"admin-ok\" role=\"status\">Saved: APISID, HSID, OSID</p>"))
        assertEquals(Kind.SAVED, r.kind)
        assertEquals("Saved: APISID, HSID, OSID", r.message)
    }

    @Test
    fun missingRequired() {
        val r = ResponseInterpreter.interpret(400, url,
            page("<p class=\"login-error\" role=\"alert\">Missing required cookies: OSID. Nothing was saved.</p>"))
        assertEquals(Kind.ERROR, r.kind)
        assertEquals("Missing required cookies: OSID. Nothing was saved.", r.message)
    }

    @Test
    fun otherServerError() {
        val r = ResponseInterpreter.interpret(400, url,
            page("<p class=\"login-error\" role=\"alert\">nothing pasted</p>"))
        assertEquals(ResponseInterpreter.Result(Kind.ERROR, "nothing pasted"), r)
    }

    @Test
    fun needLogin() {
        assertEquals(Kind.NEED_LOGIN, ResponseInterpreter.interpret(401, url, "{\"error\":\"login required\"}").kind)
        assertEquals(Kind.NEED_LOGIN,
            ResponseInterpreter.interpret(302, "http://x:7117/login?next=%2Fadmin%2Fcookies", "").kind)
        assertEquals(Kind.NEED_LOGIN, ResponseInterpreter.interpret(303, "http://x/login", "").kind)
    }

    @Test
    fun crossOriginAndHttpErrors() {
        val r = ResponseInterpreter.interpret(403, url, "cross-origin request rejected\n")
        assertEquals(Kind.ERROR, r.kind)
        assertTrue(r.message.contains("cross-origin"))
        assertEquals("The server returned HTTP 500.", ResponseInterpreter.interpret(500, url, "boom").message)
        assertEquals(Kind.UNKNOWN, ResponseInterpreter.interpret(200, url, "<html></html>").kind)
        assertEquals(Kind.UNKNOWN, ResponseInterpreter.interpret(200, null, null).kind)
    }

    @Test
    fun loginClassification() {
        assertEquals(LoginResult.OK, ResponseInterpreter.classifyLogin(303, "/app/", true))
        assertEquals(LoginResult.OK, ResponseInterpreter.classifyLogin(303, "/app/", false))
        assertEquals(LoginResult.WRONG_PASSWORD, ResponseInterpreter.classifyLogin(401, null, false))
        assertEquals(LoginResult.RATE_LIMITED, ResponseInterpreter.classifyLogin(429, null, false))
        assertEquals(LoginResult.CROSS_ORIGIN, ResponseInterpreter.classifyLogin(403, null, false))
        assertEquals(LoginResult.WRONG_PASSWORD, ResponseInterpreter.classifyLogin(200, null, false))
        assertEquals(LoginResult.FAILED, ResponseInterpreter.classifyLogin(302, "/login", false))
        assertEquals(LoginResult.FAILED, ResponseInterpreter.classifyLogin(500, null, false))
    }
}
