package com.ubermicrostudios.messagesenhanced.data.net

import com.ubermicrostudios.messagesenhanced.data.model.ArchiveRequest
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ContactsResponse
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationIdRequest
import com.ubermicrostudios.messagesenhanced.data.model.ConversationMetaDto
import com.ubermicrostudios.messagesenhanced.data.model.StartConversationRequest
import com.ubermicrostudios.messagesenhanced.data.model.StartConversationResponse
import com.ubermicrostudios.messagesenhanced.data.model.LinkPreviewDto
import com.ubermicrostudios.messagesenhanced.data.model.MuteRequest
import com.ubermicrostudios.messagesenhanced.data.model.GifPage
import com.ubermicrostudios.messagesenhanced.data.model.GoogleCookiesRequest
import com.ubermicrostudios.messagesenhanced.data.model.GrokStatus
import com.ubermicrostudios.messagesenhanced.data.model.GrokUpdate
import com.ubermicrostudios.messagesenhanced.data.model.HealthResponse
import com.ubermicrostudios.messagesenhanced.data.model.PairingResponse
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.NotificationModeRequest
import com.ubermicrostudios.messagesenhanced.data.model.PinRequest
import com.ubermicrostudios.messagesenhanced.data.model.PinResponse
import com.ubermicrostudios.messagesenhanced.data.model.ProfileDto
import com.ubermicrostudios.messagesenhanced.data.model.PushConfig
import com.ubermicrostudios.messagesenhanced.data.model.PushSettingsRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushSubscribeRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushTestRequest
import com.ubermicrostudios.messagesenhanced.data.model.PushUnsubscribeRequest
import com.ubermicrostudios.messagesenhanced.data.model.DeleteMessageRequest
import com.ubermicrostudios.messagesenhanced.data.model.DeleteMessageResponse
import com.ubermicrostudios.messagesenhanced.data.model.ReactRequest
import com.ubermicrostudios.messagesenhanced.data.model.ReadRequest
import com.ubermicrostudios.messagesenhanced.data.model.StarRequest
import com.ubermicrostudios.messagesenhanced.data.model.StarsResponse
import com.ubermicrostudios.messagesenhanced.data.model.RefreshRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendGifRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendRequest
import com.ubermicrostudios.messagesenhanced.data.model.SendResponse
import com.ubermicrostudios.messagesenhanced.data.model.SettingsEnvelope
import com.ubermicrostudios.messagesenhanced.data.model.ThemePutRequest
import com.ubermicrostudios.messagesenhanced.data.model.ThemeWriteResponse
import com.ubermicrostudios.messagesenhanced.data.model.ThemesResponse
import com.ubermicrostudios.messagesenhanced.data.model.TypingResponse
import com.ubermicrostudios.messagesenhanced.data.model.WallpapersResponse
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface MessagesApi {
    @GET("/api/conversations")
    suspend fun listConversations(
        @Query("limit") limit: Int = 200,
        @Query("tab") tab: String? = null,
    ): List<ConversationDto>

    @GET("/api/conversations/{id}/messages")
    suspend fun listMessages(
        @Path("id") conversationId: String,
        @Query("limit") limit: Int = 100,
        @Query("after") after: Long? = null,
    ): List<MessageDto>

    /** Conversation-name hits. The web app also calls this from the search box. */
    @GET("/api/search")
    suspend fun searchConversations(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
    ): List<ConversationDto>

    /** Message-body hits. Same rows as a thread message. */
    @GET("/api/search/messages")
    suspend fun searchMessages(
        @Query("q") query: String,
        @Query("limit") limit: Int = 60,
    ): List<MessageDto>

    @GET("/api/app/profile")
    suspend fun profile(): ProfileDto

    @GET("/api/app/contacts")
    suspend fun appContacts(): ContactsResponse

    @GET("/api/contacts")
    suspend fun contacts(
        @Query("q") query: String = "",
        @Query("limit") limit: Int = 100,
    ): List<ContactDto>

    @POST("/api/app/conversations/start")
    suspend fun startConversation(@Body body: StartConversationRequest): StartConversationResponse

    @POST("/api/send")
    suspend fun send(@Body body: SendRequest): SendResponse

    @POST("/api/send-gif")
    suspend fun sendGif(@Body body: SendGifRequest): SendResponse

    @GET("/api/gifs")
    suspend fun searchGifs(
        @Query("q") query: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
    ): GifPage

    @GET("/api/gifs/trending")
    suspend fun trendingGifs(
        @Query("limit") limit: Int,
        @Query("page") page: Int,
    ): GifPage

    @Multipart
    @POST("/api/send-media")
    suspend fun sendMedia(
        @Part("conversation_id") conversationId: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("caption") caption: RequestBody,
        @Part("reply_to_id") replyToId: RequestBody,
        @Part("idempotency_key") idempotencyKey: RequestBody,
    ): SendResponse

    @GET("/api/media/{id}")
    suspend fun media(@Path(value = "id", encoded = true) id: String): ResponseBody

    @POST("/api/react")
    suspend fun react(@Body body: ReactRequest): Response<ResponseBody>

    @GET("/api/app/stars")
    suspend fun stars(@Query("conversation_id") conversationId: String): StarsResponse

    @POST("/api/app/stars")
    suspend fun setStar(@Body body: StarRequest): Response<ResponseBody>

    @POST("/api/app/messages/delete")
    suspend fun deleteMessage(@Body body: DeleteMessageRequest): DeleteMessageResponse

    @POST("/api/app/conversations/read")
    suspend fun markRead(@Body body: ReadRequest): Response<ResponseBody>

    @POST("/api/conversations/{id}/notification-mode")
    suspend fun setNotificationMode(
        @Path("id") conversationId: String,
        @Body body: NotificationModeRequest,
    ): ConversationDto

    @GET("/api/app/push/config")
    suspend fun pushConfig(): PushConfig

    @POST("/api/app/push/subscribe")
    suspend fun pushSubscribe(@Body body: PushSubscribeRequest): Response<ResponseBody>

    @POST("/api/app/push/unsubscribe")
    suspend fun pushUnsubscribe(@Body body: PushUnsubscribeRequest): Response<ResponseBody>

    @POST("/api/app/push/settings")
    suspend fun pushSettings(@Body body: PushSettingsRequest): Response<ResponseBody>

    @POST("/api/app/push/test")
    suspend fun pushTest(@Body body: PushTestRequest): Response<ResponseBody>

    @GET("/api/app/typing")
    suspend fun typing(@Query("conversation_id") conversationId: String? = null): TypingResponse

    @GET("/api/app/conversations/meta")
    suspend fun conversationMeta(@Query("conversation_id") conversationId: String): ConversationMetaDto

    @GET("/api/app/themes")
    suspend fun themes(): ThemesResponse

    @PUT("/api/app/theme")
    suspend fun putTheme(@Body body: ThemePutRequest): ThemeWriteResponse

    @DELETE("/api/app/theme")
    suspend fun deleteTheme(@Query("conversation_id") conversationId: String): Response<ResponseBody>

    @GET("/api/app/theme")
    suspend fun theme(@Query("conversation_id") conversationId: String): ThemeWriteResponse

    @Multipart
    @POST("/api/app/theme/background")
    suspend fun uploadThemeBackground(
        @Part("conversation_id") conversationId: RequestBody,
        @Part file: MultipartBody.Part,
    ): ThemeWriteResponse

    @GET("/api/app/wallpapers")
    suspend fun wallpapers(): WallpapersResponse

    @POST("/api/app/conversations/pin")
    suspend fun pinConversation(@Body body: PinRequest): PinResponse

    @GET("/api/app/settings")
    suspend fun appSettings(): SettingsEnvelope

    @PUT("/api/app/settings")
    suspend fun putSettings(@Body body: JsonObject): SettingsEnvelope

    @POST("/api/app/refresh")
    suspend fun startRefresh(@Body body: RefreshRequest): Response<ResponseBody>

    @POST("/api/app/conversations/mute")
    suspend fun muteConversation(@Body body: MuteRequest): Response<ResponseBody>

    @POST("/api/app/conversations/archive")
    suspend fun archiveConversation(@Body body: ArchiveRequest): Response<ResponseBody>

    @POST("/api/app/conversations/block")
    suspend fun blockConversation(@Body body: com.ubermicrostudios.messagesenhanced.data.model.BlockRequest): Response<ResponseBody>

    @POST("/api/app/messages/action")
    suspend fun messageAction(@Body body: com.ubermicrostudios.messagesenhanced.data.model.MessageActionRequest): Response<ResponseBody>

    @POST("/api/app/conversations/trash")
    suspend fun trashConversation(@Body body: ConversationIdRequest): Response<ResponseBody>

    @POST("/api/app/conversations/refresh")
    suspend fun refreshConversation(@Body body: ConversationIdRequest): Response<ResponseBody>

    @GET("/api/link-preview")
    suspend fun linkPreview(@Query("url") url: String): LinkPreviewDto

    @GET("/api/app/grok")
    suspend fun grokStatus(): GrokStatus

    @POST("/api/app/grok")
    suspend fun updateGrok(@Body body: GrokUpdate): GrokStatus

    /** Server health monitor. Failures are ignored by [com.ubermicrostudios.messagesenhanced.data.repo.HealthRepository]. */
    @GET("/api/app/health")
    suspend fun appHealth(): HealthResponse

    /** Google Messages pairing and connection. Same failure policy as [appHealth]. */
    @GET("/api/app/pairing")
    suspend fun appPairing(): PairingResponse

    /**
     * POST Google sign-in cookies so the server can pair Messages for Web.
     * Cookie values must never be logged. Returns 202 with the same pairing payload as [appPairing].
     */
    @POST("/api/app/google/cookies")
    suspend fun submitGoogleCookies(@Body body: GoogleCookiesRequest): PairingResponse

    /** Clears the encrypted Google cookie vault on the server. */
    @POST("/api/app/google/cookies/forget")
    suspend fun forgetGoogleCookies(): PairingResponse
}
