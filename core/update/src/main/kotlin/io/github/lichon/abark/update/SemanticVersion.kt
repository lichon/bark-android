package io.github.lichon.abark.update

/**
 * Minimal SemVer 2.0 implementation, sufficient for release tags like `v1.4.2` / `v1.4.2-beta.1`.
 *
 * Deliberately strict: it accepts `MAJOR.MINOR.PATCH` with `MINOR`/`PATCH` < 100 and `MAJOR` < 210000,
 * because [versionCode] must stay inside Android's `versionCode` range and increase monotonically.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    /** `major * 10000 + minor * 100 + patch`, e.g. 1.4.2 -> 10402. Mirrors the Gradle/CI scheme. */
    val versionCode: Int
        get() {
            require(minor in 0..99 && patch in 0..99) { "minor/patch must be < 100: $this" }
            require(major in 0..210_000) { "major out of range: $this" }
            return major * 10_000 + minor * 100 + patch
        }

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A release has higher precedence than any pre-release of the same version.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until maxOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease.getOrNull(i) ?: return -1 // shorter pre-release is lower
            val b = other.preRelease.getOrNull(i) ?: return 1
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val cmp = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers rank below alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(\d{1,6})\.(\d{1,2})\.(\d{1,2})(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?$"""
        )

        /** Returns null for anything that is not a clean semver (so callers can ignore odd tags). */
        fun parse(raw: String?): SemanticVersion? {
            val value = raw?.trim() ?: return null
            val match = PATTERN.matchEntire(value) ?: return null
            val (major, minor, patch) = match.destructured
            return SemanticVersion(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.toInt(),
                preRelease = match.groupValues[4].split('.').filter { it.isNotEmpty() },
            )
        }
    }
}
