package cz.xprees.comark.components

import cz.xprees.comark.meta.ComarkUnknownComponentInspection

class UnknownComponentInspectionTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(ComarkUnknownComponentInspection())
    }

    fun testUnknownBlockAndInlineComponents() {
        addStandardComponents()
        configure(
            """
            ::<weak_warning descr="Unknown component 'alret'">alret</weak_warning>{type="info"}
            Use :badge and :<weak_warning descr="Unknown component 'bdage'">bdage</weak_warning>[x] here.
            ::

            ::alert
              :::card
              Nested :<weak_warning descr="Unknown component 'prose-card'">prose-card</weak_warning> is not registered by Nuxt Content.
              :::
            ::

            # Heading with :<weak_warning descr="Unknown component 'nope'">nope</weak_warning>

            | Column :<weak_warning descr="Unknown component 'missing'">missing</weak_warning> |
            |---|
            | cell |

            Not components: `:inline-code`, https://example.com, 10:30, Note: text, :smile: emoji.

            ```
            ::fenced
            ::
            ```

            ```md
            ::example
            ::
            ```
            """.trimIndent(),
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testSilentWithoutDiscoveredComponents() {
        configure("::whatever\n::\n\nAnd :inline too.")
        myFixture.checkHighlighting(false, false, true)
    }

    fun testSpellingVariantsAreKnown() {
        addStandardComponents()
        configure("::Card\n::\n\n::card\n::\n\nText :Badge.")
        myFixture.checkHighlighting(false, false, true)
    }
}
