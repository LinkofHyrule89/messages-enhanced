package com.ubermicrostudios.messagesenhanced.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ubermicrostudios.messagesenhanced.util.NetworkAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Process-level foreground and validated-network gate. Register once from
 * application start (off the main thread; this class posts observer setup
 * to the main looper). On each foreground entry, idle OkHttp sockets are
 * dropped so the first requests do not reuse a stale pooled connection.
 */
@Singleton
class NetworkGate @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
    @Named("sse") private val sseHttp: OkHttpClient,
) : NetworkAvailability {

    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val hooksLock = Any()
    private var hooksGeneration = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _validated = MutableStateFlow(false)
    val networkValidated: StateFlow<Boolean> = _validated.asStateFlow()

    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    override val isForeground: Boolean get() = _foreground.value
    override val isNetworkValidated: Boolean get() = _validated.value
    override val foregroundGeneration: Long get() = _generation.value

    /** Idempotent. Safe to call from a background dispatcher. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val callback: ConnectivityManager.NetworkCallback
        val observer: DefaultLifecycleObserver
        val gen: Int
        synchronized(hooksLock) {
            gen = ++hooksGeneration
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _validated.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    _validated.value = false
                }

                override fun onUnavailable() {
                    _validated.value = false
                }
            }
            networkCallback = callback
            observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    _generation.value = _generation.value + 1L
                    _foreground.value = true
                    evictPools()
                }

                override fun onStop(owner: LifecycleOwner) {
                    _foreground.value = false
                }
            }
            lifecycleObserver = observer
        }
        onMain {
            synchronized(hooksLock) {
                if (!started.get() || gen != hooksGeneration) return@onMain
            }
            installNetwork(callback)
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val callback: ConnectivityManager.NetworkCallback?
        val observer: DefaultLifecycleObserver?
        synchronized(hooksLock) {
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

    private fun installNetwork(callback: ConnectivityManager.NetworkCallback) {
        runCatching {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            _validated.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    private fun evictPools() {
        ioScope.launch {
            runCatching { http.connectionPool.evictAll() }
            if (sseHttp.connectionPool !== http.connectionPool) {
                runCatching { sseHttp.connectionPool.evictAll() }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}
