package com.ubermicrostudios.messagesenhanced.data.repo

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.ubermicrostudios.messagesenhanced.data.model.PushSettingsRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushSubscribeRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushTestRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushUnsubscribeRequest
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.push.FcmConfig
import com.ubermicrostudios.messagesenhanced.push.Push
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MessagesApi,
    private val prefs: AppPreferences,
) {
    fun notificationsEnabled(): Boolean = prefs.notificationsEnabled
    fun hideMessageText(): Boolean = prefs.hideMessageText

    suspend fun subscribe(hideText: Boolean = prefs.hideMessageText): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val config = api.pushConfig()
            if (!config.available || config.fcm == null) {
                error("Push is not available on this server.")
            }
            val fcm = FcmConfig.from(config.fcm) ?: error("Server FCM config is incomplete.")
            Push.saveConfig(context, fcm)
            Push.init(context, fcm)
            val token = FirebaseMessaging.getInstance().token.await()
            Push.saveToken(context, token, markChanged = false)
            val endpoint = Push.fcmEndpoint(token)
            val resp = api.pushSubscribe(
                PushSubscribeRequest(
                    endpoint = endpoint,
                    label = "Messages Enhanced (Android)",
                    hideText = hideText,
                ),
            )
            if (!resp.isSuccessful) error("Subscribe failed (HTTP ${resp.code()}).")
            prefs.notificationsEnabled = true
            prefs.hideMessageText = hideText
        }
    }

    suspend fun unsubscribe(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = Push.savedToken(context)
            if (token.isNotBlank()) {
                try {
                    api.pushUnsubscribe(PushUnsubscribeRequest(Push.fcmEndpoint(token)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The server call can fail. The phone still drops its subscription.
                }
            }
            Push.clearToken(context)
            prefs.notificationsEnabled = false
            Result.success(Unit)
        } catch (e: CancellationException) {
            Push.clearToken(context)
            prefs.notificationsEnabled = false
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun resubscribeIfEnabled(token: String) {
        if (!prefs.notificationsEnabled) {
            Push.saveToken(context, token, markChanged = true)
            return
        }
        Push.saveToken(context, token, markChanged = false)
        runCatchingCancellable {
            api.pushSubscribe(
                PushSubscribeRequest(
                    endpoint = Push.fcmEndpoint(token),
                    label = "Messages Enhanced (Android)",
                    hideText = prefs.hideMessageText,
                ),
            )
        }
    }

    suspend fun setHideText(hide: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val token = Push.savedToken(context)
            if (token.isBlank()) error("Not subscribed.")
            val resp = api.pushSettings(
                PushSettingsRequest(endpoint = Push.fcmEndpoint(token), hideText = hide),
            )
            if (!resp.isSuccessful) error("Could not update hide-text (HTTP ${resp.code()}).")
            prefs.hideMessageText = hide
        }
    }

    suspend fun sendTest(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val token = Push.savedToken(context)
            if (token.isBlank()) error("Not subscribed.")
            val resp = api.pushTest(PushTestRequest(Push.fcmEndpoint(token)))
            if (!resp.isSuccessful) error("Test failed (HTTP ${resp.code()}).")
        }
    }
}
