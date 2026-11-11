package cz.xprees.comark.compat

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.preview.ComarkPreviewPanelProvider
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import org.intellij.plugins.markdown.ui.preview.jcef.JCEFHtmlPanelProvider
import org.intellij.plugins.markdown.ui.preview.jcef.HtmlSourceTextPreprocessor

/** The HTML the Markdown preview shows for Comark files, produced through the same API the preview calls. */
class PreviewRenderingTest : BasePlatformTestCase() {
    private fun page(file: PsiFile): String = ReadAction.computeBlocking<String, RuntimeException> {
        val document = myFixture.getDocument(file)
        val provider = MarkdownHtmlPanelProvider.createFromInfo(JCEFHtmlPanelProvider().providerInfo)
        provider.sourceTextPreprocessor.preprocessText(project, document, file.virtualFile)
    }

    /** The page content after the inlined stylesheet. */
    private fun preview(text: String, name: String = "doc.mdc"): String =
        page(myFixture.configureByText(name, text)).substringAfter("</style>")

    /** The `md-src-pos` range of the first element opened by [tagStart], as document text. */
    private fun sourceOf(html: String, text: String, tagStart: String): String {
        val tag = html.indexOf(tagStart)
        assertTrue("no $tagStart in $html", tag >= 0)
        val range = Regex("""md-src-pos=["'](\d+)\.\.(\d+)["']""").find(html, tag)!!
        return text.substring(range.groupValues[1].toInt(), range.groupValues[2].toInt())
    }

    fun testPreviewUsesComarkProvider() {
        val chosen = MarkdownHtmlPanelProvider.createFromInfo(JCEFHtmlPanelProvider().providerInfo)
        assertInstanceOf(chosen, ComarkPreviewPanelProvider::class.java)
        assertEquals(JCEFHtmlPanelProvider().providerInfo, chosen.providerInfo)
    }

    fun testPlainMarkdownIsUnchanged() {
        val file = myFixture.configureByText("plain.md", "# Title\n\n::alert\nText\n::\n")
        val expected = ReadAction.computeBlocking<String, RuntimeException> {
            HtmlSourceTextPreprocessor().preprocessText(project, myFixture.getDocument(file), file.virtualFile)
        }
        assertEquals(expected, page(file))
    }

