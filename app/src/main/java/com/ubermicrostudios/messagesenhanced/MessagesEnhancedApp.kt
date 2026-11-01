package com.ubermicrostudios.messagesenhanced

import android.app.Application
import android.os.Looper
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import com.ubermicrostudios.messagesenhanced.data.net.NetworkGate
import com.ubermicrostudios.messagesenhanced.data.net.PersistentCookieJar
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.push.MessageNotificationIds
import com.ubermicrostudios.messagesenhanced.push.MessagingService
import com.ubermicrostudios.messagesenhanced.push.Push
import com.ubermicrostudios.messagesenhanced.update.UpdateCheckWorker
import com.ubermicrostudios.messagesenhanced.util.EmojiCatalog
import com.ubermicrostudios.messagesenhanced.util.bindPhoneDisplay
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MessagesEnhancedApp : Application(), ImageLoaderFactory {

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var inboxCache: InboxCache
    @Inject lateinit var cookieJar: PersistentCookieJar
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var networkGate: NetworkGate

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        bindPhoneDisplay(this)
        Coil.setImageLoader(imageLoader)
        MessageNotificationIds.bindPersistence(this)
        inboxCache.warm()
        MessagingService.ensureChannels(this)
        val app = this
        appScope.launch(Dispatchers.IO) {
            runCatching { networkGate.start() }
            runCatching { prefs.warmSecure() }
            runCatching { cookieJar.warm() }
            runCatching { Push.warm(app) }
        }
        Looper.getMainLooper().queue.addIdleHandler {
            appScope.launch(Dispatchers.IO) {
                runCatching { networkGate.start() }
                runCatching { EmojiCatalog.load(app) }
                runCatching { MessagingService.ensureChannels(app) }
                runCatching {
                    if (Push.loadConfig(app) != null) Push.initSaved(app)
                }
                runCatching { UpdateCheckWorker.schedule(app) }
            }
            false
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}
