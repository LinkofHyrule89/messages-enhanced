package com.ubermicrostudios.messagesenhanced.util

import android.os.Handler
import android.os.Looper
import com.ubermicrostudios.messagesenhanced.data.net.NetworkGate
import com.ubermicrostudios.messagesenhanced.data.repo.HealthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.LinkPreviewCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the network becomes usable again, the process returns to the foreground, or Google
 * Messages reports connected, ask again for link previews that failed and for images
 * still on screen.
 */
@Singleton
class MediaRecovery @Inject constructor(
    private val gate: NetworkGate,
    private val health: HealthRepository,
    private val previews: LinkPreviewCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { watchNetwork() }
        scope.launch { watchForeground() }
        scope.launch { watchServer() }
    }

    private suspend fun watchNetwork() {
        var previous = gate.isNetworkValidated
        gate.networkValidated.collect { now ->
            val rose = now && !previous
            previous = now
            if (rose) pulse()
        }
    }

    private suspend fun watchForeground() {
        var previous = gate.isForeground
        gate.foreground.collect { now ->
            val rose = now && !previous
            previous = now
            if (rose) pulse()
        }
    }

    private suspend fun watchServer() {
        var previous = health.googleConnected.value
        health.googleConnected.collect { now ->
            val rose = now == true && previous == false
            previous = now
            if (rose) pulse()
        }
    }

    private fun pulse() {
        previews.releaseFailures()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            RetryingImages.retryAfterReconnect()
        } else {
            main.post { RetryingImages.retryAfterReconnect() }
        }
    }
}
