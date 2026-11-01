package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.GoogleConnectionDto
import com.ubermicrostudios.messagesenhanced.data.model.HealthCheckDto
import com.ubermicrostudios.messagesenhanced.data.model.HealthResponse
import com.ubermicrostudios.messagesenhanced.data.model.PairingResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Connection banner copied from the web app.
 *
 * Health lines come from GET /api/app/health once a check has been down for
 * [PERSIST_MS]. If none qualify, the banner falls back to GET /api/app/pairing:
 * not paired and phone-offline show immediately; connecting and disconnected
 * wait until they have persisted for [PERSIST_MS] so a brief blip does not flash.
 * A healthy connection produces no banner.
 */
object HealthBanner {
    const val PERSIST_MS = 30_000L
    /** Google "disconnected" must last this long continuously (server restarts / VPN blips reconnect sooner). */
    const val DISCONNECTED_PERSIST_MS = 45_000L

    enum class Severity { DOWN, WARN }

    data class Banner(val text: String, val severity: Severity)

    /** Pairing states that must stick around before the banner appears. */
    enum class Unstable { CONNECTING, DISCONNECTED }

    data class UnstableClock(val kind: Unstable, val sinceMs: Long)

    /**
     * Advance the flicker clock. A null [pairing] means "no new sample"
     * (the last clock stands). A stable sample (paired, connected, phone offline)
     * clears it. Switching between connecting and disconnected starts a new window.
     */
    fun nextClock(previous: UnstableClock?, pairing: PairingResponse?, nowMs: Long): UnstableClock? {
        if (pairing == null) return previous
        val kind = unstableKind(pairing) ?: return null
        if (previous != null && previous.kind == kind) return previous
        return UnstableClock(kind, nowMs)
    }

    /**
     * Banner to show, or null when the connection looks fine or the unstable
     * pairing state has not lasted [PERSIST_MS] yet.
     * [unstableSinceMs] is the start of the current connecting/disconnected streak.
     */
    fun banner(
        health: HealthResponse?,
        pairing: PairingResponse?,
        nowMs: Long,
        formatClock: (Long) -> String,
        unstableSinceMs: Long?,
    ): Banner? {
        // A fresh "connected" pairing sample clears a stale Google-down health line at once.
        val reconnected = pairing?.google?.connected == true
        val checks = health?.checks.orEmpty().filter { !(reconnected && it.name != "vpn") }
        val lines = healthLines(checks, nowMs, formatClock)
        if (lines.isNotEmpty()) return Banner(lines.joinToString(" "), Severity.DOWN)
        if (pairing == null) return null
        return pairingBanner(pairing, nowMs, unstableSinceMs)
    }

    /** Hide the health banner while the app's own "can't reach the server" error is up. */
    fun forDisplay(banner: Banner?, serverUnreachable: Boolean): Banner? =
        if (serverUnreachable) null else banner

    /** `h:mm a` in [zone], with the narrow spaces some locales put before AM/PM turned into regular spaces. */
    fun formatLocalClock(
        epochMs: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val format = SimpleDateFormat("h:mm a", locale)
        format.timeZone = zone
        return normalizeClock(format.format(Date(epochMs)))
    }

    internal fun normalizeClock(raw: String): String =
        raw.replace(Regex("[\\u2000-\\u200A\\u202F\\u205F\\u00A0\\u3000]"), " ")

    private fun healthLines(
        checks: List<HealthCheckDto>,
        nowMs: Long,
        formatClock: (Long) -> String,
    ): List<String> {
        val lines = ArrayList<String>(checks.size)
        for (check in checks) {
            val vpn = check.name == "vpn"
            val wait = if (vpn) PERSIST_MS else DISCONNECTED_PERSIST_MS
            if (check.ok || check.downSinceMs <= 0L || nowMs - check.downSinceMs < wait) continue
            val what = if (vpn) "VPN is down" else "Google Messages is disconnected"
            val detail = if (check.detail.isNotEmpty()) " (${check.detail})" else ""
            val extra = if (vpn) "" else " Tap to reconnect Google Messages."
            lines.add("$what$detail since ${formatClock(check.downSinceMs)}. New messages may be delayed.$extra")
        }
        return lines
    }

    /**
     * Open [com.ubermicrostudios.messagesenhanced.ui.settings.GoogleReconnectActivity]
     * when the banner is about Google / pairing, not a VPN-only outage.
     */
    fun opensGoogleReconnect(banner: Banner): Boolean {
        if (banner.severity != Severity.DOWN && banner.severity != Severity.WARN) return false
        val text = banner.text
        return text.contains("Google") || text.contains("paired") || text.contains("Disconnected")
    }

    private fun pairingBanner(pairing: PairingResponse, nowMs: Long, unstableSinceMs: Long?): Banner? {
        val st = pairing.pairing
        if (st?.state == "emoji" && st.emoji.isNotBlank()) {
            return Banner(
                "Confirm on your phone: tap ${st.emoji} in Google Messages.",
                Severity.WARN,
            )
        }
        if (pairing.signInNeeded) {
            return Banner(
                "Google sign-in expired. Sign in again to reconnect Google Messages.",
                Severity.DOWN,
            )
        }
        val google = pairing.google
        if (google?.needsPairing == true || google?.needsRepair == true) {
            return Banner("Not paired with your phone yet. Tap to reconnect.", Severity.WARN)
        }
        if (google?.connected == true) {
            return if (google.phoneResponding == false) {
                Banner("Connected, but your phone isn't responding (check it's online)", Severity.WARN)
            } else {
                null
            }
        }
        val since = unstableSinceMs ?: return null
        if (nowMs - since < PERSIST_MS) return null
        return if (isConnecting(google, pairing.pairing?.state)) {
            Banner("Connecting to Google Messages…", Severity.WARN)
        } else if (nowMs - since < DISCONNECTED_PERSIST_MS) {
            null
        } else {
            Banner("Disconnected from Google Messages. Tap to reconnect Google Messages.", Severity.DOWN)
        }
    }

    private fun unstableKind(pairing: PairingResponse): Unstable? {
        val google = pairing.google
        if (google?.needsPairing == true || google?.needsRepair == true) return null
        if (google?.connected == true) return null
        return if (isConnecting(google, pairing.pairing?.state)) Unstable.CONNECTING else Unstable.DISCONNECTED
    }

    private fun isConnecting(google: GoogleConnectionDto?, pairingState: String?): Boolean =
        google?.connecting == true || pairingState == "starting" || pairingState == "emoji"
}
