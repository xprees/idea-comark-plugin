package cz.xprees.comark.components

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import cz.xprees.comark.navigation.ComarkDocumentationRenderer
import cz.xprees.comark.navigation.ComarkDocumentationTarget
import cz.xprees.comark.navigation.ComarkDocumentationTargetProvider
import cz.xprees.comark.navigation.ComarkLookupObject
import cz.xprees.comark.navigation.ComarkPsiDocumentationTargetProvider

class ComponentDocumentationTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        addStandardComponents()
    }

    private fun targetAt(text: String): DocumentationTarget? {
        configure(text)
        return ComarkDocumentationTargetProvider().documentationTargets(myFixture.file, myFixture.caretOffset).singleOrNull()
    }

    /** The HTML [target] documents with (the target hands exactly this to the platform). */
    private fun html(target: DocumentationTarget?): String {
        assertNotNull("no documentation target", target)
        assertInstanceOf(target!!.computeDocumentation(), DocumentationResult.Documentation::class.java)
        return ComarkDocumentationRenderer.render((target as ComarkDocumentationTarget).symbol)
    }

    fun testComponentDocumentation() {
        val html = html(targetAt("::al<caret>ert\n::"))
        assertTrue(html, html.contains("::alert"))
        assertTrue(html, html.contains("Highlights important information."))
        // props table: name, type, required, default
        assertTrue(html, html.contains("<table>"))
        for (expected in listOf("title", "string", "dismissible", "boolean", "&#39;info&#39;", "yes")) {
            assertTrue("$expected missing in $html", html.contains(expected) || html.contains(expected.replace("&#39;", "'")))
        }
        assertTrue(html, html.contains("Visual style"))
        // slots
        assertTrue(html, html.contains("#title"))
        assertTrue(html, html.contains("#default"))
        assertTrue(html, html.contains("components/content/Alert.vue"))
    }

    fun testInlineComponentDocumentation() {
        val html = html(targetAt("Text :ba<caret>dge[New] more"))
        assertTrue(html, html.contains("::badge"))
        assertTrue(html, html.contains("label"))
    }

    fun testPropDocumentation() {
        val html = html(targetAt("::alert{ty<caret>pe=\"info\"}\n::"))
        assertTrue(html, html.contains("Visual style"))
        assertTrue(html, html.contains("alert"))
        val presentation = targetAt("::alert{ty<caret>pe=\"info\"}\n::")!!.computePresentation()
        assertEquals("type", presentation.presentableText)
    }

    fun testSlotDocumentation() {
        val html = html(targetAt("::alert\n#ti<caret>tle\nx\n::"))
        assertTrue(html, html.contains("#title"))
    }

    fun testPropsBlockKeyDocumentation() {
        val html = html(targetAt("::alert\n---\ntit<caret>le: Hi\n---\n::"))
        assertTrue(html, html.contains("Heading shown above the content"))
    }

    fun testMarkdownDescriptionsAreRendered() {
        myFixture.addFileToProject("components/content/Note.vue", "<script setup>\n/** A **bold** note with `code`. */\ndefineProps(['a'])\n</script>")
        val html = html(targetAt("::no<caret>te\n::"))
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<code>code</code>"))
    }

    fun testNoDocumentationForUnknownOrPlainText() {
        assertNull(targetAt("::unkn<caret>own\n::"))
        assertNull(targetAt("Plain te<caret>xt"))
    }

    fun testLookupItemDocumentation() {
        configure("::<caret>")
        myFixture.completeBasic()
        val item = myFixture.lookupElements!!.first { it.lookupString == "alert" }
        val element = item.psiElement
        assertInstanceOf(element, ComarkLookupObject::class.java)
        val target = ComarkPsiDocumentationTargetProvider().documentationTarget(element!!, null)
        assertTrue(html(target).contains("Highlights important information."))
    }

    fun testHintAndPointer() {
        val target = targetAt("::al<caret>ert\n::") as ComarkDocumentationTarget
        assertTrue(target.computeDocumentationHint().contains("alert"))
        assertSame(target, target.createPointer().dereference())
    }

    fun testProvidersAreRegistered() {
        val targetProviders = ExtensionPointName<DocumentationTargetProvider>("com.intellij.platform.backend.documentation.targetProvider").extensionList
        assertTrue(targetProviders.any { it is ComarkDocumentationTargetProvider })
        val psiProviders = ExtensionPointName<PsiDocumentationTargetProvider>("com.intellij.platform.backend.documentation.psiTargetProvider").extensionList
        assertTrue(psiProviders.any { it is ComarkPsiDocumentationTargetProvider })
    }
}
