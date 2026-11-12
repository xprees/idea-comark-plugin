package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.settings.ComarkSettings
import java.util.BitSet

/** Reformat Code in the Nuxt Studio and custom styles ([ComarkStudioFormatting]). */
class ComarkStudioFormattingServiceTest : BasePlatformTestCase() {
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

    private fun withStyle(preset: ComarkStylePreset, change: (CodeStyleSettings) -> Unit = {}, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = preset
            change(settings)
            action()
        }
    }

    private fun reformat() {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
    }

    private fun reformatRange(start: Int, end: Int) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformatText(myFixture.file, start, end) }
    }

    /**
     * Opens [text] as `content/[name]` of a data collection (a `content.config.ts` next to `content/`), where Studio adds no
     * front matter, so the examples show the body alone.
     */
    private fun configureInDataCollection(name: String, text: String) {
        myFixture.addFileToProject("content.config.ts", DATA_CONFIG)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("content/$name", text).virtualFile)
    }

    private val messy = "# Title\nSome *text*\n* a\n* b\n\n\n::card{a=\"1\" b=\"2\" c=\"3\" d=\"4\"}\nbody\n::\n"

    fun testWholeFileInTheNuxtStudioStyle() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        myFixture.configureByText("page.mdc", messy)
        reformat()
        // no content.config.ts: a page collection with Studio's defaults, so the title and description are added
        val expected = "---\ntitle: Title\ndescription: Some text\n---\n\n" +
            "# Title\n\nSome *text*\n\n- a\n- b\n\n::card\n---\na: '1'\nb: '2'\nc: '3'\nd: '4'\n---\nbody\n::\n"
        myFixture.checkResult(expected)
        // a fixed point
        reformat()
        myFixture.checkResult(expected)
    }

    fun testVsCodeStyleIsUnchanged() = withStyle(ComarkStylePreset.VSCODE) {
        myFixture.configureByText("page.mdc", "::outer\n   text  \n:::inner\ninner\n:::\n::")
        reformat()
        myFixture.checkResult("::outer\ntext\n  :::inner\n  inner\n  :::\n::\n")
    }

    fun testCustomStyle() = withStyle(ComarkStylePreset.CUSTOM, {
        val custom = ComarkCodeStyleSettings.getInstance(it)
        custom.BULLET_MARKER = BulletMarker.ASTERISK
        custom.EMPHASIS_MARKER = EmphasisMarker.UNDERSCORE
    }) {
        configureInDataCollection("page.mdc", "Some *text*\n- a\n- b\n")
        reformat()
        myFixture.checkResult("Some _text_\n\n* a\n* b\n")
    }

    fun testKeptBlocksStayAsWritten() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        configureInDataCollection("page.mdc", "* a\n\n<div>\n*raw*  html\n</div>\n")
        reformat()
        myFixture.checkResult("- a\n\n<div>\n*raw*  html\n</div>\n")
    }

    fun testSelectionReformatsOnlyTheBlocksItTouches() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val text = "* a\n* b\n\n\n\nfirst\nparagraph\n\n* c\n* d\n"
        myFixture.configureByText("page.mdc", text)
        val start = text.indexOf("* d")
        reformatRange(start, start + 1)
        // the whole list `* c * d` is rewritten, the first list and the blank lines before the paragraph are not
        myFixture.checkResult("* a\n* b\n\n\n\nfirst\nparagraph\n\n- c\n- d\n")
    }

    fun testFormatterOffRegionsAreKept() = withStyle(ComarkStylePreset.NUXT_STUDIO, {
        it.FORMATTER_TAGS_ENABLED = true
    }) {
        configureInDataCollection(
            "page.mdc",
            "* a\n\n<!-- @formatter:off -->\n\n* keep   *this*\n* as is\n\n<!-- @formatter:on -->\n\n* change\n",
        )
        reformat()
        myFixture.checkResult("- a\n\n<!-- @formatter:off -->\n\n* keep   *this*\n* as is\n\n<!-- @formatter:on -->\n\n- change\n")
    }

    fun testEditsAreMinimal() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val text = "* first item with words\n* second\n"
        configureInDataCollection("page.mdc", text)
        val document = myFixture.editor.document
        val start = text.indexOf("words")
        val marker = document.createRangeMarker(start, start + "words".length)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("second"))
        reformat()
        myFixture.checkResult("- first item with words\n- second\n")
        assertTrue(marker.isValid)
        assertEquals("words", document.getText(marker.textRange))
        assertEquals(document.text.indexOf("second"), myFixture.editor.caretModel.offset)
        marker.dispose()
    }

    fun testWhitespaceOnlyReformatAppliesOnlyWhitespaceEdits() {
        val text = "Some text   \n\n* a\n"
        val result = StudioStyleAdapter.format(text, StudioStyleContext("page.md"))!!
        val edits = ComarkStudioFormatting.computeEdits(text, result, listOf(TextRange(0, text.length)), true, null, whitespaceOnly = true)
        val applied = StringBuilder(text).apply { for (e in edits.asReversed()) replace(e.start, e.end, e.text) }.toString()
        // trailing whitespace goes, the list marker and the front matter Studio adds (title, description) do not
        assertEquals("Some text\n\n* a\n", applied)
    }

    fun testEditsAtTheEndOfTheText() {
        val data = StudioCollection("data", StudioCollection.Type.DATA)
        for ((text, expected) in listOf("# A\ntext" to "# A\n\ntext\n", "text\n\n\n" to "text\n", "a\nb" to "a\nb\n")) {
            val result = StudioStyleAdapter.format(text, StudioStyleContext("page.md", data))!!
            assertEquals(expected, result.text)
            val edits = ComarkStudioFormatting.computeEdits(text, result, listOf(TextRange(0, text.length)), true, BitSet())
            val applied = StringBuilder(text).apply { for (e in edits.asReversed()) replace(e.start, e.end, e.text) }.toString()
            assertEquals(expected, applied)
        }
    }

    fun testUnreadableDocumentIsLeftAlone() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        // a component with props YAML that does not load is kept; everything else is still formatted
        myFixture.configureByText("page.mdc", "* a\n\n::c\n---\na: [\n---\nx\n::\n")
        reformat()
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("- a\n"))
    }

    fun testLineSeparatorsAndBomAfterWholeFileReformat() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val file = myFixture.tempDirFixture.createFile("crlf.mdc")
        WriteAction.runAndWait<Throwable> {
            file.setBinaryContent(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "* a\r\n* b\r\n".toByteArray())
        }
        myFixture.configureFromExistingVirtualFile(file)
        // as loading the file from disk detects them (the light test file system does not)
        file.detectedLineSeparator = "\r\n"
        file.bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(ComarkStudioFormatting.needsNormalization(file))
        reformat()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("---\ntitle: Crlf\n---\n\n- a\n- b\n", String(file.contentsToByteArray()))
        assertEquals("\n", file.detectedLineSeparator)
        assertNull(file.bom)
    }

    fun testSelectionKeepsLineSeparators() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val file = myFixture.tempDirFixture.createFile("crlf2.mdc")
        WriteAction.runAndWait<Throwable> { file.setBinaryContent("* a\r\n\r\n* b\r\n".toByteArray()) }
        myFixture.configureFromExistingVirtualFile(file)
        file.detectedLineSeparator = "\r\n"
        reformatRange(0, 1)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("\r\n", file.detectedLineSeparator)
    }

    private companion object {
        const val DATA_CONFIG = "export default defineContentConfig({ collections: { data: defineCollection({ type: 'data', source: '**' }) } })\n"
    }
}
