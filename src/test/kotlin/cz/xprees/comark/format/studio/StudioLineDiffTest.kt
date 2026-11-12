package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioLineDiff.Hunk
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioLineDiffTest {
    private fun hunks(a: String, b: String) = StudioLineDiff.hunks(a.split("\n"), b.split("\n"))

    @Test
    fun equalTextsHaveNoHunks() = assertEquals(emptyList<Hunk>(), hunks("a\nb", "a\nb"))

    @Test
    fun replacementsInsertionsAndDeletions() {
        assertEquals(listOf(Hunk(1, 1, 1)), hunks("a\nb\nc", "a\nx\nc"))
        assertEquals(listOf(Hunk(1, 0, 2)), hunks("a\nc", "a\nx\ny\nc"))
        assertEquals(listOf(Hunk(0, 1, 0), Hunk(3, 1, 0)), hunks("x\na\nb\ny\nc", "a\nb\nc"))
        assertEquals(listOf(Hunk(2, 0, 1)), hunks("a\nb", "a\nb\nc"))
    }

    @Test
    fun separateChangesStaySeparate() =
        assertEquals(listOf(Hunk(1, 1, 1), Hunk(4, 1, 1)), hunks("a\nb\nc\nd\ne\nf", "a\nB\nc\nd\nE\nf"))
}
