package com.ubermicrostudios.messagesenhanced.update

import android.content.pm.PackageInstaller
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges the install session (which outlives the activity that started it)
 * back to any progress dialog still on screen.
 */
internal object UpdateEvents {
    @Volatile
    var currentToken: Int = 0

    private val dismissers = CopyOnWriteArrayList<() -> Unit>()
    private val sessionFailures = CopyOnWriteArrayList<(Int) -> Unit>()

    fun addDismisser(listener: () -> Unit) {
        dismissers.add(listener)
    }

    fun removeDismisser(listener: () -> Unit) {
        dismissers.remove(listener)
    }

    fun addSessionFailure(listener: (Int) -> Unit) {
        sessionFailures.add(listener)
    }

    fun downloadFailed() {
        dismissers.forEach { listener -> listener() }
    }

    fun sessionStatus(status: Int, token: Int) {
        if (token != currentToken) return
        if (status == PackageInstaller.STATUS_SUCCESS ||
            status == PackageInstaller.STATUS_PENDING_USER_ACTION
        ) {
            return
        }
        sessionFailures.forEach { listener -> listener(token) }
        dismissers.forEach { listener -> listener() }
    }
}
