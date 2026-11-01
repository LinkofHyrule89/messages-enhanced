package com.ubermicrostudios.messagesenhanced.util

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Display-only phone formatting. Use the result for text the user reads.
 * Do not store it, send it, compare it, or match search against it.
 *
 * [PhoneNumberUtils.formatNumber] prints a US number as `(801) 555-1234` and
 * other regions in international form. When it returns null, the original text stays.
 */
private const val MIN_PHONE_DIGITS = 7

private val PHONE_SHAPE = Regex("""^\+?[0-9\s().\-]+$""")

@Volatile
private var appContext: Context? = null

/** Called from [com.ubermicrostudios.messagesenhanced.MessagesEnhancedApp]. */
fun bindPhoneDisplay(context: Context) {
    appContext = context.applicationContext
}

/**
 * True when [raw] is only an optional leading `+`, digits, spaces, dashes,
 * parentheses, and dots, and it contains at least [MIN_PHONE_DIGITS] digits.
 * Emails, names, and short codes are false.
 */
fun looksLikePhone(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val trimmed = raw.trim()
    if (!PHONE_SHAPE.matches(trimmed)) return false
    var digits = 0
    for (ch in trimmed) {
        if (ch in '0'..'9') digits++
    }
    return digits >= MIN_PHONE_DIGITS
}

/**
 * Network country, then SIM country, then the locale country, then `US`.
 * Values that are not two letters are skipped.
 */
fun displayCountryIso(networkIso: String?, simIso: String?, localeCountry: String?): String {
    fun valid(value: String?): String? {
        val cleaned = value?.trim()?.uppercase(Locale.US) ?: return null
        if (cleaned.length != 2 || cleaned.any { it !in 'A'..'Z' }) return null
        return cleaned
    }
    return valid(networkIso) ?: valid(simIso) ?: valid(localeCountry) ?: "US"
}

fun formatPhoneForDisplay(raw: String?, country: String = deviceCountry()): String {
    if (raw == null) return ""
    if (!looksLikePhone(raw)) return raw
    val region = country.trim().uppercase(Locale.US).ifBlank { "US" }
    val formatted = runCatching { PhoneNumberUtils.formatNumber(raw.trim(), region) }.getOrNull()
    if (formatted.isNullOrBlank()) return raw
    return formatted
}

/** Formats phone-shaped [names] inside [text]. Other characters stay put. Longer names are replaced first. */
fun formatPhonesInText(text: String, names: List<String>, country: String = deviceCountry()): String {
    if (text.isEmpty() || names.isEmpty()) return text
    var shown = text
    for (name in names.distinct().sortedByDescending { it.length }) {
        if (name.isEmpty()) continue
        val formatted = formatPhoneForDisplay(name, country)
        if (formatted != name) shown = shown.replace(name, formatted)
    }
    return shown
}

private fun deviceCountry(): String {
    val tm = runCatching { telephonyManager() }.getOrNull()
    return displayCountryIso(
        tm?.networkCountryIso,
        tm?.simCountryIso,
        Locale.getDefault().country,
    )
}

private fun telephonyManager(): TelephonyManager? {
    val context = appContext ?: return null
    return context.getSystemService(TelephonyManager::class.java)
}
