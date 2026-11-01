package com.ubermicrostudios.messagesenhanced.update

/**
 * Three-part version numbers from this app (`0.6.0`) and GitHub tags (`v0.6.0`).
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
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""", RegexOption.IGNORE_CASE)

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
