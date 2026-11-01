package com.ubermicrostudios.messagesenhanced.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.StreamEvent
import com.ubermicrostudios.messagesenhanced.util.ReconnectDelay
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * One `/api/events` stream for the process. Reconnects with backoff and jitter,
 * restarts when the network returns or the app comes to the foreground, and
 * drops the socket when heartbeats stop. Stream failures are not shown in the UI.
 */
@Singleton
class SseClient @Inject constructor(
    @Named("sse") private val http: OkHttpClient,
    private val baseUrl: BaseUrlProvider,
    private val api: MessagesApi,
    private val typing: TypingTracker,
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<StreamEvent> = _events

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var current: EventSource? = null
    @Volatile private var attemptToken: Any? = null
    @Volatile private var openedOnce = false
    @Volatile private var lastEventAt = 0L
    private var watchdog: Job? = null
    private val hooksLock = Any()
    private var hooksInstalled = false
    private var hooksGeneration = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    fun ensureStarted() {
        running = true
        installHooks()
        if (started.compareAndSet(false, true)) {
            scope.launch { loop() }
            scope.launch { typingPoll() }
        } else {
            requestReconnect()
        }
    }

    /** Like the web app's streamLive(): an open socket that delivered something recently. */
    fun isLive(now: Long = System.currentTimeMillis()): Boolean =
        current != null && openedOnce && now - lastEventAt < LIVE_WINDOW_MS

    fun stop() {
        running = false
        started.set(false)
        watchdog?.cancel()
        current?.cancel()
        uninstallHooks()
        typing.clear()
        kick.trySend(Unit)
    }

    /** Drop the socket so the loop connects again immediately. */
    fun requestReconnect() {
        if (!running) return
        current?.cancel()
        kick.trySend(Unit)
    }

    private fun installHooks() {
        val callback: ConnectivityManager.NetworkCallback
        val observer: DefaultLifecycleObserver
        val gen: Int
        synchronized(hooksLock) {
            if (hooksInstalled) return
            hooksInstalled = true
            gen = ++hooksGeneration
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    requestReconnect()
                }
            }
            networkCallback = callback
            observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (openedOnce) requestReconnect()
                }
            }
            lifecycleObserver = observer
        }
        onMain {
            synchronized(hooksLock) {
                if (!hooksInstalled || gen != hooksGeneration) return@onMain
            }
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            runCatching { cm?.registerDefaultNetworkCallback(callback) }
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        }
    }

    private fun uninstallHooks() {
        val callback: ConnectivityManager.NetworkCallback?
        val observer: DefaultLifecycleObserver?
        synchronized(hooksLock) {
            if (!hooksInstalled) return
            hooksInstalled = false
            hooksGeneration++
            callback = networkCallback
            networkCallback = null
            observer = lifecycleObserver
            lifecycleObserver = null
        }
        onMain {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            runCatching { if (callback != null) cm?.unregisterNetworkCallback(callback) }
            runCatching { if (observer != null) ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private suspend fun loop() {
        var attempt = 0
        while (scope.isActive) {
            if (!running) {
                kick.receive()
                continue
            }
            when (connectOnce()) {
                ConnectEnd.AUTH -> {
                    running = false
                    started.set(false)
                    continue
                }
                ConnectEnd.OPENED -> attempt = 0
                ConnectEnd.FAILED -> Unit
            }
            if (!running) continue
            val wait = ReconnectDelay.millis(attempt)
            if (attempt < 8) attempt++
            val kicked = withTimeoutOrNull(wait) { kick.receive() }
            if (kicked != null) attempt = 0
        }
    }

    private suspend fun connectOnce(): ConnectEnd {
        val opened = AtomicBoolean(false)
        val done = CompletableDeferred<ConnectEnd>()
        val token = Any()
        attemptToken = token
        val request = Request.Builder()
            .url(baseUrl.origin() + "/api/events")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val source = EventSources.createFactory(http).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    opened.set(true)
                    val first = !openedOnce
                    openedOnce = true
                    lastEventAt = System.currentTimeMillis()
                    armWatchdog(eventSource, token)
                    scope.launch { refreshTyping() }
                    if (!first) _events.tryEmit(StreamEvent(type = "reconnected"))
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    lastEventAt = System.currentTimeMillis()
                    if (data.isBlank() && type.isNullOrBlank()) return
                    val parsed = runCatching {
                        JsonConfig.json.decodeFromString(StreamEvent.serializer(), data)
                    }.getOrElse { StreamEvent(type = type.orEmpty()) }
                    val event = if (parsed.type.isBlank() && !type.isNullOrBlank()) {
                        parsed.copy(type = type)
                    } else {
                        parsed
                    }
                    if (event.type == "typing") typing.applyEvent(event)
                    _events.tryEmit(event)
                }

                override fun onClosed(eventSource: EventSource) {
                    finish(if (opened.get()) ConnectEnd.OPENED else ConnectEnd.FAILED)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val code = response?.code ?: 0
                    response?.close()
                    val end = when {
                        code == 401 || code == 403 -> ConnectEnd.AUTH
                        opened.get() -> ConnectEnd.OPENED
                        else -> ConnectEnd.FAILED
                    }
                    finish(end)
                }

                private fun finish(end: ConnectEnd) {
                    watchdog?.cancel()
                    if (!done.isCompleted) done.complete(end)
                }
            },
        )
        current = source
        return try {
            done.await()
        } finally {
            if (current === source) current = null
            if (attemptToken === token) attemptToken = null
        }
    }

    private fun armWatchdog(source: EventSource, token: Any) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && attemptToken === token) {
                delay(10_000)
                if (attemptToken !== token) break
                if (System.currentTimeMillis() - lastEventAt > HEARTBEAT_STALE_MS) {
                    source.cancel()
                    break
                }
            }
        }
    }

    private suspend fun typingPoll() {
        while (scope.isActive) {
            if (running) refreshTyping()
            delay(20_000)
        }
    }

    private suspend fun refreshTyping() {
        runCatchingCancellable { api.typing() }.onSuccess { typing.replace(it.typing) }
        typing.prune()
    }

    private enum class ConnectEnd { OPENED, FAILED, AUTH }

    companion object {
        /** Server heartbeats are 25s apart. A silent minute means the socket is dead. */
        const val HEARTBEAT_STALE_MS = 60_000L
        const val LIVE_WINDOW_MS = 40_000L
    }
}
