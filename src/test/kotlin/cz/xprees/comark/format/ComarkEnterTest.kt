package cz.xprees.comark.format

import cz.xprees.comark.lang.ComarkLanguage
import com.intellij.application.options.CodeStyle
import com.intellij.injected.editor.EditorWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownFileType

/** Enter in Comark files: [ComarkLineIndentProvider] and [ComarkEnterHandlerDelegate]. */
class ComarkEnterTest : BasePlatformTestCase() {
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

    private fun withIndentSize(size: Int, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.INDENT_SIZE = size
            action()
        }
    }

    /** Types Enter at `<caret>` in [before] (a `.mdc` file unless [name] says otherwise) and checks [after]. */
    private fun enter(before: String, after: String, name: String = "test.mdc", indentSize: Int = 2) = withIndentSize(indentSize) {
        myFixture.configureByText(name, before)
        myFixture.type("\n")
        assertEquals(after, textWithCaret())
    }

    /** The whole document with the caret marked (the fixture's editor is an injected one when the caret is in front matter). */
    private fun textWithCaret(): String {
        val editor = (myFixture.editor as? EditorWindow)?.delegate ?: myFixture.editor
        return StringBuilder(editor.document.text).insert(editor.caretModel.offset, "<caret>").toString()
    }

    // ---- component bodies ----------------------------------------------------------------------------------------

    fun testEnterInNestedComponentBody() =
        enter("::outer\n:::inner\n  text<caret>\n  :::\n::\n", "::outer\n:::inner\n  text\n  <caret>\n  :::\n::\n")

    fun testEnterAfterNestedOpener() =
        enter("::outer\n  :::inner<caret>\n  :::\n::\n", "::outer\n  :::inner\n  <caret>\n  :::\n::\n")

    fun testEnterInTopLevelComponentBodyHasNoIndentation() =
        enter("::alert\ntext<caret>\n::\n", "::alert\ntext\n<caret>\n::\n")

    fun testIndentationFollowsTheFormatterNotTheCurrentText() =
        // the formatter puts the inner content at two spaces, whatever it has now
        enter("::outer\n:::inner\ntext<caret>\n:::\n::\n", "::outer\n:::inner\ntext\n  <caret>\n:::\n::\n")

    fun testEnterUsesTheCodeStyleIndentSize() =
        enter("::a\n:::b\n::::c\ntext<caret>\n::::\n:::\n::\n", "::a\n:::b\n::::c\ntext\n        <caret>\n::::\n:::\n::\n", indentSize = 4)

    fun testEnterOnBlankLineInComponentBody() =
        enter("::outer\n  :::inner\n  text\n<caret>\n  :::\n::\n", "::outer\n  :::inner\n  text\n\n  <caret>\n  :::\n::\n")

    fun testEnterOnIndentedBlankLineInComponentBody() =
        enter("::outer\n  :::inner\n  text\n  <caret>  \n  :::\n::\n", "::outer\n  :::inner\n  text\n  \n  <caret>\n  :::\n::\n")

    fun testSplittingALineIndentsTheRest() =
        enter("::outer\n  :::inner\n  one <caret>two\n  :::\n::\n", "::outer\n  :::inner\n  one \n  <caret>two\n  :::\n::\n")

    // ---- props ---------------------------------------------------------------------------------------------------

    fun testEnterAfterPropertyKeepsItsIndentation() =
        enter("::outer\n  :::inner\n  ---\n  title: x<caret>\n  ---\n  :::\n::\n", "::outer\n  :::inner\n  ---\n  title: x\n  <caret>\n  ---\n  :::\n::\n")

    fun testEnterAfterParentPropertyIndents() =
        enter("::card\n---\nui:<caret>\n---\n::\n", "::card\n---\nui:\n  <caret>\n---\n::\n")

    fun testEnterAfterMultilineStringStartIndents() =
        enter("::card\n---\ntext: |<caret>\n---\n::\n", "::card\n---\ntext: |\n  <caret>\n---\n::\n")

    fun testEnterInMultilineStringKeepsItsIndentation() =
        enter("::card\n---\ntext: |\n    first<caret>\n---\n::\n", "::card\n---\ntext: |\n    first\n    <caret>\n---\n::\n")

    fun testEnterAfterArrayItemPropertyAlignsWithIt() =
        enter("::card\n---\nlinks:\n  - label: a<caret>\n---\n::\n", "::card\n---\nlinks:\n  - label: a\n    <caret>\n---\n::\n")

    fun testEnterAfterScalarArrayItemKeepsItsIndentation() =
        enter("::card\n---\nitems:\n  - a<caret>\n---\n::\n", "::card\n---\nitems:\n  - a\n  <caret>\n---\n::\n")

    fun testEnterAfterPropsOpener() =
        enter("::a\n  :::card\n  ---<caret>\n  ---\n  :::\n::\n", "::a\n  :::card\n  ---\n  <caret>\n  ---\n  :::\n::\n")

    fun testEnterOnBlankLineInProps() =
        enter("::card\n---\nui:\n  a: 1\n<caret>\n---\n::\n", "::card\n---\nui:\n  a: 1\n\n  <caret>\n---\n::\n")

    fun testEnterOnBlankLineInNestedInjectedProps() =
        // the props are injected YAML here (the YAML plugin is present in tests): the nested component's indentation must survive
        enter("::outer\n  :::inner\n  ---\n  ui:\n    a: 1\n<caret>\n  ---\n  :::\n::\n", "::outer\n  :::inner\n  ---\n  ui:\n    a: 1\n\n    <caret>\n  ---\n  :::\n::\n")

    fun testThematicBreakIsNotYaml() =
        // the formatter takes `---` for a YAML block, but only props and front matter get YAML indentation on Enter
        enter("::outer\n:::inner\nIntro\n\n---\n\nNote:<caret>\n:::\n::\n", "::outer\n:::inner\nIntro\n\n---\n\nNote:\n  <caret>\n:::\n::\n")

    fun testDashesRightAfterTheOpenerAreProps() =
        enter("::outer\n:::inner\n---\nui:<caret>\n", "::outer\n:::inner\n---\nui:\n  <caret>\n")

    fun testEnterInFrontMatter() =
        enter("---\nnavigation:<caret>\n---\n\n# Title\n", "---\nnavigation:\n  <caret>\n---\n\n# Title\n")

    // ---- lists keep the Markdown behaviour -----------------------------------------------------------------------

    fun testListContinuationOutsideComponents() =
        enter("- one<caret>\n", "- one\n- <caret>\n")

    fun testListContinuationInsideComponent() =
        enter("::outer\n  :::inner\n  - one<caret>\n  :::\n::\n", "::outer\n  :::inner\n  - one\n  - <caret>\n  :::\n::\n")

    fun testNestedListContinuationInsideComponent() =
        enter("::outer\n  :::inner\n  - one\n    - two<caret>\n  :::\n::\n", "::outer\n  :::inner\n  - one\n    - two\n    - <caret>\n  :::\n::\n")

    fun testOrderedListContinuationInsideComponent() =
        enter("::card\n1. one<caret>\n::\n", "::card\n1. one\n2. <caret>\n::\n")

    fun testListsBehaveAsInMarkdownFiles() {
        for (before in listOf("- one\n  - two<caret>\n", "1. one<caret>\n\nText\n", "- [ ] task<caret>\n", "- one\n\n  more<caret>\n")) {
            withIndentSize(2) {
                myFixture.configureByText("plain.md", before)
                myFixture.type("\n")
            }
            enter(before, textWithCaret())
        }
    }

    // ---- components inside list items are component content, not list content -----------------------------------

    fun testEnterInListItemComponentBodyDoesNotInsertABullet() =
        enter("- item\n  ::block\n  text<caret>\n  ::\n", "- item\n  ::block\n  text\n  <caret>\n  ::\n")

    fun testEnterAfterListItemComponentOpenerDoesNotInsertABullet() =
        enter("- item\n  ::block<caret>\n  ::\n", "- item\n  ::block\n  <caret>\n  ::\n")

    fun testEnterInComponentNestedInAListItemComponent() =
        enter("- item\n  ::block\n    :::inner\n    text<caret>\n    :::\n  ::\n", "- item\n  ::block\n    :::inner\n    text\n    <caret>\n    :::\n  ::\n")

    fun testEnterInListItemComponentInsideAComponent() =
        enter("::outer\n- item\n  :::block\n  text<caret>\n  :::\n::\n", "::outer\n- item\n  :::block\n  text\n  <caret>\n  :::\n::\n")

    fun testEnterOnBlankLineInListItemComponent() =
        enter("- item\n  ::block\n  text\n<caret>\n  ::\n", "- item\n  ::block\n  text\n\n  <caret>\n  ::\n")

    fun testListInsideAListItemComponentContinues() =
        enter("- item\n  ::block\n  - one<caret>\n  ::\n", "- item\n  ::block\n  - one\n  - <caret>\n  ::\n")

    // ---- block quotes keep the Markdown behaviour ----------------------------------------------------------------

    fun testBlockQuoteContinuesInsideAComponent() =
        enter("::alert\n> quote<caret>\n::\n", "::alert\n> quote\n> <caret>\n::\n")

    fun testBlockQuoteContinuesInsideANestedComponent() =
        enter("::outer\n  :::alert\n  > quote<caret>\n  :::\n::\n", "::outer\n  :::alert\n  > quote\n  > <caret>\n  :::\n::\n")

    // ---- elsewhere Markdown decides ------------------------------------------------------------------------------

    fun testTopLevelParagraph() = enter("Some text<caret>\n", "Some text\n<caret>\n")

    fun testCodeFenceInComponentKeepsTheFenceIndentation() =
        enter("::outer\n  :::inner\n  ```\n  const a = 1<caret>\n  ```\n  :::\n::\n", "::outer\n  :::inner\n  ```\n  const a = 1\n  <caret>\n  ```\n  :::\n::\n")

    fun testDisabledFormatterLeavesEnterToMarkdown() {
        ComarkSettings.getInstance().formatterEnabled = false
        enter("::outer\n:::inner\ntext<caret>\n:::\n::\n", "::outer\n:::inner\ntext\n<caret>\n:::\n::\n")
        enter("::outer\n  :::inner\n  text\n<caret>\n  :::\n::\n", "::outer\n  :::inner\n  text\n\n<caret>\n  :::\n::\n")
    }
}
