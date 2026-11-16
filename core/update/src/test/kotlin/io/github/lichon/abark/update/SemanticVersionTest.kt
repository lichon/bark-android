package io.github.lichon.abark.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `parses plain and v-prefixed tags`() {
        assertEquals(SemanticVersion(1, 4, 2), SemanticVersion.parse("1.4.2"))
        assertEquals(SemanticVersion(1, 4, 2), SemanticVersion.parse("v1.4.2"))
        assertEquals(SemanticVersion(1, 4, 2), SemanticVersion.parse(" V1.4.2 "))
    }

    @Test
    fun `parses pre-release and ignores build metadata`() {
        val beta = SemanticVersion.parse("v1.4.2-beta.1")
        assertEquals(listOf("beta", "1"), beta?.preRelease)
        assertTrue(beta!!.isPreRelease)
        assertEquals(SemanticVersion(1, 4, 2), SemanticVersion.parse("1.4.2+sha.abc123"))
    }

    @Test
    fun `rejects non-semver tags`() {
        listOf("1.4", "1.4.2.3", "1.300.0", "nightly", "v1.4.2.1", "", null).forEach {
            assertNull("should reject '$it'", SemanticVersion.parse(it))
        }
    }

    @Test
    fun `semver precedence`() {
        assertTrue(SemanticVersion.parse("1.4.2")!! > SemanticVersion.parse("1.4.1")!!)
        assertTrue(SemanticVersion.parse("1.10.0")!! > SemanticVersion.parse("1.9.9")!!)
        assertTrue(SemanticVersion.parse("1.0.0-alpha")!! < SemanticVersion.parse("1.0.0-alpha.1")!!)
        assertTrue(SemanticVersion.parse("1.0.0-alpha.1")!! < SemanticVersion.parse("1.0.0-beta")!!)
        assertTrue(SemanticVersion.parse("1.0.0-beta.11")!! > SemanticVersion.parse("1.0.0-beta.2")!!)
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("1.0.0-rc.9")!!)
    }

    @Test
    fun `versionCode matches the documented scheme and is monotonic`() {
        assertEquals(10402, SemanticVersion.parse("1.4.2")!!.versionCode)
        assertEquals(10_000, SemanticVersion.parse("1.0.0")!!.versionCode)
        assertEquals(99_999, SemanticVersion.parse("9.99.99")!!.versionCode)

        val ascending = listOf("1.0.0", "1.0.1", "1.0.99", "1.1.0", "1.99.0", "2.0.0")
            .map { SemanticVersion.parse(it)!!.versionCode }
        assertEquals(ascending.sorted(), ascending)
    }

    @Test
    fun `pre-release shares the versionCode of its release`() {
        assertEquals(
            SemanticVersion.parse("1.4.2")!!.versionCode,
            SemanticVersion.parse("1.4.2-beta.3")!!.versionCode,
        )
    }

    @Test
    fun `versionCode refuses values that would break the scheme`() {
        val invalid = SemanticVersion(1, 100, 0)
        assertFalse(runCatching { invalid.versionCode }.isSuccess)
    }
}
