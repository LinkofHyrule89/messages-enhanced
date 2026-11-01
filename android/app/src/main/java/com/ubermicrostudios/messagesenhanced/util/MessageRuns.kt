package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import java.util.Calendar

/**
 * Consecutive-message grouping from the web app's `sameRun`: same sender,
 * under 15 minutes apart, and the same calendar day.
 *
 * Group sender headers (name + avatar above the bubble) show at the start of
 * each sender run only. Reply status ([MessageDto.replyToId]) must not affect
 * [Row.showSender] — a reply that starts a run still shows the reply author's
 * name, matching Google Messages.
 */
object MessageRuns {
    const val GAP_MS = 15 * 60 * 1000L

    enum class Pos { SOLO, FIRST, MID, LAST }

    data class Row(
        val message: MessageDto,
        val pos: Pos,
        val showReceipt: Boolean,
        val showSender: Boolean,
    )

    fun rows(messages: List<MessageDto>, isGroup: Boolean): List<Row> {
        if (messages.isEmpty()) return emptyList()
        var lastMine = -1
        messages.forEachIndexed { index, message ->
            if (message.isFromMe && !message.status.startsWith("TOMBSTONE", ignoreCase = true)) {
                lastMine = index
            }
        }
        return messages.mapIndexed { index, message ->
            val prev = index > 0 && sameRun(messages[index - 1], message)
            val next = index < messages.lastIndex && sameRun(message, messages[index + 1])
            val pos = when {
                prev && next -> Pos.MID
                prev -> Pos.LAST
                next -> Pos.FIRST
                else -> Pos.SOLO
            }
            Row(
                message = message,
                pos = pos,
                showReceipt = index == lastMine,
                // ReplyToID is intentionally ignored: replies use the same run rules.
                showSender = isGroup && !message.isFromMe && !prev &&
                    (message.senderName.isNotBlank() || message.senderNumber.isNotBlank()),
            )
        }
    }

    fun sameRun(a: MessageDto?, b: MessageDto?): Boolean {
        if (a == null || b == null) return false
        if (runKey(a) != runKey(b)) return false
        if (kotlin.math.abs(b.timestampMs - a.timestampMs) >= GAP_MS) return false
        return sameDay(a.timestampMs, b.timestampMs)
    }

    fun runKey(message: MessageDto): String =
        if (message.isFromMe) "me" else message.senderNumber.ifBlank { message.senderName }.ifBlank { "them" }

    private fun sameDay(a: Long, b: Long): Boolean {
        if (a <= 0L || b <= 0L) return false
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
