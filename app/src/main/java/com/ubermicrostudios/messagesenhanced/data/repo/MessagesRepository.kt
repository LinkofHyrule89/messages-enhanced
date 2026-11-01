package com.ubermicrostudios.messagesenhanced.data.repo

import com.ubermicrostudios.messagesenhanced.data.model.DeleteMessageRequest
import com.ubermicrostudios.messagesenhanced.data.model.DeleteMessageResponse
import com.ubermicrostudios.messagesenhanced.data.model.ArchiveRequest
import com.ubermicrostudios.messagesenhanced.data.model.ContactsResponse
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationIdRequest
import com.ubermicrostudios.messagesenhanced.data.model.StartConversationRequest
import com.ubermicrostudios.messagesenhanced.data.model.StartConversationResponse
import com.ubermicrostudios.messagesenhanced.data.model.GifPage
import com.ubermicrostudios.messagesenhanced.data.model.GrokStatus
import com.ubermicrostudios.messagesenhanced.data.model.MuteRequest
import com.ubermicrostudios.messagesenhanced.data.model.GrokUpdate
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.NotificationModeRequest
import com.ubermicrostudios.messagesenhanced.data.model.PinRequest
import com.ubermicrostudios.messagesenhanced.data.model.PinResponse
import com.ubermicrostudios.messagesenhanced.data.model.ProfileDto
import com.ubermicrostudios.messagesenhanced.data.model.ReactRequest
import com.ubermicrostudios.messagesenhanced.data.model.ReadRequest
import com.ubermicrostudios.messagesenhanced.data.model.StarRequest
import com.ubermicrostudios.messagesenhanced.data.model.StarsResponse
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.RefreshRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendGifRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendResponse
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.util.IdempotencyKeys
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.ubermicrostudios.messagesenhanced.util.InboxFilter
import com.ubermicrostudios.messagesenhanced.util.InboxSearch
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteSearch(
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val messageError: String? = null,
)

