package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioOracleCases.Outcome
import cz.xprees.comark.format.studio.StudioOracleCases.assertTally
import cz.xprees.comark.format.studio.StudioOracleCases.cases
import cz.xprees.comark.format.studio.StudioOracleCases.compareText
import cz.xprees.comark.format.studio.StudioOracleCases.textVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StudioFormatter.format], the text path T(x), against what Nuxt Studio 1.8.0 wrote after a code-editor save: the
 * oracle's `text` for every corpus case and `text` of every random document in `text-vectors.json`. Where Studio threw,
 * `format` must return `null`. The counts per [StudioOracleCases.Outcome] are exact and may only improve.
 */
class StudioFormatterDifferentialTest {
    @Test
    fun formatsTheOracleCorpusLikeStudio() {
        val verdicts = cases.map { it["name"].asString to compareText(it["input"].asString, it["text"]) }
        assertTally("Studio text path T(x), oracle corpus", verdicts, mapOf(Outcome.MATCH to 446, Outcome.BOTH_FAIL to 34))
    }

    @Test
    fun formatsTheRandomDocumentsLikeStudio() {
        val verdicts = textVectors.mapIndexed { i, v -> "vector #$i" to compareText(v["input"].asString, v["text"]) }
        assertTally("Studio text path T(x), random documents", verdicts, mapOf(Outcome.MATCH to 4400, Outcome.BOTH_FAIL to 621))
    }

    @Test
    fun returnsNullWhereStudioThrows() {
        assertNull(StudioFormatter.format("---\ntitle: a\ntitle: b\n---\n\ntext\n"))
        assertNull(StudioFormatter.format("::card\n---\nkey: [\n---\n::\n"))
        assertEquals("---\ntitle: a\n---\n\ntext\n", StudioFormatter.format("---\ntitle: a\n---\ntext"))
    }

    /** A `body` front matter key replaces the parsed body in Studio's document; the port declines rather than guess. */
    @Test
    fun declinesFrontMatterThatReplacesTheBody() {
        assertNull(StudioFormatter.format("---\nbody: x\n---\n\ntext\n"))
    }
}
