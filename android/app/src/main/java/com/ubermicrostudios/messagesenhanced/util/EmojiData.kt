package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parses `emoji-data.js` (`window.TM_EMOJI = [{id, label, e}]`, records split by 0x1e / 0x1f). */
object EmojiData {
    fun parse(raw: String): List<EmojiCatalog.Category> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JsonConfig.json.parseToJsonElement(raw.substring(start, end + 1)).jsonArray
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
            val packed = obj["e"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val items = packed.split('\u001e').mapNotNull { piece ->
                if (piece.isEmpty()) return@mapNotNull null
                val parts = piece.split('\u001f', limit = 2)
                val emoji = parts[0]
                if (emoji.isEmpty()) return@mapNotNull null
                EmojiCatalog.Named(emoji, parts.getOrElse(1) { "" })
            }
            EmojiCatalog.Category(id, label, items)
        }
    }
}
