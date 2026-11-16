package io.github.lichon.abark.update

/**
 * Pure selection logic: which release, if any, should this device install?
 *
 * Rules (see docs/DISTRIBUTION.md §4/§5):
 *  - drafts are ignored, pre-releases only in the BETA channel;
 *  - the version comes from the tag; if the tag is not semver we fall back to the asset name;
 *  - an APK asset is required, and assets built for a *different* ABI are discarded rather than offered;
 *  - an asset built for our ABI wins over a universal one;
 *  - only versions with a strictly greater versionCode *and* greater semver are considered (never downgrade).
 */
object ReleaseSelector {

    const val MAX_APK_BYTES: Long = 100L * 1024 * 1024

    private const val ASSET_PREFIX = "abark-"
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private data class Scored(val candidate: UpdateCandidate, val abiRank: Int)

    fun select(
        releases: List<GithubRelease>,
        channel: UpdateChannel,
        installedVersionCode: Int,
        installedVersion: SemanticVersion?,
        abi: String? = null,
        minSupportedVersionCode: Int = 0,
    ): UpdateDecision {
        val scored = releases
            .asSequence()
            .filter { !it.draft }
            .filter { channel == UpdateChannel.BETA || !it.prerelease }
            .flatMap { release -> candidatesIn(release, channel, abi).asSequence() }
            .filter { it.candidate.versionCode > installedVersionCode }
            .filter { scored ->
                val version = scored.candidate.parsedVersion ?: return@filter false
                installedVersion == null || version > installedVersion
            }
            .toList()

        if (scored.isEmpty()) return UpdateDecision.NothingUsable("no-newer-version")

        val best = scored.maxWithOrNull(
            compareBy<Scored> { it.candidate.parsedVersion ?: SemanticVersion(0, 0, 0) }
                .thenByDescending { it.abiRank } // rank 0 (exact ABI) beats rank 1 (universal)
                .thenBy { it.candidate.assetSize },
        ) ?: return UpdateDecision.NothingUsable("no-newer-version")

        return UpdateDecision.Available(
            candidate = best.candidate,
            mandatory = installedVersionCode < minSupportedVersionCode,
        )
    }

    private fun candidatesIn(
        release: GithubRelease,
        channel: UpdateChannel,
        abi: String?,
    ): List<Scored> {
        val tagVersion = SemanticVersion.parse(release.tagName)
        // A pre-release tag can never become a stable release, even if GitHub's flag is missing.
        if (channel == UpdateChannel.STABLE && tagVersion?.isPreRelease == true) return emptyList()

        return release.assets.mapNotNull { asset ->
            val name = asset.name
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            if (asset.size <= 0 || asset.size > MAX_APK_BYTES) return@mapNotNull null
            val rank = abiRank(name, abi) ?: return@mapNotNull null

            val version = tagVersion ?: versionFromAssetName(name) ?: return@mapNotNull null
            if (channel == UpdateChannel.STABLE && version.isPreRelease) return@mapNotNull null

            Scored(
                candidate = UpdateCandidate(
                    version = version.toString(),
                    versionCode = version.versionCode,
                    tagName = release.tagName,
                    notes = release.body,
                    releaseUrl = release.htmlUrl,
                    assetName = name,
                    assetUrl = asset.downloadUrl,
                    assetSize = asset.size,
                ),
                abiRank = rank,
            )
        }
    }

    /** `abark-1.4.2-arm64-v8a.apk` -> `1.4.2`; the tag is authoritative, this is only a fallback. */
    private fun versionFromAssetName(name: String): SemanticVersion? {
        if (!name.startsWith(ASSET_PREFIX, ignoreCase = true)) return null
        var core = name.removeSuffix(".apk").removeSuffix(".APK").substring(ASSET_PREFIX.length)
        KNOWN_ABIS.firstOrNull { core.endsWith("-$it", ignoreCase = true) }?.let { abi ->
            core = core.dropLast(abi.length + 1)
        }
        return SemanticVersion.parse(core)
    }

    /** 0 = built for our ABI, 1 = universal, null = built for a different ABI (discard). */
    private fun abiRank(assetName: String, abi: String?): Int? {
        val lowered = assetName.lowercase()
        val declared = KNOWN_ABIS.filter { lowered.contains(it) }
        if (declared.isEmpty()) return 1
        return if (abi != null && declared.any { it == abi.lowercase() }) 0 else null
    }
}
