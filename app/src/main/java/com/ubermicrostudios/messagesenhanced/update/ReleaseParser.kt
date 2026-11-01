package com.ubermicrostudios.messagesenhanced.update

import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ParsedApk(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
    val digestInvalid: Boolean,
)

data class ParsedRelease(
    val version: SemVer,
    val tag: String,
    val notes: String,
    val apk: ParsedApk?,
)

data class AppRelease(
    val version: SemVer,
    val tag: String,
    val notes: String,
    val apkName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

@Serializable
internal data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
internal data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

object ReleaseParser {
    private val SHA256 = Regex("""^[0-9a-fA-F]{64}$""")

    fun parse(jsonText: String): ParsedRelease? {
        val raw = runCatching {
            JsonConfig.json.decodeFromString(GithubReleaseDto.serializer(), jsonText)
        }.getOrNull() ?: return null
        val version = SemVer.parse(raw.tagName) ?: return null
        val asset = raw.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return ParsedRelease(
            version = version,
            tag = raw.tagName.trim(),
            notes = raw.body.trim(),
            apk = asset?.let(::apkOf),
        )
    }

    private fun apkOf(asset: GithubAssetDto): ParsedApk {
        val digest = asset.digest?.trim().orEmpty()
        val sha = if (digest.isEmpty()) null else sha256Hex(digest)
        return ParsedApk(
            name = asset.name,
            downloadUrl = asset.browserDownloadUrl.trim(),
            size = asset.size,
            sha256 = sha,
            digestInvalid = digest.isNotEmpty() && sha == null,
        )
    }

    /** Hex from `sha256:<64 hex chars>`, or null when the field is not that form. */
    fun sha256Hex(digest: String): String? {
        val trimmed = digest.trim()
        if (!trimmed.startsWith("sha256:", ignoreCase = true)) return null
        val hex = trimmed.substring("sha256:".length).trim()
        if (!SHA256.matches(hex)) return null
        return hex.lowercase()
    }
}
