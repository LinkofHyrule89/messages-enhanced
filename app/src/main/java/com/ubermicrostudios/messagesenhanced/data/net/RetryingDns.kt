package com.ubermicrostudios.messagesenhanced.data.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Retries system DNS a few times. Transient "No address associated with hostname"
 * answers from the resolver are common on flaky links and clear on the next lookup.
 */
class RetryingDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val attempts: Int = 3,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        var last: UnknownHostException? = null
        val tries = attempts.coerceAtLeast(1)
        for (attempt in 0 until tries) {
            try {
                return delegate.lookup(hostname)
            } catch (e: UnknownHostException) {
                last = e
                if (attempt < tries - 1) {
                    sleep(200L * (attempt + 1))
                }
            }
        }
        throw last ?: UnknownHostException(hostname)
    }
}
