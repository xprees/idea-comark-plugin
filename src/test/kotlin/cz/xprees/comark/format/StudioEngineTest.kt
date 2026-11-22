package cz.xprees.comark.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StudioEngine.resolve]: the *Nuxt Studio version* setting, and the automatic choice from the project's dependencies. */
class StudioEngineTest {
    private val both = listOf(StudioEngine.V1_8_0, StudioEngine.V1_8_2)

    private fun lock(vararg versions: String) = StudioDependencies("^1.8.0", versions.toList(), null, emptyList(), "pnpm-lock.yaml")

    private fun range(range: String) = StudioDependencies(range, emptyList(), null, emptyList(), null)

    @Test
    fun withoutStudioTheNewestIsUsed() {
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, null, both))
        assertEquals(StudioEngine.newest, StudioEngine.resolve(StudioEngine.AUTO, null))
    }

    @Test
    fun lockfileKeepsTheReleaseTheProjectUses() {
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve(StudioEngine.AUTO, lock("1.8.0"), both))
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve(StudioEngine.AUTO, lock("1.8.1"), both))
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, lock("1.8.2"), both))
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, lock("1.9.0"), both))
        // older than every supported release: the oldest one
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve(StudioEngine.AUTO, lock("1.7.3"), both))
        // several copies: the newest decides
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, lock("1.8.0", "1.8.4"), both))
    }

    @Test
    fun rangeWithoutLockfile() {
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, range("^1.8.0"), both))
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, range("~1.8.0"), both))
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve(StudioEngine.AUTO, range("1.8.0"), both))
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve(StudioEngine.AUTO, range("1.8.1"), both))
        // nothing satisfies it: closest to the base version
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, range("^2.1.0"), both))
        // unparsable ranges fall back to the newest
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve(StudioEngine.AUTO, range("latest"), both))
    }

    @Test
    fun explicitSettingWins() {
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve("1.8.0", lock("1.8.2"), both))
        assertEquals(StudioEngine.V1_8_2, StudioEngine.resolve("1.8.2", null, both))
        // a release that's no longer supported falls back to the automatic choice
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve("1.7.0", lock("1.8.1"), both))
        assertEquals(StudioEngine.V1_8_0, StudioEngine.resolve("1.8.2", null, listOf(StudioEngine.V1_8_0)))
    }

    @Test
    fun onlyAvailableReleasesAreOffered() {
        assertTrue(StudioEngine.supported.all { it.available })
        assertTrue(StudioEngine.V1_8_0 in StudioEngine.supported)
    }

    @Test
    fun rangeChecks() {
        assertTrue(StudioVersionParser.satisfies("^1.8.0", "1.8.2"))
        assertTrue(!StudioVersionParser.satisfies("^1.8.2", "1.8.0"))
        assertTrue(StudioVersionParser.satisfies(">=1.8.0", "1.8.2"))
        assertTrue(!StudioVersionParser.satisfies("~1.7.0", "1.8.0"))
        assertEquals("1.8.1", StudioVersionParser.baseVersion("^1.8.1"))
    }
}