    fun testBlockComponent() {
        val text = "# Title\n\n::alert{type=\"info\" .wide}\nThis is **important**!\n::\n\nAfter\n"
        val page = page(myFixture.configureByText("doc.mdc", text))
        assertTrue(page, page.startsWith("<html><head></head><body md-src-pos=\"0..${text.length}\"><style>"))
        val html = page.substringAfter("</style>")
        assertTrue(html, html.contains("""class="comark-component" data-component="alert""""))
        assertTrue(html, html.contains("""<span class="comark-component-name">alert</span>"""))
        assertTrue(html, html.contains("""<span class="comark-attr">type=&quot;info&quot;</span>"""))
        assertTrue(html, html.contains("""<span class="comark-attr comark-attr-class">.wide</span>"""))
        assertFalse("markers must not be rendered as text: $html", html.contains("::"))
        assertTrue(html, html.indexOf("<strong") in html.indexOf("comark-component-body")..html.indexOf("After"))
        assertEquals("**important**", sourceOf(html, text, "<strong"))
        assertTrue(html, html.contains("<h1"))
    }

    fun testSourcePositionsInsideIndentedComponents() {
        val text = "::card\n  :::badge\n    Nested **content**\n  :::\n::\n"
        val html = preview(text)
        assertFalse("indented content is a paragraph, not code: $html", html.contains("<pre"))
        assertEquals("**content**", sourceOf(html, text, "<strong"))
        assertEquals("Nested **content**", sourceOf(html, text, "<p").trim())
        assertTrue(html, html.contains("""data-component="badge""""))
    }

    fun testPropsAndSlots() {
        val text = "::card\n---\ntitle: Hello\n---\nDefault slot\n\n#footer\nFooter *text*\n::\n"
        val html = preview(text)
        assertTrue(html, html.contains("""<pre class="comark-component-props""""))
        assertTrue(html, html.contains("title: Hello"))
        assertTrue(html, html.contains("""<div class="comark-slot" data-slot="footer""""))
        assertTrue(html, html.contains("<em"))
        assertTrue(html, html.indexOf("Default slot") < html.indexOf("comark-slot"))
    }

    fun testShorthandContent() {
        val html = preview("::alert[Hello **there**]\n::\n")
        val content = html.substringAfter("""<div class="comark-component-content">""").substringBefore("</div>")
        assertTrue(html, content.contains("Hello") && content.contains("<strong"))
    }

    fun testInlineComponentsAndSpans() {
        val text = "Click :button[Submit]{type=\"primary\"} or :icon-star, a [span]{.red} and **bold**{.x} `:code` :smile:\n"
        val html = preview(text)
        val button = """<span class="comark-inline-component" data-component="button" title=":button{type=&quot;primary&quot;}">"""
        assertTrue(html, html.contains(button))
        assertEquals("Submit", sourceOf(html, text, button + "<span"))
        assertTrue(html, html.contains("""data-component="icon-star""""))
        assertTrue(html, html.contains("""<span class="comark-span" title="{.red}">"""))
        assertFalse(html, html.contains("{.x}"))
        assertTrue("code spans are untouched: $html", html.contains(">:code</code>"))
        assertFalse("emoji short codes are not components: $html", html.contains("""data-component="smile""""))
        assertEquals("**bold**", sourceOf(html, text, "<strong"))
    }

    fun testCodeFenceInsideComponentIsCode() {
        val html = preview("::card\n```md\n::fake\n:inline\n```\n::\n")
        assertTrue(html, html.contains("::fake"))
        assertFalse(html, html.contains("""data-component="fake""""))
        assertFalse(html, html.contains("""data-component="inline""""))
    }

    fun testReferenceLinksAcrossComponents() {
        val html = preview("::card\nSee [docs][ref]\n::\n\n[ref]: https://example.com\n")
        assertTrue(html, html.contains("href=\"https://example.com\""))
    }

    fun testComponentInListItemIsRenderedAsComponent() {
        val text = "::steps\n- **Node.js** - `22.x` or newer\n- **Terminal** - run Nuxt\n  :::callout\n  Use an even version.\n  :::\n- third\n::\n"
        val html = preview(text)
        assertTrue(html, html.contains("""data-component="callout""""))
        assertFalse("component markers rendered as text: $html", html.contains(":::"))
        val item = html.indexOf("Terminal")
        val callout = html.indexOf("""data-component="callout"""")
        assertTrue("callout inside the second list item: $html", callout in item..html.indexOf("third"))
        assertTrue(html, html.indexOf("</li>", item) > callout)
        assertEquals(":::callout\n  Use an even version.\n  :::", sourceOf(html, text, """<div class="comark-component" data-component="callout""""))
        assertEquals("Use an even version.", sourceOf(html, text, "<p").trim())
    }

    fun testTopLevelListItemComponent() {
        val text = "1. Install\n   ::code-group\n   ```bash\n   npm i\n   ```\n   ::\n2. Run\n"
        val html = preview(text)
        assertTrue(html, html.contains("""data-component="code-group""""))
        assertFalse("component markers rendered as text: $html", html.contains("::code-group"))
        assertFalse("closer rendered as text: $html", html.contains(">::</p>"))
        val component = html.indexOf("""data-component="code-group"""")
        assertTrue("fence inside the component: $html", html.indexOf("<pre", component) in component..html.indexOf("Run"))
        assertTrue(html, html.indexOf("</li>") > component)
        assertEquals("::code-group\n   ```bash\n   npm i\n   ```\n   ::", sourceOf(html, text, """<div class="comark-component""""))
        assertEquals(text.substring(0, text.indexOf("2.")).trimEnd(), sourceOf(html, text, "<li").trimEnd())
    }

    fun testDuplicateHeadingsGetUniqueIds() {
        val text = "## Usage\n\nFirst\n\n::note\nBetween\n::\n\n## Usage\n\nSecond\n\n## Usage\n"
        fun ids(html: String) = Regex("""<h\d id="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        val plainIds = ids(page(myFixture.configureByText("plain.md", text)))
        val comarkIds = ids(page(myFixture.configureByText("doc.mdc", text)))
        assertEquals(listOf("usage", "usage-1", "usage-2"), plainIds)
        assertEquals(plainIds, comarkIds)
    }

    fun testLinkDefinitionsDoNotLeakIntoUnclosedFence() {
        val text = "[ref]: https://example.com\n\n::note\nhi\n::\n\n```js\nconst a = 1\n"
        val html = preview(text)
        val code = html.substringAfter("<code")
        assertFalse("link definition rendered inside the code block: $code", code.contains("[ref]: https:"))
        val fenceStart = text.indexOf("```js")
        for (m in Regex("""md-src-pos=["'](\d+)\.\.(\d+)["']""").findAll(code)) {
            assertTrue("code span range ${m.value} lies before the fence (offset $fenceStart)", m.groupValues[1].toInt() >= fenceStart)
            assertTrue("range ${m.value} past the document end", m.groupValues[2].toInt() <= text.length)
        }
    }

    fun testReferenceLinkForms() {
        val text = "::card\nA [full][Ref  One], [collapsed][], [short], ![img][pic], [missing][nope] and [span]{.x}\n::\n\n" +
            "[ref one]: https://example.com/one \"One\"\n[collapsed]: <https://example.com/two>\n[short]: /three\n[pic]: /p.png\n[span]: /no\n"
        val html = preview(text)
        assertTrue(html, html.contains("href=\"https://example.com/one\""))
        assertTrue(html, html.contains("title=\"One\""))
        assertTrue(html, html.contains("href=\"https://example.com/two\""))
        assertTrue(html, html.contains("/three\""))
        assertTrue(html, html.contains("/p.png"))
        assertTrue("undefined reference stays text: $html", html.contains("[missing][nope]"))
        assertTrue(html, html.contains("""<span class="comark-span" title="{.x}">"""))
        assertFalse(html, html.contains("href=\"/no\""))
        assertTrue(sourceOf(html, text, "<a").startsWith("[full]"))
    }

    fun testPreviewOfLargeDocumentWithLinkDefinitions() {
        val sb = StringBuilder()
        for (i in 0 until 1150) sb.append("::card\nSome **text** and [link][ref${i % 400}]\n::\n\n")
        for (i in 0 until 400) sb.append("[ref$i]: https://example.com/$i \"Title $i\"\n")
        val comark = myFixture.configureByText("perf.mdc", sb.toString())
        val plain = myFixture.addFileToProject("perf.md", sb.toString())
        fun measure(file: PsiFile): Long {
            page(file)
            val start = System.nanoTime()
            page(file)
            return (System.nanoTime() - start) / 1_000_000
        }
        val comarkTime = measure(comark)
        val plainTime = measure(plain)
        assertTrue("Comark preview ${comarkTime}ms vs plain Markdown ${plainTime}ms", comarkTime < plainTime * 10 + 500)
    }

    fun testUnclosedAndEmpty() {
        assertTrue(preview("::divider\n::\n").contains("""data-component="divider""""))
        assertTrue(preview("::unclosed\ntext\n").contains("text"))
        assertEquals("</body></html>", preview(""))
    }
}
