package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioOracleCases.Outcome
import cz.xprees.comark.format.studio.StudioOracleCases.assertTally
import cz.xprees.comark.format.studio.StudioOracleCases.cases
import cz.xprees.comark.format.studio.StudioOracleCases.compareTree
import cz.xprees.comark.format.studio.StudioOracleCases.textVectors
import org.junit.Test

/**
 * [StudioTreeBuilder] against the trees Nuxt Studio 1.8.0's text path really hands to `renderMarkdown`: the oracle's
 * `comarkTree` + `frontmatter` for every corpus case, and the same for the seeded random documents of `text-vectors.json`.
 * A case where Studio throws must make the builder throw [StudioTextPathException]. The counts per
 * [StudioOracleCases.Outcome] are exact; they may only move towards [Outcome.MATCH] and [Outcome.BOTH_FAIL].
 */
class StudioTreeBuilderDifferentialTest {
    @Test
    fun buildsTheOracleTrees() {
        val verdicts = cases.map { it["name"].asString to compareTree(it["input"].asString, it["comarkTree"], it["frontmatter"]) }
        assertTally("Studio text-path trees, oracle corpus", verdicts, mapOf(Outcome.MATCH to 438, Outcome.BOTH_FAIL to 34))
    }

    @Test
    fun buildsTheRandomDocumentTrees() {
        val verdicts = textVectors.mapIndexed { i, v -> "vector #$i" to compareTree(v["input"].asString, v["tree"], v["frontmatter"]) }
        assertTally("Studio text-path trees, random documents", verdicts, mapOf(Outcome.MATCH to 4400, Outcome.BOTH_FAIL to 621))
    }
}
