package com.ubermicrostudios.messagesenhanced.util

/**
 * Process foreground and validated-network snapshot used to decide whether a
 * failed request should count toward the unreachable-server banner.
 */
interface NetworkAvailability {
    val isForeground: Boolean
    val isNetworkValidated: Boolean
    val foregroundGeneration: Long

    companion object {
        val AlwaysOn: NetworkAvailability = object : NetworkAvailability {
            override val isForeground: Boolean get() = true
            override val isNetworkValidated: Boolean get() = true
            override val foregroundGeneration: Long get() = 0L
        }
    }
}
