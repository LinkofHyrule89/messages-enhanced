package com.ubermicrostudios.messagesenhanced.util

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Formatters {
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    private val yearFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        return when {
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.getDefault())
        }
    }

    /**
     * Short relative age for the photo viewer, via [DateUtils].
     * Abbreviated, so a recent photo reads like "17 min. ago".
     */
    fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): CharSequence {
        if (timestampMs <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(
            timestampMs,
            nowMs,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
    }

    /** Clock time only, for the timestamp inside a bubble. */
    fun clockTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        return timeFmt.format(Date(timestampMs))
    }

    /** Web `dayLabel`: Today, Yesterday, or weekday + month + day. */
    fun dayLabel(timestampMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (timestampMs <= 0L) return ""
        val zone = Calendar.getInstance().timeZone
        val then = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        if (sameDay(then, now)) return "Today"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        return SimpleDateFormat("EEEE, MMM d", locale).format(Date(timestampMs))
    }

    /** Web tap-detail time: clock today, otherwise `dayLabel, clock`. */
    fun detailTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (timestampMs <= 0L) return ""
        val clock = SimpleDateFormat("h:mm a", locale).format(Date(timestampMs))
        val zone = Calendar.getInstance().timeZone
        val then = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        if (sameDay(then, now)) return clock
        return "${dayLabel(timestampMs, nowMs, locale)}, $clock"
    }

    fun conversationTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return ""
        val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        return when {
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
                timeFmt.format(Date(timestampMs))
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                dateFmt.format(Date(timestampMs))
            else -> yearFmt.format(Date(timestampMs))
        }
    }

    /**
     * List timestamp, matching the web app's `fmtTime`: clock time today,
     * "Yesterday", a short weekday when the message is under six days old,
     * otherwise a short month and day.
     */
    fun listTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (timestampMs <= 0L) return ""
        val zone = Calendar.getInstance().timeZone
        val then = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        if (sameDay(then, now)) return SimpleDateFormat("h:mm a", locale).format(Date(timestampMs))
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        if (nowMs - timestampMs < 6L * 86_400_000L) {
            return SimpleDateFormat("EEE", locale).format(Date(timestampMs))
        }
        return SimpleDateFormat("MMM d", locale).format(Date(timestampMs))
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    fun avatarColor(name: String): Int {
        val palette = intArrayOf(
            0xFF3E8BFF.toInt(),
            0xFF2F6FE4.toInt(),
            0xFF30A46C.toInt(),
            0xFFE5484D.toInt(),
            0xFFF5A524.toInt(),
            0xFF7C5CBF.toInt(),
            0xFF0D9488.toInt(),
            0xFFDB2777.toInt(),
        )
        val idx = (name.hashCode() and 0x7FFFFFFF) % palette.size
        return palette[idx]
    }

    fun formatReactions(raw: String): String = MessageActions.reactionLine(raw)
}
