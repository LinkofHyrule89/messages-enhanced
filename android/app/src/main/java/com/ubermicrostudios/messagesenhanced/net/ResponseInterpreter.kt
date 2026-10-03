package com.ubermicrostudios.messagesenhanced.net

/**
 * Interprets the Messages Enhanced server's response to POST /admin/cookies.
 * Mirrors the extension's interpretResponse (lib.js). Pure, unit-tested.
 */
object ResponseInterpreter {

    enum class Kind { SAVED, NEED_LOGIN, ERROR, UNKNOWN }

    data class Result(val kind: Kind, val message: String)

    fun interpret(status: Int, finalUrl: String?, body: String?): Result {
        val url = finalUrl ?: ""
        val text = body ?: ""

        if (status == 401 || Regex("/login(\\?|$)").containsMatchIn(url)) {
            return Result(Kind.NEED_LOGIN, "The server needs you to sign in first.")
        }

        Regex("Saved:\\s*([^<\\n]+)").find(text)?.let {
            return Result(Kind.SAVED, "Saved: " + it.groupValues[1].trim())
        }
        Regex("Missing required cookies:[^<\\n]*").find(text)?.let {
            return Result(Kind.ERROR, it.value.trim())
        }
        Regex("class=\"login-error\"[^>]*>([^<]+)<").find(text)?.let {
            return Result(Kind.ERROR, it.groupValues[1].trim())
        }
        if (status == 403 && Regex("(?i)cross-origin").containsMatchIn(text)) {
            return Result(
                Kind.ERROR,
                "The server rejected the request as cross-origin (403).",
            )
        }
        if (status in 200..299) {
            return Result(Kind.UNKNOWN, "The server responded but the result could not be read.")
        }
        return Result(Kind.ERROR, "The server returned HTTP $status.")
    }

    enum class LoginResult { OK, WRONG_PASSWORD, RATE_LIMITED, CROSS_ORIGIN, FAILED }

    /**
     * Classify the /login POST response. Success is a 303 that sets the
     * tm_session cookie; the server re-renders the form with 401 on a wrong
     * secret and 429 when rate-limited.
     */
    fun classifyLogin(status: Int, location: String?, gotSessionCookie: Boolean): LoginResult = when {
        status == 401 -> LoginResult.WRONG_PASSWORD
        status == 429 -> LoginResult.RATE_LIMITED
        status == 403 -> LoginResult.CROSS_ORIGIN
        gotSessionCookie -> LoginResult.OK
        status in 300..399 && !(location ?: "").contains("/login") -> LoginResult.OK
        status in 200..299 -> LoginResult.WRONG_PASSWORD
        else -> LoginResult.FAILED
    }
}
