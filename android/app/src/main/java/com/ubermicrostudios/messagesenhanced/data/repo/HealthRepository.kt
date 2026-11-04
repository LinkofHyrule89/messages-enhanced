package com.ubermicrostudios.messagesenhanced.data.repo

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ubermicrostudios.messagesenhanced.data.model.HealthResponse
import com.ubermicrostudios.messagesenhanced.data.model.PairingResponse
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.data.net.SseClient
import com.ubermicrostudios.messagesenhanced.util.HealthBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls GET /api/app/health and GET /api/app/pairing while the app is in the foreground.
 *
 * Health is fetched on start, on resume ([refresh]), every [POLL_MS], when the event
 * stream reconnects, and when it delivers a `status` event. Pairing is fetched on
 * start, resume, the same interval, and on `status` (not on a bare reconnect).
 * A failed call keeps the last good sample and never surfaces an error or a toast.
 */
@Singleton
class HealthRepository @Inject constructor(
    private val api: MessagesApi,
    private val sse: SseClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val started = AtomicBoolean(false)
    private val pullGen = AtomicInteger(0)

    private val _banner = MutableStateFlow<HealthBanner.Banner?>(null)
    val banner: StateFlow<HealthBanner.Banner?> = _banner

    /** Latest `google.connected` from GET /api/app/pairing. Null until a sample arrives. */
    private val _googleConnected = MutableStateFlow<Boolean?>(null)
    val googleConnected: StateFlow<Boolean?> = _googleConnected.asStateFlow()

    @Volatile private var foreground = false
    private var healthSnapshot: HealthResponse? = null
    private var pairingSnapshot: PairingResponse? = null
    private var condition: HealthBanner.UnstableClock? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null
    private var pollJob: Job? = null
    private var sseJob: Job? = null
    private var revealJob: Job? = null
    private var revealGen = 0

    /** Begin polling. Safe to call once per signed-in session; a second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        foreground = true
        installLifecycle()
        sseJob = scope.launch { collectEvents() }
        pollJob = scope.launch { pollLoop() }
        scope.launch { pull(fetchHealth = true, fetchPairing = true) }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        foreground = false
        pollJob?.cancel()
        sseJob?.cancel()
        revealJob?.cancel()
        pollJob = null
        sseJob = null
        revealJob = null
        revealGen++
        removeLifecycle()
        scope.launch {
            gate.withLock {
                healthSnapshot = null
                pairingSnapshot = null
                condition = null
                _banner.value = null
                _googleConnected.value = null
            }
        }
    }

    /** Activity resume: both endpoints, even if the process never left the foreground. */
    fun refresh() {
        if (!started.get()) return
        scope.launch { pull(fetchHealth = true, fetchPairing = true) }
    }

    private suspend fun pollLoop() {
        while (started.get()) {
            delay(POLL_MS)
            if (!started.get()) return
            if (foreground) pull(fetchHealth = true, fetchPairing = true)
        }
    }

    private suspend fun collectEvents() {
        sse.events.collect { event ->
            if (!foreground || !started.get()) return@collect
            when (event.type) {
                "reconnected" -> pull(fetchHealth = true, fetchPairing = false)
                "status" -> pull(fetchHealth = true, fetchPairing = true)
            }
        }
    }

    private suspend fun pull(fetchHealth: Boolean, fetchPairing: Boolean) {
        if (!started.get()) return
        // A newer pull supersedes this one, so a slow response cannot overwrite a fresh sample.
        val gen = pullGen.incrementAndGet()
        val nextHealth = if (fetchHealth) quiet { api.appHealth() } else null
        val nextPairing = if (fetchPairing) quiet { api.appPairing() } else null
        gate.withLock {
            if (!started.get() || gen != pullGen.get()) return
            if (nextHealth != null) healthSnapshot = nextHealth
            if (nextPairing != null) pairingSnapshot = nextPairing
            publishLocked()
        }
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        condition = HealthBanner.nextClock(condition, pairingSnapshot, now)
        _googleConnected.value = pairingSnapshot?.google?.connected
        _banner.value = HealthBanner.banner(
            health = healthSnapshot,
            pairing = pairingSnapshot,
            nowMs = now,
            formatClock = { HealthBanner.formatLocalClock(it) },
            unstableSinceMs = condition?.sinceMs,
        )
        scheduleReveal(now, condition)
    }

    /** Recompute when a connecting/disconnected streak crosses [HealthBanner.PERSIST_MS]. No network. */
    private fun scheduleReveal(now: Long, clock: HealthBanner.UnstableClock?) {
        val wait = if (clock == null) -1L else HealthBanner.PERSIST_MS - (now - clock.sinceMs)
        if (clock == null || wait <= 0L) {
            revealGen++
            revealJob?.cancel()
            revealJob = null
            return
        }
        val gen = ++revealGen
        val stamp = clock
        revealJob?.cancel()
        revealJob = scope.launch {
            delay(wait)
            if (gen != revealGen) return@launch
            gate.withLock {
                if (!started.get() || condition != stamp) return@withLock
                val at = System.currentTimeMillis()
                _banner.value = HealthBanner.banner(
                    health = healthSnapshot,
                    pairing = pairingSnapshot,
                    nowMs = at,
                    formatClock = { HealthBanner.formatLocalClock(it) },
                    unstableSinceMs = condition?.sinceMs,
                )
            }
        }
    }

    private fun installLifecycle() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!started.get()) return
                foreground = true
                scope.launch { pull(fetchHealth = true, fetchPairing = true) }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        }
        lifecycleObserver = observer
        onMain { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
    }

    private fun removeLifecycle() {
        val observer = lifecycleObserver ?: return
        lifecycleObserver = null
        onMain { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val POLL_MS = 60_000L
    }
}
