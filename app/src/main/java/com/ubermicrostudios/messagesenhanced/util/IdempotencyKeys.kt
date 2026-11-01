package com.ubermicrostudios.messagesenhanced.util

import java.util.UUID

/**
 * Idempotency keys for POST /api/send. The server accepts [a-zA-Z0-9_.:-] up to 128 chars;
 * a UUID string always matches.
 */
object IdempotencyKeys {
    private val allowed = Regex("^[A-Za-z0-9_.:-]{1,128}$")

    fun newKey(): String = UUID.randomUUID().toString()

    fun isValid(key: String): Boolean = allowed.matches(key.trim())
}