@Singleton
class MessagesRepository @Inject constructor(
    private val api: MessagesApi,
) {
    /** Default inbox: same limit the web app requests, then the client-side tab filter. */
    suspend fun conversations(limit: Int = 100): List<ConversationDto> =
        InboxFilter.inbox(api.listConversations(limit = limit, tab = null))

    suspend fun conversationMeta(conversationId: String) = api.conversationMeta(conversationId)

    /** Server returns newest first (the web app reverses it); show oldest to newest. */
    suspend fun messages(conversationId: String, limit: Int = 80): List<MessageDto> =
        api.listMessages(conversationId, limit).asReversed().sortedBy { it.timestampMs }

    /**
     * Messages strictly after [timestampMs], counted the way the web jump does,
     * then widened by 20 and clamped to 80..1000.
     */
    suspend fun jumpWindow(conversationId: String, timestampMs: Long): Int {
        val afterMs = maxOf(1L, timestampMs - 1)
        val count = runCatchingCancellable {
            api.listMessages(conversationId, limit = 1000, after = afterMs).size
        }.getOrDefault(20)
        return InboxSearch.messageWindow(count)
    }

    suspend fun profile(): ProfileDto = api.profile()

    /** Web Start chat: `/api/app/contacts`, falling back to `/api/contacts`. */
    suspend fun contacts(): ContactsResponse =
        runCatchingCancellable { api.appContacts() }.getOrElse {
            val all = api.contacts(limit = 500)
            ContactsResponse(top = all.take(8), all = all)
        }

    suspend fun startConversation(numbers: List<String>, groupName: String = ""): StartConversationResponse =
        api.startConversation(StartConversationRequest(numbers, groupName))

    /**
     * Web search: conversation hits and message hits are independent.
     * A conversation-search failure still shows message hits, and the reverse.
     */
    suspend fun search(query: String): RemoteSearch {
        val conversations = runCatchingCancellable { api.searchConversations(query, limit = 20) }.getOrDefault(emptyList())
        val messages = runCatchingCancellable { api.searchMessages(query, limit = 60) }
        return if (messages.isSuccess) {
            RemoteSearch(conversations, messages.getOrThrow())
        } else {
            RemoteSearch(
                conversations = conversations,
                messages = emptyList(),
                messageError = NetErrors.friendly(messages.exceptionOrNull() ?: IllegalStateException("search")),
            )
        }
    }

    data class MediaFile(val bytes: ByteArray, val mime: String)

    /** GET /api/media/{id}. Throws "couldn't load the attachment" or "attachment too large". */
    suspend fun downloadMedia(messageId: String): MediaFile {
        val body = runCatchingCancellable { api.media(messageId) }.getOrElse {
            throw IllegalStateException("couldn't load the attachment")
        }
        val mime = body.contentType()?.toString()?.substringBefore(';')?.trim().orEmpty()
            .ifBlank { "application/octet-stream" }
        val bytes = body.bytes()
        if (bytes.size > MessageActions.MAX_MEDIA_BYTES) {
            throw IllegalStateException("attachment too large")
        }
        return MediaFile(bytes, mime)
    }

    /** POST /api/send-media. Caption stays empty when the web sends the caption as a later text. */
    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        caption: String = "",
        replyToId: String = "",
        idempotencyKey: String = IdempotencyKeys.newKey(),
    ): SendResponse {
        val type = mime.ifBlank { "application/octet-stream" }.toMediaType()
        val file = MultipartBody.Part.createFormData("file", filename, bytes.toRequestBody(type))
        val plain = "text/plain".toMediaType()
        return api.sendMedia(
            conversationId.toRequestBody(plain),
            file,
            caption.toRequestBody(plain),
            replyToId.toRequestBody(plain),
            idempotencyKey.toRequestBody(plain),
        )
    }

    suspend fun react(conversationId: String, messageId: String, emoji: String) {
        requireOk(
            api.react(
                ReactRequest(
                    conversationId = conversationId,
                    messageId = messageId,
                    emoji = emoji,
                    action = "add",
                ),
            ),
            "Couldn't react",
        )
    }

    suspend fun stars(conversationId: String): StarsResponse = api.stars(conversationId)

    suspend fun setStar(messageId: String, conversationId: String, starred: Boolean) {
        requireOk(
            api.setStar(StarRequest(messageId = messageId, conversationId = conversationId, starred = starred)),
            "Couldn't star",
        )
    }

    suspend fun deleteMessage(messageId: String): DeleteMessageResponse =
        runCatchingCancellable { api.deleteMessage(DeleteMessageRequest(messageId)) }.getOrElse { explain(it) }

    private fun explain(error: Throwable): Nothing {
        if (error is CancellationException) throw error
        if (error is HttpException) {
            val raw = runCatchingCancellable { error.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val parsed = runCatchingCancellable {
                JsonConfig.json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!parsed.isNullOrBlank()) error(parsed)
        }
        val message = error.message.orEmpty()
        error(message.ifBlank { "request failed" })
    }

    /** GET /api/gifs or /api/gifs/trending. Throws the server's error text (including a missing GIF key). */
    suspend fun gifs(query: String, page: Int, limit: Int = 32): GifPage = runCatchingCancellable {
        if (query.isBlank()) api.trendingGifs(limit, page) else api.searchGifs(query, limit, page)
    }.getOrElse { explain(it) }

    /** POST /api/send-gif. Empty caption and reply are omitted, the way the web client omits them. */
    suspend fun sendGif(
        conversationId: String,
        url: String,
        caption: String = "",
        replyToId: String = "",
        idempotencyKey: String = IdempotencyKeys.newKey(),
    ): SendResponse = runCatchingCancellable {
        api.sendGif(
            SendGifRequest(
                conversationId = conversationId,
                url = url,
                caption = caption.takeIf { it.isNotBlank() },
                replyToId = replyToId.takeIf { it.isNotBlank() },
                idempotencyKey = idempotencyKey,
            ),
        )
    }.getOrElse { explain(it) }

    suspend fun send(
        conversationId: String,
        text: String,
        replyToId: String? = null,
        idempotencyKey: String = IdempotencyKeys.newKey(),
    ): SendResponse = api.send(
        SendRequest(
            conversationId = conversationId,
            message = text,
            replyToId = replyToId?.takeIf { it.isNotBlank() },
            idempotencyKey = idempotencyKey,
        ),
    )

    suspend fun markRead(conversationId: String) {
        runCatchingCancellable { api.markRead(ReadRequest(conversationId)) }
    }

    suspend fun setNotificationMode(conversationId: String, mode: String): ConversationDto =
        api.setNotificationMode(conversationId, NotificationModeRequest(mode))

    suspend fun pin(conversationId: String, pinned: Boolean): PinResponse =
        api.pinConversation(PinRequest(conversationId, pinned))

    /** POST /api/app/conversations/mute — the same call the web header menu uses. */
    suspend fun setMuted(conversationId: String, muted: Boolean) {
        requireOk(api.muteConversation(MuteRequest(conversationId, muted)), "Couldn't update mute")
    }

    suspend fun archive(conversationId: String) {
        requireOk(api.archiveConversation(ArchiveRequest(conversationId, archived = true)), "Couldn't archive")
    }

    suspend fun block(conversationId: String, blocked: Boolean, report: Boolean) {
        requireOk(
            api.blockConversation(com.ubermicrostudios.messagesenhanced.data.model.BlockRequest(conversationId, blocked, report)),
            if (blocked) "Couldn't block" else "Couldn't unblock",
        )
    }

    suspend fun messageAction(messageId: String, action: String) {
        requireOk(
            api.messageAction(com.ubermicrostudios.messagesenhanced.data.model.MessageActionRequest(messageId, action)),
            "Couldn't update the message",
        )
    }

    suspend fun trash(conversationId: String) {
        requireOk(api.trashConversation(ConversationIdRequest(conversationId)), "Couldn't move it to trash")
    }

    /**
     * POST /api/app/conversations/refresh. Returns the message to show.
     * The caller reloads the thread after a successful start.
     */
    suspend fun refreshConversation(conversationId: String): String {
        val response = api.refreshConversation(ConversationIdRequest(conversationId))
        val raw = (if (response.isSuccessful) response.body() else response.errorBody())?.string().orEmpty()
        val code = response.code()
        val obj = runCatchingCancellable { JsonConfig.json.parseToJsonElement(raw).jsonObject }.getOrNull()
        return when (code) {
            200, 202 -> "Refresh started."
            429 -> {
                val sec = obj?.get("retry_after_sec")?.jsonPrimitive?.intOrNull
                val err = obj?.get("error")?.jsonPrimitive?.contentOrNull
                err ?: "Refreshed recently. Try again in ${sec ?: 60}s."
            }
            else -> {
                val err = obj?.get("error")?.jsonPrimitive?.contentOrNull
                if (err.isNullOrBlank()) "Couldn't refresh (HTTP $code)." else err
            }
        }.also { message ->
            if (code != 200 && code != 202) error(message)
        }
    }

    private fun requireOk(response: retrofit2.Response<okhttp3.ResponseBody>, fallback: String) {
        val raw = (if (response.isSuccessful) response.body() else response.errorBody())?.string().orEmpty()
        if (response.isSuccessful) return
        val err = runCatchingCancellable {
            JsonConfig.json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        error(if (err.isNullOrBlank()) "$fallback (HTTP ${response.code()})." else err)
    }

    /** POST /api/app/refresh {"scope":"all"}. The string is the message to show. */
    suspend fun refreshEverything(): String {
        val response = api.startRefresh(RefreshRequest("all"))
        val raw = (if (response.isSuccessful) response.body() else response.errorBody())?.string().orEmpty()
        val code = response.code()
        val obj = runCatchingCancellable { JsonConfig.json.parseToJsonElement(raw).jsonObject }.getOrNull()
        return when (code) {
            200, 202 -> "Refresh started."
            429 -> {
                val sec = obj?.get("retry_after_sec")?.jsonPrimitive?.intOrNull ?: 60
                val mins = maxOf(1, (sec + 59) / 60)
                val unit = if (mins == 1) "minute" else "minutes"
                "Refreshed recently. Try again in $mins $unit."
            }
            503 -> "Refresh is unavailable."
            else -> {
                val err = obj?.get("error")?.jsonPrimitive?.contentOrNull
                if (err.isNullOrBlank()) "Couldn't refresh (HTTP $code)." else "Couldn't refresh: $err"
            }
        }
    }

    suspend fun grokStatus(): GrokStatus = api.grokStatus()


    /** Server default is on when the key is absent. */
    suspend fun autoGoogleRepairEnabled(): Boolean {
        val settings = api.appSettings().settings
        val el = settings["auto_google_repair"] as? kotlinx.serialization.json.JsonPrimitive ?: return true
        return when {
            el.booleanOrNull != null -> el.booleanOrNull == true
            el.contentOrNull.equals("false", true) || el.contentOrNull == "0" || el.contentOrNull.equals("off", true) -> false
            else -> true
        }
    }

    /** PUT merges onto the current settings object (server replaces wholesale). */
    suspend fun setAutoGoogleRepair(enabled: Boolean): Boolean {
        val current = api.appSettings().settings.toMutableMap()
        current["auto_google_repair"] = JsonPrimitive(enabled)
        val saved = api.putSettings(JsonObject(current)).settings
        val el = saved["auto_google_repair"] as? kotlinx.serialization.json.JsonPrimitive
        return when {
            el == null -> true
            el.booleanOrNull != null -> el.booleanOrNull == true
            else -> !el.contentOrNull.equals("false", true)
        }
    }

    suspend fun forgetGoogleSession() = api.forgetGoogleCookies()

    suspend fun saveGrok(enabled: Boolean, trigger: String, groqEnabled: Boolean?): GrokStatus =
        api.updateGrok(GrokUpdate(enabled = enabled, trigger = trigger, groqEnabled = groqEnabled))
}
