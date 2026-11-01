package com.ubermicrostudios.messagesenhanced.update

/**
 * Three-part version numbers from this app (`0.8.1`) and GitHub tags.
 * Android releases in the monorepo use `android-v0.8.1`. Web tags stay `v0.7.22`
 * and are not Android releases.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(?:android-)?v?(\d+)\.(\d+)\.(\d+)$""", RegexOption.IGNORE_CASE)
        private val ANDROID_TAG = Regex("""^android-v(\d+)\.(\d+)\.(\d+)$""", RegexOption.IGNORE_CASE)

        /** True only for monorepo Android release tags (`android-vX.Y.Z`). Web `vX.Y.Z` tags are not. */
        fun isAndroidReleaseTag(raw: String): Boolean = ANDROID_TAG.matches(raw.trim())

        fun parse(raw: String): SemVer? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch)
        }

        /** True when [candidate] (tag or version) is strictly newer than [current]. */
        fun isNewer(candidate: String, current: String): Boolean {
            val next = parse(candidate) ?: return false
            val now = parse(current) ?: return false
            return next > now
        }
    }
}
