package io.github.lichon.abark.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The subset of the GitHub Releases API we actually use. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** A release that is actually installable on this device. Serialized into the update cache. */
@Serializable
data class UpdateCandidate(
    val version: String,
    val versionCode: Int,
    val tagName: String,
    val notes: String? = null,
    val releaseUrl: String = "",
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long = 0,
) {
    val parsedVersion: SemanticVersion? get() = SemanticVersion.parse(version)
}

enum class UpdateChannel { STABLE, BETA }

sealed interface UpdateDecision {
    /** Installed version is at least the newest one available in the selected channel. */
    data class UpToDate(val checkedAtMillis: Long) : UpdateDecision

    data class Available(
        val candidate: UpdateCandidate,
        /** Installed versionCode is below the release's minimum supported version: APNs transport is gated. */
        val mandatory: Boolean,
    ) : UpdateDecision

    /** No usable release (no APK asset, unparsable tags, only drafts...). Not an error worth alarming the user. */
    data class NothingUsable(val reason: String) : UpdateDecision

    /** Network/rate-limit/parse failure. Never blocks startup; retried on the next scheduled check. */
    data class Failed(val reason: String) : UpdateDecision
}
