package cz.xprees.comark.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.editor.yaml.ComarkPropsYamlInjector
import cz.xprees.comark.psi.ComarkPropsContent
import org.jetbrains.yaml.YAMLHighlighter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue

class ComarkYamlInjectionTest : BasePlatformTestCase() {
    private val injectedLanguageManager get() = InjectedLanguageManager.getInstance(project)

    private fun propsContent(): ComarkPropsContent = PsiTreeUtil.findChildrenOfType(myFixture.file, ComarkPropsContent::class.java).single()

    private fun injectedFiles(host: ComarkPropsContent): List<PsiFile> =
        injectedLanguageManager.getInjectedPsiFiles(host).orEmpty().map { it.first.containingFile }.distinct()

    fun testPropsAreYaml() {
        myFixture.configureByText("test.mdc", "::card\n---\ntitle: Hello\nicon: x\n---\nBody\n::\n")
        val injected = injectedFiles(propsContent()).single()
        assertEquals(YAMLLanguage.INSTANCE, injected.language)
        assertEquals("title: Hello\nicon: x\n", injected.text)
        val keys = PsiTreeUtil.findChildrenOfType(injected, YAMLKeyValue::class.java).map { it.keyText }
        assertEquals(listOf("title", "icon"), keys)
    }

    fun testNestedPropsLoseTheComponentIndentation() {
        val text = "::card\n  :::inner\n  ---\n  b: 2\n  c:\n    - x\n\n  ---\n  body\n  :::\n::"
        myFixture.configureByText("test.mdc", text)
        val injected = injectedFiles(propsContent()).single()
        assertEquals("b: 2\nc:\n  - x\n\n", injected.text)
        val element = injectedLanguageManager.findInjectedElementAt(myFixture.file, text.indexOf("- x") + 2)
        assertEquals("x", element?.text)
        assertEquals(YAMLLanguage.INSTANCE, element?.language)
    }

    private fun yamlErrors(text: String): List<String> {
        myFixture.configureByText("test.mdc", text)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).map { text.substring(it.startOffset, it.endOffset) }
    }

    fun testYamlErrorsAreReportedInProps() {
        assertEquals(listOf("b: c"), yamlErrors("::card\n---\ntitle: x\na: b: c\n---\n::\n"))
        assertEquals(listOf("value\n- item"), yamlErrors("::card\n---\nkey: value\n- item\n---\n::\n"))
    }

    fun testYamlErrorsInNestedPropsPointAtTheHostText() {
        assertEquals(listOf("b: c"), yamlErrors("::card\n  :::inner\n  ---\n  title: x\n  a: b: c\n  ---\n  :::\n::\n"))
    }

    fun testValidYamlHasNoErrors() {
        myFixture.configureByText("test.mdc", "::card\n---\ntitle: Hello\nlist:\n  - a\n---\n::\n")
        assertEquals(emptyList<Any>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    fun testInjectedYamlIsHighlighted() {
        val text = "::card\n---\ntitle: Hello\n---\n::\n"
        myFixture.configureByText("test.mdc", text)
        val key = TextRange.from(text.indexOf("title"), "title".length)
        val keyColor = EditorColorsManager.getInstance().globalScheme.getAttributes(YAMLHighlighter.SCALAR_KEY).foregroundColor
        assertNotNull(keyColor)
        val keyInfos = myFixture.doHighlighting().filter { it.severity == HighlightInfoType.INJECTED_FRAGMENT_SYNTAX_SEVERITY && TextRange(it.startOffset, it.endOffset) == key }
        assertTrue("expected the YAML key colour on 'title': $keyInfos", keyInfos.any { it.forcedTextAttributes?.foregroundColor == keyColor })
    }

    fun testFrontMatterIsStillYaml() {
        val text = "---\ntitle: Page\n---\n\n::card\n---\nicon: x\n---\n::\n"
        myFixture.configureByText("test.mdc", text)
        val frontMatter = injectedLanguageManager.findInjectedElementAt(myFixture.file, text.indexOf("title"))
        assertEquals(YAMLLanguage.INSTANCE, frontMatter?.language)
        val props = injectedLanguageManager.findInjectedElementAt(myFixture.file, text.indexOf("icon"))
        assertEquals(YAMLLanguage.INSTANCE, props?.language)
    }

    fun testYamlPropsFenceIsStillYaml() {
        val text = "::card\n```yaml [props]\ntitle: Hello\n```\nBody\n::\n"
        myFixture.configureByText("test.mdc", text)
        val element = injectedLanguageManager.findInjectedElementAt(myFixture.file, text.indexOf("title"))
        assertEquals(YAMLLanguage.INSTANCE, element?.language)
    }

    fun testLineRanges() {
        assertEquals(listOf(TextRange(0, 9)), ComarkPropsYamlInjector.lineRanges("a: 1\nb: 2", 0))
        assertEquals(
            listOf(TextRange(2, 7), TextRange(9, 12), TextRange(14, 20), TextRange(20, 21)),
            ComarkPropsYamlInjector.lineRanges("  a: 1\n  b:\n    - x\n\n", 2),
        )
        // a line indented less than the fence keeps its text
        assertEquals(listOf(TextRange(1, 6)), ComarkPropsYamlInjector.lineRanges(" a: 1\n", 4))
        assertEquals(emptyList<TextRange>(), ComarkPropsYamlInjector.lineRanges("", 2))
    }
}
