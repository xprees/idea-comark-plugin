package cz.xprees.comark.components

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.xprees.comark.completion.ComarkCompletionAutoPopupHandler
import cz.xprees.comark.psi.ComarkPropsContent
import cz.xprees.comark.settings.ComarkSettings
import org.jetbrains.yaml.YAMLLanguage

class ComponentCompletionTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        addStandardComponents()
    }

    private fun complete(text: String): List<String> {
        configure(text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun assertCompletes(before: String, after: String) {
        configure(before)
        val items = myFixture.completeBasic()
        assertTrue("expected a single completion, got ${items?.map { it.lookupString }}", items == null || items.isEmpty())
        myFixture.checkResult(after)
    }

    private fun select(lookupString: String) {
        val item = myFixture.lookupElements!!.first { it.lookupString == lookupString }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    // ---- Block component names --------------------------------------------------------------------------------------

    fun testBlockComponentNames() {
        assertContainsElements(complete("::<caret>"), "alert", "badge", "card")
        // Nuxt Content registers components/content/prose/Card.vue only as `Card`
        assertDoesntContain(complete("::<caret>"), "prose-card")
    }

    fun testBlockComponentInsertsClosingMarker() {
        assertCompletes("::ale<caret>", "::alert\n<caret>\n::")
    }

    fun testNestedComponentKeepsItsMarkers() {
        assertCompletes("::alert\n:::bad<caret>\n::", "::alert\n:::badge\n<caret>\n:::\n::")
    }

    fun testSameMarkerNestingGetsItsOwnCloser() {
        assertCompletes("::alert\n::bad<caret>\n::", "::alert\n::badge\n<caret>\n::\n::")
    }

    fun testRenamingAClosedComponentAddsNothing() {
        assertCompletes("::bad<caret>\ncontent\n::", "::badge<caret>\ncontent\n::")
    }

    fun testExistingPropsBlockIsKept() {
        assertCompletes("::ale<caret>\n---\ntitle: x\n---\n::", "::alert<caret>\n---\ntitle: x\n---\n::")
    }

    fun testIndentedComponent() {
        assertCompletes("::alert\n  :::bad<caret>\n::", "::alert\n  :::badge\n  <caret>\n  :::\n::")
    }

    fun testPascalCaseInputInsertsPascalCase() {
        assertCompletes("::Car<caret>", "::Card\n<caret>\n::")
    }

    fun testNuxtContentNestedComponentCompletesWithItsRegisteredName() {
        // Nuxt Content registers components/content with pathPrefix: false, before ~/components; Nuxt skips the files again
        // when it scans ~/components, so components/content/prose/Card.vue is only ever `Card` at runtime.
        configure("::car<caret>")
        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first()
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertEquals("::card\n\n::", myFixture.editor.document.text)
    }

    fun testLookupPresentation() {
        configure("::<caret>")
        myFixture.completeBasic()
        val alert = myFixture.lookupElements!!.first { it.lookupString == "alert" }
        val presentation = LookupElementPresentation.renderElement(alert)
        assertEquals("components/content/Alert.vue", presentation.typeText)
        assertEquals(" Highlights important information", presentation.tailText)
        assertNotNull(presentation.icon)
    }

    fun testNoComponentsInCodeFence() {
        assertDoesntContain(complete("```\n::<caret>\n```"), "alert", "badge")
        assertDoesntContain(complete("::alert\n```js\nconst x = ::<caret>\n```\n::"), "alert", "badge")
        // a ```md fence is injected as Comark; still a code example
        assertDoesntContain(complete("```md\n::<caret>\n```"), "alert", "badge")
    }

    fun testNoComponentsInIndentedCode() {
        assertDoesntContain(complete("Text\n\n    ::<caret>\n"), "alert", "badge")
    }

    fun testNoComponentsInFrontMatter() {
        assertDoesntContain(complete("---\ntitle: ::<caret>\n---\n"), "alert", "badge")
    }

    fun testCompletionCanBeDisabled() {
        ComarkSettings.getInstance().componentCompletion = false
        assertDoesntContain(complete("::<caret>"), "alert", "badge")
    }

    // ---- Inline components ------------------------------------------------------------------------------------------

    fun testInlineComponentNames() {
        assertContainsElements(complete("Some text :<caret>"), "alert", "badge")
        assertCompletes("Some text :bad<caret> more", "Some text :badge<caret> more")
    }

    fun testInlineComponentAtLineStartAndAfterEmphasis() {
        assertContainsElements(complete(":<caret>"), "badge")
        assertContainsElements(complete("**bold**:<caret>"), "badge")
    }

    fun testNoInlineComponentAfterWordCharacter() {
        assertDoesntContain(complete("Note:<caret>"), "badge")
        assertDoesntContain(complete("see https:<caret>"), "badge")
    }

    fun testNoInlineComponentInInlineCode() {
        assertDoesntContain(complete("Use `x :<caret>` here"), "badge")
    }

    fun testInlineComponentInsideBlockContent() {
        assertContainsElements(complete("::alert[See :<caret>]\n::"), "badge")
    }

    // ---- Attributes -------------------------------------------------------------------------------------------------

    fun testBlockAttributes() {
        val items = complete("::alert{<caret>}\n::")
        assertContainsElements(items, "type", "title", "dismissible", ":dismissible", "count", ":count")
        // strings are never bound
        assertDoesntContain(items, ":type", ":title")
    }

    fun testRequiredPropsComeFirst() {
        val items = complete("::alert{<caret>}\n::")
        assertEquals("title", items.first())
    }

    fun testAttributeValueSkeleton() {
        assertCompletes("::alert{tit<caret>}\n::", "::alert{title=\"<caret>\"}\n::")
    }

    fun testBooleanAttributeIsBare() {
        configure("::alert{dism<caret>}\n::")
        myFixture.completeBasic()
        if (myFixture.lookupElements != null) select("dismissible")
        myFixture.checkResult("::alert{dismissible<caret>}\n::")
    }

    fun testBindingVariant() {
        configure("::alert{:cou<caret>}\n::")
        myFixture.completeBasic()
        myFixture.checkResult("::alert{:count=\"<caret>\"}\n::")
    }

    fun testExistingAttributesAreNotOffered() {
        val items = complete("::alert{title=\"Hi\" <caret>}\n::")
        assertDoesntContain(items, "title")
        assertContainsElements(items, "type")
    }

    fun testUnclosedAttributeGroup() {
        assertContainsElements(complete("::alert{type=\"info\" <caret>\n::"), "title")
    }

    fun testNoAttributesInValuesClassesOrIds() {
        assertDoesntContain(complete("::alert{title=\"<caret>\"}\n::"), "type")
        assertDoesntContain(complete("::alert{.cls<caret>}\n::"), "type")
        assertDoesntContain(complete("::alert{#id<caret>}\n::"), "type")
    }

    fun testInlineComponentAttributes() {
        assertContainsElements(complete("Text :badge{<caret>}"), "color", "label")
        assertCompletes("Text :badge[New]{col<caret>}", "Text :badge[New]{color=\"<caret>\"}")
    }

    fun testAttributeLookupShowsType() {
        configure("::alert{<caret>}\n::")
        myFixture.completeBasic()
        val type = myFixture.lookupElements!!.first { it.lookupString == "type" }
        val presentation = LookupElementPresentation.renderElement(type)
        assertEquals("'info' | 'warning' | 'danger'", presentation.typeText)
        assertEquals(" = 'info'  Visual style", presentation.tailText)
        val title = myFixture.lookupElements!!.first { it.lookupString == "title" }
        assertTrue(LookupElementPresentation.renderElement(title).isItemTextBold)
        assertEquals(" (required)  Heading shown above the content", LookupElementPresentation.renderElement(title).tailText)
    }

    // ---- Values -----------------------------------------------------------------------------------------------------

    fun testLiteralUnionValuesInAttributes() {
        assertSameElements(complete("::alert{type=\"<caret>\"}\n::"), "info", "warning", "danger")
        assertCompletes("::alert{type=\"wa<caret>\"}\n::", "::alert{type=\"warning<caret>\"}\n::")
        assertContainsElements(complete("::alert{title=\"x\" type='<caret>'}\n::"), "danger")
    }

    fun testDefaultValueIsMarked() {
        configure("::alert{type=\"<caret>\"}\n::")
        myFixture.completeBasic()
        val info = myFixture.lookupElements!!.first { it.lookupString == "info" }
        assertEquals(" (default)", LookupElementPresentation.renderElement(info).tailText)
    }

    fun testBooleanBindingValues() {
        assertSameElements(complete("::alert{:dismissible=\"<caret>\"}\n::"), "true", "false")
    }

    fun testNoValuesForFreeText() {
        assertDoesntContain(complete("::alert{title=\"<caret>\"}\n::"), "info", "true")
        assertDoesntContain(complete("::alert{type=\"info\"<caret>}\n::"), "info", "warning")
    }

    fun testLiteralUnionValuesInPropsBlock() {
        assertSameElements(complete("::alert\n---\ntype: <caret>\n---\n::"), "info", "warning", "danger")
        assertCompletes("::alert\n---\ntitle: Hi\ntype: da<caret>\n---\n::", "::alert\n---\ntitle: Hi\ntype: danger<caret>\n---\n::")
    }

    fun testInlineComponentValues() {
        myFixture.addFileToProject("components/Pill.vue", "<script setup lang=\"ts\">\ndefineProps<{ tone?: 'muted' | 'loud' }>()\n</script>")
        assertSameElements(complete("Text :pill{tone=\"<caret>\"}"), "muted", "loud")
    }

    // ---- Slots ------------------------------------------------------------------------------------------------------

    fun testSlotNames() {
        assertContainsElements(complete("::alert\n#<caret>\n::"), "title", "default")
    }

    fun testUsedSlotsAreNotOffered() {
        val items = complete("::alert\n#title\nHeading\n\n#<caret>\n::")
        assertContainsElements(items, "default")
        assertDoesntContain(items, "title")
    }

    fun testHeadingIsNotASlot() {
        assertDoesntContain(complete("::alert\n# <caret>\n::"), "title", "default")
    }

    fun testNoSlotsOutsideComponents() {
        assertDoesntContain(complete("#<caret>\n"), "title", "default")
    }

    fun testSlotsOfTheInnermostComponent() {
        val items = complete("::alert\n:::card\n#<caret>\n:::\n::")
        assertContainsElements(items, "footer")
        assertDoesntContain(items, "title")
    }

    // ---- Props block ------------------------------------------------------------------------------------------------

    fun testPropsBlockKeys() {
        assertContainsElements(complete("::alert\n---\n<caret>\n---\n::"), "type", "title", "dismissible")
    }

    fun testPropsBlockKeyInsertion() {
        assertCompletes("::alert\n---\ntit<caret>\n---\n::", "::alert\n---\ntitle: <caret>\n---\n::")
        assertCompletes("::alert\n---\ndismi<caret>\n---\n::", "::alert\n---\ndismissible: true<caret>\n---\n::")
        assertCompletes("::card\n---\nite<caret>\n---\n::", "::card\n---\nitems:\n  - <caret>\n---\n::")
    }

    fun testPropsBlockSkipsExistingAndNestedKeys() {
        val items = complete("::alert\n---\ntitle: Hello\n<caret>\n---\n::")
        assertDoesntContain(items, "title")
        assertContainsElements(items, "type")
        assertDoesntContain(complete("::alert\n---\ntitle: Hello\nnested:\n  <caret>\n---\n::"), "type")
    }

    fun testPropsBlockOfIndentedComponent() {
        assertContainsElements(complete("::alert\n  :::badge\n  ---\n  <caret>\n  ---\n  :::\n::"), "color", "label")
    }

    fun testInjectedYamlPropsBlock() {
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(YamlPropsInjector(), testRootDisposable)
        configure("::alert\n---\ntitle: x\nty<caret>\n---\n::")
        // with the caret in an injection the fixture works on the injected file
        assertTrue(InjectedLanguageManager.getInstance(project).isInjectedFragment(myFixture.file))
        myFixture.completeBasic()
        myFixture.checkResult("::alert\n---\ntitle: x\ntype: <caret>\n---\n::")
    }

    // ---- Auto popup -------------------------------------------------------------------------------------------------

    fun testAutoPopupTriggers() {
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("::"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("  :::"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("::alert{"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("::alert{type=\"info\" "))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("Text :badge[New]{"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopup("::alert{:"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopup("::alert{title=\"Hello "))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopup("Note:"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopup("a::"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopup("word{"))
    }

    /** Stands in for the YAML injection into props blocks (provided by the editor feature area). */
    private class YamlPropsInjector : MultiHostInjector {
        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            if (context !is ComarkPropsContent) return
            registrar.startInjecting(YAMLLanguage.INSTANCE).addPlace(null, null, context, TextRange(0, context.textLength)).doneInjecting()
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(ComarkPropsContent::class.java)
    }
}
