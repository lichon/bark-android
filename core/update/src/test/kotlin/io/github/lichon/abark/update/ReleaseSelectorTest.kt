package io.github.lichon.abark.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectorTest {

    private fun asset(name: String, size: Long = 8_000_000) =
        GithubAsset(name = name, downloadUrl = "https://example.test/$name", size = size)

    private fun release(
        tag: String,
        body: String? = "notes",
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<GithubAsset> = emptyList(),
    ) = GithubRelease(
        tagName = tag,
        body = body,
        draft = draft,
        prerelease = prerelease,
        htmlUrl = "https://github.com/lichon/bark-android/releases/tag/$tag",
        assets = assets,
    )

    private fun available(decision: UpdateDecision): UpdateDecision.Available {
        assertTrue("expected Available, got $decision", decision is UpdateDecision.Available)
        return decision as UpdateDecision.Available
    }

    @Test
    fun `picks the highest stable release`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.3.0", assets = listOf(asset("abark-1.3.0.apk"))),
                release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk"))),
                release("v1.4.1", assets = listOf(asset("abark-1.4.1.apk"))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10300,
            installedVersion = SemanticVersion.parse("1.3.0"),
        )
        val chosen = available(decision)
        assertEquals("1.4.2", chosen.candidate.version)
        assertEquals(10402, chosen.candidate.versionCode)
        assertEquals("notes", chosen.candidate.notes)
        assertFalse(chosen.mandatory)
    }

    @Test
    fun `ignores drafts and prereleases on the stable channel`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.5.0", draft = true, assets = listOf(asset("abark-1.5.0.apk"))),
                release("v1.6.0", prerelease = true, assets = listOf(asset("abark-1.6.0.apk"))),
                release("v1.4.2-beta.1", assets = listOf(asset("abark-1.4.2-beta.1.apk"))),
                release("v1.4.1", assets = listOf(asset("abark-1.4.1.apk"))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertEquals("1.4.1", available(decision).candidate.version)
    }

    @Test
    fun `beta channel accepts prereleases`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.4.1", assets = listOf(asset("abark-1.4.1.apk"))),
                release("v1.5.0-beta.2", prerelease = true, assets = listOf(asset("abark-1.5.0-beta.2.apk"))),
            ),
            channel = UpdateChannel.BETA,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertEquals("1.5.0-beta.2", available(decision).candidate.version)
    }

    @Test
    fun `stable channel still refuses a prerelease tag that GitHub did not flag`() {
        val decision = ReleaseSelector.select(
            releases = listOf(release("v1.5.0-beta.2", assets = listOf(asset("abark-1.5.0-beta.2.apk")))),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertTrue(decision is UpdateDecision.NothingUsable)
    }

    @Test
    fun `ignores releases without an apk asset`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.5.0", assets = listOf(asset("mapping.txt", size = 1024))),
                release("v1.5.1-bundle", assets = listOf(asset("app-release.aab"))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertTrue(decision is UpdateDecision.NothingUsable)
    }

    @Test
    fun `falls back to the asset name when the tag is not semver`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("nightly-2026-02-01", assets = listOf(asset("abark-1.4.2.apk"))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertEquals("1.4.2", available(decision).candidate.version)
    }

    @Test
    fun `discards assets built for another abi and prefers ours over universal`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release(
                    "v1.4.2",
                    assets = listOf(
                        asset("abark-1.4.2-x86_64.apk"),
                        asset("abark-1.4.2-universal.apk", size = 30_000_000),
                        asset("abark-1.4.2-arm64-v8a.apk"),
                    ),
                ),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
            abi = "arm64-v8a",
        )
        assertEquals("abark-1.4.2-arm64-v8a.apk", available(decision).candidate.assetName)
    }

    @Test
    fun `never offers a downgrade or the installed version`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk"))),
                release("v1.4.3", assets = listOf(asset("abark-1.4.3.apk"))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10403,
            installedVersion = SemanticVersion.parse("1.4.3"),
        )
        assertTrue(decision is UpdateDecision.NothingUsable)
    }

    @Test
    fun `refuses an apk whose versionCode does not advance`() {
        // Same patch number, but the installed build already reports a higher versionCode.
        val decision = ReleaseSelector.select(
            releases = listOf(release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk")))),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 11402,
            installedVersion = SemanticVersion.parse("1.14.2"),
        )
        assertTrue(decision is UpdateDecision.NothingUsable)
    }

    @Test
    fun `marks the update mandatory below the minimum supported version`() {
        val decision = ReleaseSelector.select(
            releases = listOf(release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk")))),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10100,
            installedVersion = SemanticVersion.parse("1.1.0"),
            minSupportedVersionCode = 10200,
        )
        assertTrue(available(decision).mandatory)
    }

    @Test
    fun `ignores absurdly large assets`() {
        val decision = ReleaseSelector.select(
            releases = listOf(
                release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk", size = 500_000_000))),
            ),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = SemanticVersion.parse("1.4.0"),
        )
        assertTrue(decision is UpdateDecision.NothingUsable)
    }

    @Test
    fun `unknown installed version still updates by versionCode`() {
        val decision = ReleaseSelector.select(
            releases = listOf(release("v1.4.2", assets = listOf(asset("abark-1.4.2.apk")))),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 10400,
            installedVersion = null,
            minSupportedVersionCode = 10402,
        )
        assertTrue(available(decision).mandatory)
    }
}
