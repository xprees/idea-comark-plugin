package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.format.studio.StudioOracleCases
import cz.xprees.comark.format.studio.StudioOracleCases.text
import cz.xprees.comark.settings.ComarkSettings

/**
 * Reformat Code in the Nuxt Studio style from end to end: real files, `content.config.ts`, the code style setting, the
 * formatting service and the engine.
 *
 * The Studio-written fixtures are recorded outputs of the real Nuxt Studio 1.8.0 (`oracle.json`, see
 * `src/test/node/studio-oracle`): what its database path wrote (D(x)) where its code editor leaves that text unchanged too
 * (T(D(x)) == D(x)) and saving it again changes nothing (D(D(x)) == D(x)), i.e. files Studio itself keeps as they are.
 */
class ComarkStudioEndToEndTest : BasePlatformTestCase() {
    private var formatterWasEnabled = true

    override fun setUp() {
        super.setUp()
        formatterWasEnabled = ComarkSettings.getInstance().formatterEnabled
        ComarkSettings.getInstance().formatterEnabled = true
    }

    override fun tearDown() {
        try {
            ComarkSettings.getInstance().formatterEnabled = formatterWasEnabled
        } finally {
            super.tearDown()
        }
    }

    private fun withStudioStyle(action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = ComarkStylePreset.NUXT_STUDIO
            action()
        }
    }

    private fun reformat() {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
    }

    private fun text() = myFixture.editor.document.text

    /** The Studio outputs every recorded save path leaves unchanged. */
    private val studioWritten: List<Pair<String, String>> by lazy {
        StudioOracleCases.cases.mapNotNull { case ->
            val db = case.text("db") ?: return@mapNotNull null
            val dualFixedPoint = case["collectionType"]?.asString == "page" && case["dbStable"]?.asBoolean == true && case.text("textOfDb") == db
            if (dualFixedPoint) case["name"].asString to db else null
        }
    }

    fun testStudioWrittenFilesAreUnchangedByReformat() = withStudioStyle {
        val names = listOf(
            "handwritten/h18-many-attrs.md",
            "handwritten/h19-props-block.md",
            "handwritten/h21-slots-advanced.md",
            "handwritten/h40-mixed-document.md",
            "handwritten/h63-props-sort.md",
            "handwritten/probe-p26-component-yaml-complex.md",
        )
        val byName = studioWritten.toMap()
        for (name in names) {
            val written = byName[name] ?: error("$name is not a dual fixed point in oracle.json")
            // `.mdc`: always a Comark file (a `.md` file is one only in a detected Nuxt Content project)
            myFixture.configureByText(name.substringAfterLast('/').replace(".md", ".mdc"), written)
            reformat()
            assertEquals(name, written, text())
        }
    }

    /** The same over every Studio-written file of the corpus, through the adapter the formatting service calls. */
    fun testEveryStudioWrittenFileIsAFixedPoint() {
        assertTrue(studioWritten.size > 300)
        val changed = studioWritten.filter { (name, written) ->
            StudioStyleAdapter.format(written, StudioStyleContext(name.substringAfterLast('/')))?.text != written
        }
        assertEquals(changed.take(3).joinToString("\n=====\n") { it.first + "\n" + it.second }, 0, changed.size)
    }

    fun testReformattingTwiceIsStable() = withStudioStyle {
        val messy = """
            ---
            tags: [a,   b]
            navigation: true
            ---
            # Getting started
            Install the module, then:
            * add it to `nuxt.config.ts`
            * restart the dev server

            ::card{to="/docs" title="Comark" icon="i-lucide-box"}
            Default slot content
            :::badge{color="primary"}
            Nested
            :::
            ::
        """.trimIndent() + "\n"
        myFixture.configureByText("getting-started.mdc", messy)
        reformat()
        val once = text()
        assertEquals(
            """
            ---
            title: Getting started
            description: 'Install the module, then:'
            tags:
              - a
              - b
            ---

            # Getting started

            Install the module, then:

            - add it to `nuxt.config.ts`
            - restart the dev server

            ::card{icon="i-lucide-box" title="Comark" to="/docs"}
            Default slot content

              :::badge{color="primary"}
              Nested
              :::
            ::
            """.trimIndent() + "\n",
            once,
        )
        reformat()
        assertEquals(once, text())
    }

    fun testCustomSchemaKeysInSchemaOrder() = withStudioStyle {
        myFixture.addFileToProject(
            "content.config.ts",
            """
            import { defineCollection, defineContentConfig, z } from '@nuxt/content'

            export default defineContentConfig({
              collections: {
                blog: defineCollection({
                  type: 'page',
                  source: 'blog/**',
                  schema: z.object({
                    tags: z.array(z.string()),
                    date: z.date(),
                    author: z.string(),
                    draft: z.boolean().default(false),
                  }),
                }),
              },
            })
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject(
            "content/blog/hello.mdc",
            "---\nextra: kept last\ntags: [news]\nauthor: Jane\ndate: 2026-01-02\n---\n# Hello\n\nFirst post.\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        reformat()
        // id and title first, then the schema's columns sorted by name (Nuxt Content's column order, with the draft default),
        // then keys outside the schema in their source order
        assertEquals(
            "---\ntitle: Hello\nauthor: Jane\ndate: '2026-01-02'\ndescription: First post.\ndraft: false\ntags:\n  - news\n" +
                "extra: kept last\n---\n\n# Hello\n\nFirst post.\n",
            text(),
        )
        val once = text()
        reformat()
        assertEquals(once, text())
    }

    fun testDataCollectionGetsNoTitle() = withStudioStyle {
        myFixture.addFileToProject(
            "content.config.ts",
            """
            export default defineContentConfig({
              collections: {
                authors: defineCollection({ type: 'data', source: 'authors/**' }),
                content: defineCollection({ type: 'page', source: '**' }),
              },
            })
            """.trimIndent(),
        )
        val author = myFixture.addFileToProject("content/authors/jane.mdc", "---\nname: Jane\n---\n# Jane\nWrites things.\n")
        myFixture.configureFromExistingVirtualFile(author.virtualFile)
        reformat()
        assertEquals("---\nname: Jane\n---\n\n# Jane\n\nWrites things.\n", text())
        // the same text in the page collection gets a title and a description
        val page = myFixture.addFileToProject("content/about.mdc", "---\nname: Jane\n---\n# Jane\nWrites things.\n")
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        reformat()
        assertEquals("---\ntitle: Jane\ndescription: Writes things.\nname: Jane\n---\n\n# Jane\n\nWrites things.\n", text())
    }

    fun testVsCodePresetNeverCallsTheEngine() {
        // the VS Code style leaves Markdown alone: no front matter, markers or blank lines change
        val text = "# Title\n* a\n* b\n"
        myFixture.configureByText("page.mdc", text)
        val calls = StudioStyleAdapter.formatCalls.get()
        reformat()
        assertEquals(text, text())
        assertEquals(calls, StudioStyleAdapter.formatCalls.get())
        assertFalse(ComarkStyle.of(myFixture.file).usesStudio)
        assertNull(ComarkStudioAnalysis.styleFor(myFixture.file))
    }
}
