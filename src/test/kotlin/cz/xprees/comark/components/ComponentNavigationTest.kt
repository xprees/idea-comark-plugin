package cz.xprees.comark.components

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilBase
import cz.xprees.comark.navigation.ComarkGotoDeclarationHandler
import cz.xprees.comark.navigation.ComarkSourceElement
import cz.xprees.comark.psi.ComarkPropsContent
import org.jetbrains.yaml.YAMLLanguage

class ComponentNavigationTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        addStandardComponents()
    }

    /**
     * Targets of Go to Declaration at the caret. With the caret in an injected fragment the fixture's file and caret offset are
     * the injected ones, which is also what the platform hands to declaration handlers (injection first, then host).
     */
    private fun targets(text: String): List<PsiElement> {
        configure(text)
        val source = myFixture.file.findElementAt(myFixture.caretOffset)
        return ComarkGotoDeclarationHandler().getGotoDeclarationTargets(source, myFixture.caretOffset, myFixture.editor)?.toList().orEmpty()
    }

    private fun vueText(path: String) = myFixture.findFileInTempDir(path).let { String(it.contentsToByteArray()) }

    private fun assertSourceTarget(target: PsiElement, file: String, offset: Int) {
        assertInstanceOf(target, ComarkSourceElement::class.java)
        assertEquals(file, target.containingFile.name)
        assertEquals(offset, target.textOffset)
        assertEquals(file, (PsiUtilBase.getVirtualFile(target.navigationElement) ?: error("no file")).name)
    }

    fun testBlockComponentNameGoesToFile() {
        val target = targets("::al<caret>ert\n::").single()
        assertInstanceOf(target, PsiFile::class.java)
        assertEquals("Alert.vue", (target as PsiFile).name)
    }

    fun testCaretRightAfterTheName() {
        assertEquals("Alert.vue", (targets("::alert<caret>\n::").single() as PsiFile).name)
    }

    fun testInlineComponentNameGoesToFile() {
        assertEquals("Badge.vue", (targets("Some :ba<caret>dge here").single() as PsiFile).name)
        assertEquals("Badge.vue", (targets("Some :badge[New]{color=\"x\"} and :bad<caret>ge").single() as PsiFile).name)
    }

    fun testAttributeGoesToPropDeclaration() {
        val target = targets("::alert{ti<caret>tle=\"Hi\"}\n::").single()
        assertSourceTarget(target, "Alert.vue", vueText("components/content/Alert.vue").indexOf("title: string"))
    }

    fun testBindingAttributeGoesToPropDeclaration() {
        val target = targets("::alert{:cou<caret>nt=\"3\"}\n::").single()
        assertSourceTarget(target, "Alert.vue", vueText("components/content/Alert.vue").indexOf("count?: number"))
    }

    fun testKebabCaseAttributeFindsCamelCaseProp() {
        myFixture.addFileToProject("components/Icon.vue", "<script setup lang=\"ts\">\ndefineProps<{ iconName: string }>()\n</script>")
        val target = targets("::icon{icon-na<caret>me=\"x\"}\n::").single()
        assertSourceTarget(target, "Icon.vue", vueText("components/Icon.vue").indexOf("iconName"))
    }

    fun testInlineAttributeGoesToPropDeclaration() {
        val target = targets("Text :badge[New]{co<caret>lor=\"red\"}").single()
        assertSourceTarget(target, "Badge.vue", vueText("components/Badge.vue").indexOf("color"))
    }

    fun testSlotGoesToSlotTag() {
        val text = vueText("components/content/Alert.vue")
        assertSourceTarget(targets("::alert\n#ti<caret>tle\nHeading\n::").single(), "Alert.vue", text.indexOf("title\" />"))
        assertSourceTarget(targets("::alert\n#title\nx\n#def<caret>ault\ny\n::").single(), "Alert.vue", text.indexOf("<slot />") + 1)
    }

    fun testPropsBlockKeyGoesToPropDeclaration() {
        val target = targets("::alert\n---\nty<caret>pe: info\n---\n::").single()
        assertSourceTarget(target, "Alert.vue", vueText("components/content/Alert.vue").indexOf("type?:"))
    }

    fun testInjectedPropsBlockKeyGoesToPropDeclaration() {
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(YamlPropsInjector(), testRootDisposable)
        val target = targets("::alert\n---\ntitle: x\ndismis<caret>sible: true\n---\n::").single()
        assertTrue(InjectedLanguageManager.getInstance(project).isInjectedFragment(myFixture.file))
        assertSourceTarget(target, "Alert.vue", vueText("components/content/Alert.vue").indexOf("dismissible?:"))
    }

    fun testUnknownPropFallsBackToTheFile() {
        assertEquals("Alert.vue", (targets("::alert{unkn<caret>own=\"x\"}\n::").single() as PsiFile).name)
    }

    fun testNothingForUnknownComponentsAndPlainText() {
        assertEmpty(targets("::unkn<caret>own\n::"))
        assertEmpty(targets("Plain te<caret>xt"))
        assertEmpty(targets("`:ba<caret>dge`"))
        assertEmpty(targets("::alert{title=\"H<caret>i\"}\n::"))
    }

    fun testComponentWithoutFileHasNoTarget() {
        myFixture.addFileToProject(".nuxt/component-meta.json", """{ "Remote": { "pascalName": "Remote", "meta": { "props": [] } } }""")
        assertNotNull(index().find("remote"))
        assertEmpty(targets("::rem<caret>ote\n::"))
    }

    private class YamlPropsInjector : MultiHostInjector {
        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            if (context !is ComarkPropsContent) return
            registrar.startInjecting(YAMLLanguage.INSTANCE).addPlace(null, null, context, TextRange(0, context.textLength)).doneInjecting()
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(ComarkPropsContent::class.java)
    }
}
