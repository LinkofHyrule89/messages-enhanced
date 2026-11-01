package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.GoogleConnectionDto
import com.ubermicrostudios.messagesenhanced.data.model.HealthCheckDto
import com.ubermicrostudios.messagesenhanced.data.model.HealthResponse
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.PairingResponse
import com.ubermicrostudios.messagesenhanced.data.model.PairingStateDto
import com.ubermicrostudios.messagesenhanced.util.HealthBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class HealthBannerTest {

    private val clock: (Long) -> String = { "9:00 AM" }

    @Test
    fun healthyChecks_andConnected_showNothing() {
        val health = HealthResponse(
            checks = listOf(
                HealthCheckDto(name = "google", ok = true, downSinceMs = 1_000),
                HealthCheckDto(name = "vpn", ok = true),
            ),
        )
        val pairing = paired(connected = true, phone = true)
        assertNull(HealthBanner.banner(health, pairing, nowMs = 90_000, clock, unstableSinceMs = null))
        assertNull(HealthBanner.nextClock(null, pairing, nowMs = 90_000))
    }

    @Test
    fun noSamples_showNothing() {
        assertNull(HealthBanner.banner(null, null, nowMs = 90_000, clock, unstableSinceMs = null))
        assertNull(HealthBanner.nextClock(null, null, nowMs = 0))
    }

    @Test
    fun vpnDown_after30s_usesClockOfDownSince_andNoDetail() {
        var seen = -1L
        val health = HealthResponse(
            checks = listOf(HealthCheckDto(name = "vpn", ok = false, downSinceMs = 5_000, alerted = true, checkedMs = 99)),
        )
        val banner = HealthBanner.banner(
            health,
            paired(needsPairing = true),
            nowMs = 35_000,
            formatClock = { seen = it; "9:00 AM" },
            unstableSinceMs = null,
        )
        assertEquals(5_000L, seen)
        assertEquals(
            "VPN is down since 9:00 AM. New messages may be delayed.",
            banner!!.text,
        )
        assertEquals(HealthBanner.Severity.DOWN, banner.severity)
        assertFalse(HealthBanner.opensGoogleReconnect(banner))
    }

    @Test
    fun googleDown_includesDetail_otherNamesUseTheSameWording() {
        val health = HealthResponse(
            checks = listOf(
                HealthCheckDto(name = "google", ok = false, detail = "needs pairing", downSinceMs = 5_000),
            ),
        )
        val banner = HealthBanner.banner(health, null, nowMs = 50_000, clock, null)
        assertEquals(
            "Google Messages is disconnected (needs pairing) since 9:00 AM. New messages may be delayed. Tap to reconnect Google Messages.",
            banner!!.text,
        )
        assertTrue(HealthBanner.opensGoogleReconnect(banner))
        val other = HealthBanner.banner(
            HealthResponse(checks = listOf(HealthCheckDto(name = "VPN", ok = false, downSinceMs = 5_000))),
            null,
            nowMs = 55_000,
            clock,
            null,
        )
        assertEquals(
            "Google Messages is disconnected since 9:00 AM. New messages may be delayed. Tap to reconnect Google Messages.",
            other!!.text,
        )
        assertTrue(HealthBanner.opensGoogleReconnect(other))
    }

    @Test
    fun downShorterThan30s_orMissingSince_isSkipped() {
        val fresh = HealthCheckDto(name = "vpn", ok = false, detail = "wg0 is down", downSinceMs = 1_000)
        val missing = HealthCheckDto(name = "google", ok = false, detail = "disconnected", downSinceMs = 0)
        assertNull(
            HealthBanner.banner(
                HealthResponse(checks = listOf(fresh, missing)),
                null,
                nowMs = 30_999,
                clock,
                null,
            ),
        )
        val shown = HealthBanner.banner(
            HealthResponse(checks = listOf(fresh)),
            null,
            nowMs = 31_000,
            clock,
            null,
        )
        assertEquals(
            "VPN is down (wg0 is down) since 9:00 AM. New messages may be delayed.",
            shown!!.text,
        )
    }

    @Test
    fun failingChecks_joinWithASpace_andSkipTheFreshOne() {
        val health = HealthResponse(
            checks = listOf(
                HealthCheckDto(name = "vpn", ok = false, downSinceMs = 1_000),
                HealthCheckDto(name = "google", ok = false, detail = "disconnected", downSinceMs = 2_000),
                HealthCheckDto(name = "google", ok = false, detail = "too fresh", downSinceMs = 50_000),
            ),
        )
        val banner = HealthBanner.banner(health, null, nowMs = 50_000, clock, null)
        assertEquals(
            "VPN is down since 9:00 AM. New messages may be delayed. " +
                "Google Messages is disconnected (disconnected) since 9:00 AM. New messages may be delayed. Tap to reconnect Google Messages.",
            banner!!.text,
        )
        assertEquals(HealthBanner.Severity.DOWN, banner.severity)
    }

    @Test
    fun healthLines_winOverPairing() {
        val health = HealthResponse(
            checks = listOf(HealthCheckDto(name = "vpn", ok = false, downSinceMs = 1_000)),
        )
        val banner = HealthBanner.banner(health, paired(connected = false), nowMs = 31_000, clock, unstableSinceMs = 0)
        assertTrue(banner!!.text.startsWith("VPN is down"))
        assertEquals(HealthBanner.Severity.DOWN, banner.severity)
    }

    @Test
    fun needsPairing_textMentionsReconnectOrTap() {
        val banner = HealthBanner.banner(null, paired(needsPairing = true), 0, clock, null)!!
        val text = banner.text.lowercase()
        assertTrue(text.contains("reconnect") || text.contains("tap"))
        assertTrue(HealthBanner.opensGoogleReconnect(banner))
    }

    @Test
    fun needsPairing_andNeedsRepair_showImmediately() {
        val pairing = "Not paired with your phone yet. Tap to reconnect."
        val needsPair = HealthBanner.banner(null, paired(needsPairing = true, connected = false), 0, clock, null)
        assertEquals(pairing, needsPair!!.text)
        assertEquals(HealthBanner.Severity.WARN, needsPair.severity)
        assertTrue(needsPair.text.contains("reconnect") || needsPair.text.contains("tap", ignoreCase = true))
        assertTrue(HealthBanner.opensGoogleReconnect(needsPair))
        assertNull(HealthBanner.nextClock(null, paired(needsPairing = true), 0))

        val repair = paired(connected = true, needsRepair = true, phone = false)
        val banner = HealthBanner.banner(null, repair, nowMs = 0, clock, unstableSinceMs = null)
        assertEquals(pairing, banner!!.text)
        assertEquals(HealthBanner.Severity.WARN, banner.severity)
        assertTrue(HealthBanner.opensGoogleReconnect(banner))
        assertNull(HealthBanner.nextClock(null, repair, 5_000))
    }

    @Test
    fun phoneOffline_showsImmediately_onlyWhenExplicitlyFalse() {
        val offline = HealthBanner.banner(null, paired(connected = true, phone = false), 0, clock, null)
        assertEquals(
            "Connected, but your phone isn't responding (check it's online)",
            offline!!.text,
        )
        assertEquals(HealthBanner.Severity.WARN, offline.severity)
        assertFalse(HealthBanner.opensGoogleReconnect(offline))
        assertNull(HealthBanner.banner(null, paired(connected = true, phone = true), 0, clock, null))
        assertNull(HealthBanner.banner(null, paired(connected = true, phone = null), 0, clock, null))
        assertNull(HealthBanner.nextClock(null, paired(connected = true, phone = false), 0))
    }

    @Test
    fun connected_winsOverConnectingFlag_andEmojiState() {
        val pairing = PairingResponse(
            google = GoogleConnectionDto(connected = true, connecting = true, phoneResponding = true),
            pairing = PairingStateDto(state = "emoji"),
        )
        assertNull(HealthBanner.banner(null, pairing, nowMs = 90_000, clock, unstableSinceMs = 0))
        assertNull(HealthBanner.nextClock(null, pairing, 90_000))
    }

    @Test
    fun connecting_andDisconnected_wait30s_thenShow() {
        val connecting = PairingResponse(
            google = GoogleConnectionDto(connecting = true),
            pairing = PairingStateDto(state = "idle"),
        )
        var tracked = HealthBanner.nextClock(null, connecting, nowMs = 1_000)
        assertEquals(HealthBanner.Unstable.CONNECTING, tracked!!.kind)
        assertEquals(1_000L, tracked!!.sinceMs)
        assertNull(HealthBanner.banner(null, connecting, nowMs = 1_000, clock, tracked!!.sinceMs))
        assertNull(HealthBanner.banner(null, connecting, nowMs = 30_999, clock, tracked!!.sinceMs))
        val shown = HealthBanner.banner(null, connecting, nowMs = 31_000, clock, tracked!!.sinceMs)
        assertEquals("Connecting to Google Messages…", shown!!.text)
        assertEquals(HealthBanner.Severity.WARN, shown.severity)
        assertTrue(shown.text.endsWith("\u2026"))
        assertTrue(HealthBanner.opensGoogleReconnect(shown))

        tracked = HealthBanner.nextClock(tracked, connecting, nowMs = 40_000)
        assertEquals(1_000L, tracked!!.sinceMs)

        val dropped = PairingResponse(google = GoogleConnectionDto(connected = false), pairing = PairingStateDto(state = "error"))
        tracked = HealthBanner.nextClock(tracked, dropped, nowMs = 50_000)
        assertEquals(HealthBanner.Unstable.DISCONNECTED, tracked!!.kind)
        assertEquals(50_000L, tracked!!.sinceMs)
        assertNull(HealthBanner.banner(null, dropped, nowMs = 94_999, clock, tracked!!.sinceMs))
        val down = HealthBanner.banner(null, dropped, nowMs = 95_000, clock, tracked!!.sinceMs)
        assertEquals("Disconnected from Google Messages. Tap to reconnect Google Messages.", down!!.text)
        assertEquals(HealthBanner.Severity.DOWN, down.severity)
        assertTrue(HealthBanner.opensGoogleReconnect(down))
    }

    @Test
    fun pairingState_startingOrEmoji_countsAsConnecting_otherStatesDoNot() {
        for (state in listOf("starting", "emoji")) {
            val pairing = PairingResponse(google = null, pairing = PairingStateDto(state = state))
            val tracked = HealthBanner.nextClock(null, pairing, nowMs = 0)
            assertEquals(HealthBanner.Unstable.CONNECTING, tracked!!.kind)
            val banner = HealthBanner.banner(null, pairing, nowMs = 30_000, clock, tracked.sinceMs)
            assertEquals("Connecting to Google Messages…", banner!!.text)
            assertEquals(HealthBanner.Severity.WARN, banner.severity)
        }
        val emojiCase = PairingResponse(pairing = PairingStateDto(state = "Emoji"))
        assertEquals(HealthBanner.Unstable.DISCONNECTED, HealthBanner.nextClock(null, emojiCase, 0)!!.kind)
        val idle = PairingResponse(pairing = PairingStateDto(state = "idle"))
        val idleClock = HealthBanner.nextClock(null, idle, 0)
        assertEquals(HealthBanner.Unstable.DISCONNECTED, idleClock!!.kind)
        assertNull(HealthBanner.banner(null, idle, nowMs = 0, clock, unstableSinceMs = null))
        assertEquals(
            "Disconnected from Google Messages. Tap to reconnect Google Messages.",
            HealthBanner.banner(null, idle, nowMs = 45_000, clock, idleClock.sinceMs)!!.text,
        )
    }

    @Test
    fun missingGoogle_withNoPairingState_isDisconnected_after30s() {
        val pairing = PairingResponse()
        val tracked = HealthBanner.nextClock(null, pairing, nowMs = 10)
        assertEquals(HealthBanner.Unstable.DISCONNECTED, tracked!!.kind)
        assertNull(HealthBanner.banner(null, pairing, nowMs = 10, clock, unstableSinceMs = null))
    }

    @Test
    fun failedPairingRefresh_doesNotResetTheClock() {
        val connecting = PairingResponse(google = GoogleConnectionDto(connecting = true))
        val started = HealthBanner.nextClock(null, connecting, nowMs = 5_000)
        val kept = HealthBanner.nextClock(started, pairing = null, nowMs = 20_000)
        assertEquals(started, kept)
        val recovered = HealthBanner.nextClock(started, paired(connected = true, phone = true), nowMs = 20_000)
        assertNull(recovered)
    }

    @Test
    fun reconnectedPairing_clearsStaleGoogleHealthLine_atOnce() {
        val health = HealthResponse(checks = listOf(HealthCheckDto(name = "google", ok = false, detail = "disconnected", downSinceMs = 1_000)))
        assertNull(HealthBanner.banner(health, paired(connected = true, phone = true), nowMs = 90_000, clock, null))
    }

    @Test
    fun emptyHealth_fallsThroughToPhoneOffline() {
        val banner = HealthBanner.banner(
            HealthResponse(checks = emptyList()),
            paired(connected = true, phone = false),
            nowMs = 0,
            clock,
            null,
        )
        assertEquals(HealthBanner.Severity.WARN, banner!!.severity)
        assertTrue(banner.text.contains("isn't responding"))
    }

    @Test
    fun serverUnreachable_hidesTheBanner() {
        val banner = HealthBanner.Banner("VPN is down since 9:00 AM. New messages may be delayed.", HealthBanner.Severity.DOWN)
        assertNull(HealthBanner.forDisplay(banner, serverUnreachable = true))
        assertEquals(banner, HealthBanner.forDisplay(banner, serverUnreachable = false))
        assertNull(HealthBanner.forDisplay(null, serverUnreachable = false))
    }

    @Test
    fun formatLocalClock_isHourMinuteAmPm_andNormalizesNarrowSpaces() {
        val zone = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(zone, Locale.US).apply {
            set(2020, Calendar.JANUARY, 1, 15, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("3:04 PM", HealthBanner.formatLocalClock(cal.timeInMillis, Locale.US, zone))
        assertEquals("3:04 PM", HealthBanner.normalizeClock("3:04\u202FPM"))
        assertEquals("3:04 PM", HealthBanner.normalizeClock("3:04\u00A0PM"))
    }

    @Test
    fun healthAndPairingJson_matchTheServerShape() {
        val json = JsonConfig.json
        val health = json.decodeFromString(
            HealthResponse.serializer(),
            """
            {
              "enabled": true,
              "alert_after_ms": 180000,
              "future": 1,
              "checks": [
                {"name":"google","ok":false,"detail":"disconnected","down_since_ms":10,"alerted":true,"checked_ms":20},
                {"name":"vpn","ok":true,"checked_ms":20}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(health.enabled)
        assertEquals(180_000L, health.alertAfterMs)
        assertEquals("google", health.checks[0].name)
        assertEquals(false, health.checks[0].ok)
        assertEquals("disconnected", health.checks[0].detail)
        assertEquals(10L, health.checks[0].downSinceMs)
        assertTrue(health.checks[0].alerted)
        assertEquals(20L, health.checks[0].checkedMs)
        assertEquals(0L, health.checks[1].downSinceMs)

        val pairing = json.decodeFromString(
            PairingResponse.serializer(),
            """
            {
              "cookies_saved": true,
              "pairing": {"state":"emoji","emoji":"🐱","message":"Tap this on your phone","updated_at":5},
              "google": {
                "connected": false,
                "paired": true,
                "needs_pairing": false,
                "needs_repair": true,
                "connecting": true,
                "phone_responding": false,
                "last_error": "nope"
              }
            }
            """.trimIndent(),
        )
        assertEquals("emoji", pairing.pairing!!.state)
        assertEquals("🐱", pairing.pairing!!.emoji)
        assertEquals("Tap this on your phone", pairing.pairing!!.message)
        assertEquals(false, pairing.google!!.connected)
        assertTrue(pairing.google!!.needsRepair)
        assertTrue(pairing.google!!.connecting)
        assertEquals(false, pairing.google!!.phoneResponding)

        val missingPhone = json.decodeFromString(
            PairingResponse.serializer(),
            """{"google":{"connected":true}}""",
        )
        assertEquals(null, missingPhone.google!!.phoneResponding)
        assertTrue(missingPhone.google!!.connected)
    }

    private fun paired(
        connected: Boolean = false,
        connecting: Boolean = false,
        needsPairing: Boolean = false,
        needsRepair: Boolean = false,
        phone: Boolean? = null,
    ) = PairingResponse(
        google = GoogleConnectionDto(
            connected = connected,
            connecting = connecting,
            needsPairing = needsPairing,
            needsRepair = needsRepair,
            phoneResponding = phone,
        ),
    )

    @Test
    fun emojiConfirm_showsImmediately() {
        val pairing = PairingResponse(
            google = GoogleConnectionDto(connected = false, connecting = true),
            pairing = PairingStateDto(state = "emoji", emoji = "🦊"),
        )
        val banner = HealthBanner.banner(null, pairing, nowMs = 1_000, clock, unstableSinceMs = 0)
        assertEquals("Confirm on your phone: tap 🦊 in Google Messages.", banner!!.text)
        assertEquals(HealthBanner.Severity.WARN, banner.severity)
        assertTrue(HealthBanner.opensGoogleReconnect(banner))
    }

    @Test
    fun signInNeeded_showsReconnectPrompt() {
        val pairing = PairingResponse(
            google = GoogleConnectionDto(connected = false, needsRepair = false),
            signInNeeded = true,
        )
        val banner = HealthBanner.banner(null, pairing, nowMs = 1_000, clock, unstableSinceMs = 0)
        assertEquals(
            "Google sign-in expired. Sign in again to reconnect Google Messages.",
            banner!!.text,
        )
        assertTrue(HealthBanner.opensGoogleReconnect(banner))
    }

}
