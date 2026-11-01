package com.ubermicrostudios.messagesenhanced.data.model

import kotlinx.serialization.json.Json

object JsonConfig {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }
}
