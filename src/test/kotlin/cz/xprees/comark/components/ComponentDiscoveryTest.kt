package cz.xprees.comark.components

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.DumbModeTestUtils
import cz.xprees.comark.meta.ComponentSource
import java.io.File

class ComponentDiscoveryTest : ComponentsTestBase() {
    fun testVueComponentsAreDiscoveredWithNuxtNames() {
        addStandardComponents()
        val index = index()
        assertEquals(listOf("alert", "badge", "prose-card"), index.visible().map { it.kebabName })

        val alert = index.find("alert")!!
        assertEquals("Alert", alert.name)
        assertEquals(ComponentSource.VUE_FILE, alert.source)
        assertEquals("Highlights important information.", alert.description)
        assertEquals(listOf("type", "title", "dismissible", "count"), alert.props.map { it.name })
        assertEquals("'info'", alert.findProp("type")!!.defaultValue)
        assertEquals(listOf("title", "default"), alert.slots.map { it.name })
        assertEquals("Alert.vue", alert.file!!.name)
        assertEquals("src", alert.root!!.name)

        val card = index.find("prose-card")!!
        assertEquals("A card with an optional footer.", card.description)
        assertTrue(card.findProp("items")!!.required)
    }

    fun testNamesResolveInAnySpelling() {
        addStandardComponents()
        val index = index()
        for (name in listOf("prose-card", "ProseCard", "proseCard", "PROSE-CARD".lowercase())) {
            assertEquals(name, "ProseCard", index.find(name)?.name)
        }
        // Nuxt Content registers components/content without path prefixes: the bare file name resolves too
        assertEquals("ProseCard", index.find("card")?.name)
        assertNull(index.find("nope"))
    }

    fun testDependenciesAndBuildOutputAreSkipped() {
        addStandardComponents()
        myFixture.addFileToProject("node_modules/lib/components/Foreign.vue", BADGE)
        myFixture.addFileToProject("dist/components/Built.vue", BADGE)
        myFixture.addFileToProject(".output/components/Output.vue", BADGE)
        myFixture.addFileToProject("pages/index.vue", BADGE)
        val names = index().components.map { it.name }
        assertSameElements(names, "Alert", "ProseCard", "Badge")
    }

    fun testComponentMetaIsMergedWithVueFiles() {
        addStandardComponents()
        myFixture.addFileToProject(".nuxt/component-meta.mjs", File("src/test/testData/components/component-meta.mjs").readText())
        val index = index()

        val alert = index.find("alert")!!
        assertEquals(ComponentSource.COMPONENT_META, alert.source)
        assertEquals("Alert.vue", alert.file?.name)
        // props come from the metadata, descriptions missing there from the .vue file
        assertEquals(listOf("type", "title", "dismissible"), alert.props.map { it.name })
        assertEquals("Visual style of the alert", alert.findProp("type")!!.description)
        assertEquals("Heading shown above the content", alert.findProp("title")!!.description)
        assertEquals("Highlights important information.", alert.description)

        val proseA = index.find("prose-a")!!
        assertEquals(ComponentSource.COMPONENT_META, proseA.source)
        assertNull(proseA.file)
        assertEquals(listOf("href", "target"), proseA.props.map { it.name })
    }

    fun testMetaWithoutVueFiles() {
        myFixture.addFileToProject("apps/docs/.nuxt/component-meta.mjs", File("src/test/testData/components/component-meta.mjs").readText())
        val index = index()
        assertSameElements(index.components.map { it.name }, "Alert", "ProseA")
        assertEquals("docs", index.find("alert")!!.root!!.name)
    }

    fun testSavedChangesAreSeen() {
        addStandardComponents()
        assertNull(index().find("badge")!!.findProp("size"))
        val badge = myFixture.findFileInTempDir("components/Badge.vue")
        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(badge, BADGE.replace("label: string", "label: string; size?: number"))
        }
        assertNotNull(index().find("badge")!!.findProp("size"))
    }

    fun testUnsavedEditorChangesAreSeen() {
        addStandardComponents()
        val badge = myFixture.findFileInTempDir("components/Badge.vue")
        val document = FileDocumentManager.getInstance().getDocument(badge)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(BADGE.replace("label: string", "label: string; outlined?: boolean"))
        }
        assertNotNull(index().find("badge")!!.findProp("outlined"))
    }

    fun testNewAndDeletedFilesAreSeen() {
        addStandardComponents()
        assertNull(index().find("hero"))
        val hero = myFixture.addFileToProject("components/content/Hero.vue", BADGE).virtualFile
        assertNotNull(index().find("hero"))
        WriteCommandAction.runWriteCommandAction(project) { hero.delete(this) }
        assertNull(index().find("hero"))
    }

    fun testRefreshAction() {
        addStandardComponents()
        assertNotNull(index().find("alert"))
        val action = ActionManager.getInstance().getAction("Comark.RefreshComponents")
        assertNotNull("action registered", action)
        assertEquals("Refresh Comark Components", action.templatePresentation.text)
        myFixture.testAction(action)
        assertNotNull(index().find("alert"))
    }

    fun testWorksInDumbMode() {
        addStandardComponents()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNotNull(index().find("alert"))
        }
    }

    fun testAppOfTheMarkdownFileWins() {
        myFixture.addFileToProject("apps/a/components/Hero.vue", "<script setup>defineProps(['fromA'])</script>")
        // Nuxt 4 layout: components live in app/, the app root is the directory with nuxt.config
        myFixture.addFileToProject("apps/b/nuxt.config.ts", "export default defineNuxtConfig({})")
        myFixture.addFileToProject("apps/b/app/components/Hero.vue", "<script setup>defineProps(['fromB'])</script>")
        val a = myFixture.addFileToProject("apps/a/content/index.mdc", "::hero\n::").virtualFile
        val b = myFixture.addFileToProject("apps/b/content/index.mdc", "::hero\n::").virtualFile
        val index = index()
        assertEquals("fromA", index.find("hero", a)!!.props.single().name)
        assertEquals("fromB", index.find("hero", b)!!.props.single().name)
        assertEquals(1, index.visible(a).size)
        assertEquals("b", index.find("hero", b)!!.root!!.name)
    }
}
