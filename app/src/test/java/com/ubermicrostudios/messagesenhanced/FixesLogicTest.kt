package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.net.RetryingDns
import com.ubermicrostudios.messagesenhanced.util.AppSettingsSync
import com.ubermicrostudios.messagesenhanced.util.ConvListRow
import com.ubermicrostudios.messagesenhanced.util.ConversationEncryption
import com.ubermicrostudios.messagesenhanced.util.ConversationOrder
import com.ubermicrostudios.messagesenhanced.util.Encryption
import com.ubermicrostudios.messagesenhanced.util.FailureWindow
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.InboxFilter
import com.ubermicrostudios.messagesenhanced.util.MessageStatus
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ReceiptKind
import com.ubermicrostudios.messagesenhanced.util.ReconnectDelay
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.util.ThemePalettes
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class FixesLogicTest {

    @Test
    fun inbox_dropsArchiveSpamAndTrash_keepsCustomTabs() {
        val rows = listOf("", "archive", "Archive", "spam", "Spam", "trash", "family", "work")
            .map { ConversationDto(conversationId = it, tab = it) }
        val kept = InboxFilter.inbox(rows).map { it.tab }
        assertEquals(listOf("", "family", "work"), kept)
    }

    @Test
    fun statusKind_matchesWebReceipts() {
        assertEquals(ReceiptKind.READ, MessageStatus.statusKind("OUTGOING_DISPLAYED"))
        assertEquals(ReceiptKind.READ, MessageStatus.statusKind("read"))
        assertEquals(ReceiptKind.DELIVERED, MessageStatus.statusKind("OUTGOING_DELIVERED"))
        assertEquals(ReceiptKind.DELIVERED, MessageStatus.statusKind("delivered"))
        assertEquals(ReceiptKind.SENT, MessageStatus.statusKind("OUTGOING_COMPLETE"))
        assertEquals(ReceiptKind.SENT, MessageStatus.statusKind("OUTGOING_NOT_DELIVERED_YET"))
        assertEquals(ReceiptKind.SENT, MessageStatus.statusKind("sent"))
        assertEquals(ReceiptKind.FAILED, MessageStatus.statusKind("OUTGOING_FAILED"))
        assertEquals(ReceiptKind.FAILED, MessageStatus.statusKind("OUTGOING_CANCELED"))
        assertEquals(ReceiptKind.SENDING, MessageStatus.statusKind("OUTGOING_SENDING"))
        assertEquals(
            ReceiptKind.SENT,
            MessageStatus.statusKind("OUTGOING_SENDING", messageId = "m1", posted = true),
        )
        assertNull(MessageStatus.statusKind("OUTGOING_SCHEDULED"))
        assertNull(MessageStatus.statusKind(""))
    }

    @Test
    fun listStatus_onlyWhenFromMe_andPartialGroupReadStaysDelivered() {
        assertNull(
            MessageStatus.listStatusKind(
                ConversationDto(lastFromMe = false, lastStatus = "OUTGOING_DELIVERED"),
            ),
        )
        assertEquals(
            ReceiptKind.DELIVERED,
            MessageStatus.listStatusKind(
                ConversationDto(lastFromMe = true, lastStatus = "delivered"),
            ),
        )
        val members = """[{"name":"Me","is_me":true},{"name":"Alice","number":"1"},{"name":"Bob","number":"2"}]"""
        val partial = ConversationDto(
            isGroup = true,
            lastFromMe = true,
            lastStatus = "OUTGOING_DISPLAYED",
            lastStatusText = "Read by Alice",
            participants = members,
        )
        assertEquals(ReceiptKind.DELIVERED, MessageStatus.listStatusKind(partial))
        assertEquals(
            ReceiptKind.READ,
            MessageStatus.listStatusKind(partial.copy(lastStatusText = "Read by Alice and Bob")),
        )
        assertEquals(listOf("Ada", "Bob"), MessageStatus.readByNames("Seen by Ada, Bob"))
        val member = Participants.memberByName(partial, "Alice")
        assertEquals("Alice", member?.name)
        assertEquals(
            "https://example.test/api/avatar?v=2&source=sms&phone=1",
            Participants.personAvatarUrl("https://example.test", member!!),
        )
    }

    @Test
    fun bubbleTapDetail_matchesWebStatusAndTime() {
        val zone = Calendar.getInstance().timeZone
        val now = Calendar.getInstance(zone).apply {
            set(2026, Calendar.OCTOBER, 21, 15, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 3)
        }.timeInMillis
        val clock = SimpleDateFormat("h:mm a", Locale.US).format(Date(today))
        val outgoing = MessageDto(
            isFromMe = true,
            status = "OUTGOING_DISPLAYED",
            statusText = "Read by Ada",
            timestampMs = today,
        )
        assertEquals("Read by Ada · $clock", MessageStatus.detailLine(outgoing, now))
        val incoming = MessageDto(isFromMe = false, timestampMs = today)
        assertEquals(clock, MessageStatus.detailLine(incoming, now))
        val yesterday = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DATE, -1)
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val yClock = SimpleDateFormat("h:mm a", Locale.US).format(Date(yesterday))
        assertEquals("Yesterday", Formatters.dayLabel(yesterday, now, Locale.US))
        assertEquals("Yesterday, $yClock", Formatters.detailTime(yesterday, now, Locale.US))
        assertEquals("Sent · $clock", MessageStatus.detailLine(outgoing.copy(status = "OUTGOING_COMPLETE", statusText = ""), now))
    }

    @Test
    fun avatarUrl_matchesWebQuery_groupsUseConvId_otherPlatformsSkip() {
        assertEquals(
            "https://example.test/api/avatar?v=2&source=sms&participant_id=conv%3Aabc",
            Participants.avatarUrl("https://example.test/", participantId = "conv:abc"),
        )
        assertEquals(
            "https://example.test/api/avatar?v=2&source=sms&phone=555%201212",
            Participants.avatarUrl("https://example.test", phone = "555 1212"),
        )
        assertNull(Participants.avatarUrl("https://example.test"))
        val group = ConversationDto(
            conversationId = "g1",
            isGroup = true,
            participants = """[{"name":"Me","is_me":true},{"name":"Ada","id":"p1","number":"9"}]""",
        )
        val urls = Participants.avatarRequests("https://example.test", group).map { it.url }
        assertTrue(urls[0].contains("participant_id=conv%3Ag1"))
        assertTrue(urls.any { it.contains("participant_id=p1") })
        val imessage = group.copy(sourcePlatform = "imessage")
        assertTrue(Participants.avatarRequests("https://example.test", imessage).isEmpty())
        assertEquals(97, Participants.hue("a"))
        val color = Participants.avatarColor("a")
        assertEquals(0xFF.toInt(), (color ushr 24) and 0xFF)
    }

    @Test
    fun failureWindow_needsFiveFailuresOverThirtySeconds_afterQuiet() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        now = 10_000L
        repeat(5) { assertNull(window.failure("down")) }
        now = 25_000L
        assertNull(window.failure("down"))
        now = 41_000L
        assertEquals("down", window.failure("down"))
        window.success()
        now = 80_000L
        window.onForeground()
        now = 90_000L
        assertNull(window.failure("down"))
        now = 121_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
        assertEquals(
            "Can’t reach the server. Will keep trying.",
            NetErrors.friendly(UnknownHostException("Unable to resolve host \"x\": No address associated with hostname")),
        )
    }

    @Test
    fun reconnectDelay_backsOffAndCaps() {
        val first = ReconnectDelay.millis(0, Random(1))
        assertTrue(first in 800L..1_200L)
        val capped = ReconnectDelay.millis(8, Random(2))
        assertTrue(capped in 24_000L..30_000L)
    }

    @Test
    fun themePalette_blueMatchesWebHex() {
        val colors = ThemePalettes.colors(ChatThemeDto(palette = "blue"))
        assertEquals(0xFF1F5FBF.toInt(), colors.me)
        assertEquals(0xFFA8C7FA.toInt(), colors.light)
        assertEquals(0xFF2F6FE4.toInt(), colors.accent)
        assertEquals(ThemePalettes.colors(null).me, 0xFF2F6FE4.toInt())
    }

    @Test
    fun messageEncrypted_followsWebRules() {
        val plain = MessageDto(messageId = "m1", timestampMs = 10_000)
        assertEquals(false, Encryption.messageEncrypted(plain, ConversationEncryption()))
        val listed = ConversationEncryption(e2eeIds = setOf("m1"))
        assertEquals(true, Encryption.messageEncrypted(plain, listed))
        val local = plain.copy(messageId = "local-1")
        assertEquals(false, Encryption.messageEncrypted(local, listed.copy(e2ee = true, e2eeIds = setOf("local-1"))))
        val recent = ConversationEncryption(e2ee = true, e2eeSource = "messages", loadedAt = 12_000)
        assertEquals(true, Encryption.messageEncrypted(plain, recent))
        assertEquals(
            false,
            Encryption.messageEncrypted(plain.copy(timestampMs = 1_000), recent),
        )
        assertEquals(true, Encryption.protocolEncrypted("RCS E2EE"))
        assertEquals(false, Encryption.protocolEncrypted("RCS"))
        assertEquals(true, Encryption.isRcs("RCS"))
        assertEquals(false, Encryption.isRcs("RCS E2EE"))
    }

    @Test
    fun retryingDns_retriesThenReturns() {
        var calls = 0
        val fake = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                calls++
                if (calls < 3) throw UnknownHostException(hostname)
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(1, 2, 3, 4)))
            }
        }
        val found = RetryingDns(fake, attempts = 3, sleep = {}).lookup("example.test")
        assertEquals(3, calls)
        assertEquals(1, found.size)
    }

    @Test
    fun conversationOrder_pinsFirst_andKeepsEqualTimestampOrder() {
        val pinnedA = ConversationDto(conversationId = "a", lastMessageTs = 10, localPinnedAtMs = 5)
        val pinnedB = ConversationDto(conversationId = "b", lastMessageTs = 10, googlePinned = true)
        val newer = ConversationDto(conversationId = "c", lastMessageTs = 30)
        val older = ConversationDto(conversationId = "d", lastMessageTs = 20)
        val labels = ConversationOrder.rows(listOf(newer, pinnedA, older, pinnedB)).map { row ->
            when (row) {
                is ConvListRow.Header -> "h:${row.title}"
                is ConvListRow.Item -> row.conversation.conversationId
            }
        }
        assertEquals(listOf("h:Pinned", "a", "b", "h:Other conversations", "c", "d"), labels)
        val unpinned = ConversationOrder.rows(listOf(older, newer)).map { (it as ConvListRow.Item).conversation.conversationId }
        assertEquals(listOf("c", "d"), unpinned)
        assertTrue(ConversationOrder.isPinned(pinnedB))
        assertFalse(ConversationOrder.isPinned(newer))
    }

    @Test
    fun listTime_matchesWebBuckets() {
        val zone = Calendar.getInstance().timeZone
        val now = Calendar.getInstance(zone).apply {
            set(2026, Calendar.OCTOBER, 21, 15, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        assertEquals(
            SimpleDateFormat("h:mm a", Locale.US).format(Date(today)),
            Formatters.listTime(today, now, Locale.US),
        )
        val yesterday = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DATE, -1)
        }.timeInMillis
        assertEquals("Yesterday", Formatters.listTime(yesterday, now, Locale.US))
        val weekday = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DATE, -3)
        }.timeInMillis
        val expectedDay = SimpleDateFormat("EEE", Locale.US).format(Date(weekday))
        assertEquals(expectedDay, Formatters.listTime(weekday, now, Locale.US))
        val older = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DATE, -10)
        }.timeInMillis
        assertEquals("Oct 11", Formatters.listTime(older, now, Locale.US))
        assertEquals("", Formatters.listTime(0L, now, Locale.US))
    }

    @Test
    fun appSettingsSync_serverWins_andLegacyMigratesOnce() {
        val migrated = AppSettingsSync.plan(
            serverHasTheme = false,
            serverTheme = "",
            serverHasAppearance = false,
            serverAppearance = "",
            legacyPresent = true,
            legacyPalette = "blue",
        )
        assertEquals("blue", migrated.theme)
        assertEquals(AppSettingsSync.DARK, migrated.appearance)
        assertEquals(mapOf(AppSettingsSync.THEME to "blue", AppSettingsSync.APPEARANCE to AppSettingsSync.DARK), migrated.upload)
        assertTrue(migrated.deleteLegacy)

        val server = AppSettingsSync.plan(
            serverHasTheme = true,
            serverTheme = "rose",
            serverHasAppearance = false,
            serverAppearance = "",
            legacyPresent = true,
            legacyPalette = "blue",
        )
        assertEquals("rose", server.theme)
        assertEquals(AppSettingsSync.DARK, server.appearance)
        assertTrue(server.upload.isEmpty())
        assertTrue(server.deleteLegacy)

        val fresh = AppSettingsSync.plan(false, "", false, "", false, "")
        assertEquals("", fresh.theme)
        assertEquals(AppSettingsSync.DARK, fresh.appearance)
        assertTrue(fresh.upload.isEmpty())
        assertFalse(fresh.deleteLegacy)

        val blankLegacy = AppSettingsSync.plan(false, "", false, "", true, "  ")
        assertEquals("", blankLegacy.theme)
        assertTrue(blankLegacy.upload.isEmpty())
        assertTrue(blankLegacy.deleteLegacy)
    }

    @Test
    fun themePalettes_matchWebCss() {
        assertEquals(9, ThemePalettes.all.size)
        val def = ThemeColors.Default
        assertEquals(0xFF3E8BFF.toInt(), def.accent)
        assertEquals(0xFF262B33.toInt(), def.them)
        assertEquals(0xFF9CC3FF.toInt(), def.light)
        assertEquals(0xFF0D0F12.toInt(), def.surface)
        assertEquals(0xFF2F6FE4.toInt(), def.me)
        assertEquals("Peach", ThemePalettes.byId("coral")?.name)
        assertEquals("Pink", ThemePalettes.byId("rose")?.name)
        assertEquals(listOf("blue", "sage"), ThemePalettes.offered(listOf("blue", "sage", "nope")).map { it.id })
    }
}
