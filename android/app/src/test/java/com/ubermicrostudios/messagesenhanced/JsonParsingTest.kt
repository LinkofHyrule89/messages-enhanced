package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.SendRequest
import com.ubermicrostudios.messagesenhanced.data.net.AuthInterceptor
import com.ubermicrostudios.messagesenhanced.util.IdempotencyKeys
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParsingTest {

    private val json = JsonConfig.json

    @Test
    fun conversationJson_parsesGoFieldNames() {
        val raw = """
            {
              "ConversationID": "c1",
              "Name": "Alice",
              "IsGroup": false,
              "Participants": "[{\"name\":\"Alice\"}]",
              "LastMessageTS": 1710000000000,
              "UnreadCount": 3,
              "last_message_preview": "Latest Alice preview",
              "is_favorite": true,
              "notification_mode": "mentions",
              "last_from_me": true,
              "last_status": "OUTGOING_COMPLETE",
              "last_status_text": "Read"
            }
        """.trimIndent()
        val conv = json.decodeFromString(ConversationDto.serializer(), raw)
        assertEquals("c1", conv.conversationId)
        assertEquals("Alice", conv.name)
        assertFalse(conv.isGroup)
        assertEquals(1710000000000L, conv.lastMessageTs)
        assertEquals(3, conv.unreadCount)
        assertEquals("Latest Alice preview", conv.lastMessagePreview)
        assertTrue(conv.isFavorite)
        assertEquals("mentions", conv.notificationMode)
        assertTrue(conv.lastFromMe)
        assertEquals("Read", conv.lastStatusText)
    }

    @Test
    fun conversationList_parsesEmptyAndUnknownFields() {
        val raw = """[{"ConversationID":"g1","Name":"Crew","IsGroup":true,"extra_future":1}]"""
        val list = json.decodeFromString<List<ConversationDto>>(raw)
        assertEquals(1, list.size)
        assertEquals("g1", list[0].conversationId)
        assertTrue(list[0].isGroup)
    }

    @Test
    fun messageJson_parsesGoFieldNames() {
        val raw = """
            {
              "MessageID": "m1",
              "ConversationID": "c1",
              "SenderName": "Alice",
              "Body": "Hello",
              "TimestampMS": 42,
              "Status": "INCOMING_COMPLETE",
              "IsFromMe": false,
              "MediaID": "media-9",
              "MimeType": "image/jpeg",
              "Reactions": "[{\"emoji\":\"👍\",\"count\":2}]",
              "ReplyToID": "m0",
              "status_text": "Delivered"
            }
        """.trimIndent()
        val msg = json.decodeFromString(MessageDto.serializer(), raw)
        assertEquals("m1", msg.messageId)
        assertEquals("c1", msg.conversationId)
        assertEquals("Alice", msg.senderName)
        assertEquals("Hello", msg.body)
        assertEquals(42L, msg.timestampMs)
        assertFalse(msg.isFromMe)
        assertEquals("media-9", msg.mediaId)
        assertTrue(msg.isImageMedia())
        assertEquals("m0", msg.replyToId)
        assertEquals("Delivered", msg.statusText)
    }

    @Test
    fun sendRequest_encodesSnakeCaseAndUuid() {
        val key = IdempotencyKeys.newKey()
        val encoded = json.encodeToString(
            SendRequest(
                conversationId = "c1",
                message = "hi",
                replyToId = null,
                idempotencyKey = key,
            ),
        )
        assertTrue(encoded.contains("\"conversation_id\":\"c1\""))
        assertTrue(encoded.contains("\"message\":\"hi\""))
        assertTrue(encoded.contains("\"idempotency_key\":\"$key\""))
        assertFalse(encoded.contains("reply_to_id"))
    }
}

class IdempotencyKeyTest {
    @Test
    fun newKey_isValidUuid() {
        val key = IdempotencyKeys.newKey()
        assertTrue(IdempotencyKeys.isValid(key))
        assertEquals(36, key.length)
        assertTrue(key.contains("-"))
    }

    @Test
    fun isValid_rejectsUnsupportedCharacters() {
        assertFalse(IdempotencyKeys.isValid(""))
        assertFalse(IdempotencyKeys.isValid("has space"))
        assertFalse(IdempotencyKeys.isValid("slash/nope"))
        assertTrue(IdempotencyKeys.isValid("abc-DEF_0123.:"))
    }

    @Test
    fun twoKeys_areUnique() {
        assertTrue(IdempotencyKeys.newKey() != IdempotencyKeys.newKey())
    }
}

class AuthInterceptorTest {
    @Test
    fun rewriteToBase_keepsPathAndQuery() {
        val out = AuthInterceptor.rewriteToBase(
            "https://placeholder.invalid/api/conversations?limit=50",
            "https://messages.example.com",
        )
        assertEquals("https://messages.example.com/api/conversations?limit=50", out)
    }
}
