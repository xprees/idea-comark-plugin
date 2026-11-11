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
        assertEquals(listOf("alert", "badge", "card"), index.visible().map { it.kebabName })

        val alert = index.find("alert")!!
        assertEquals("Alert", alert.name)
        assertEquals(ComponentSource.VUE_FILE, alert.source)
        assertEquals("Highlights important information.", alert.description)
        assertEquals(listOf("type", "title", "dismissible", "count"), alert.props.map { it.name })
        assertEquals("'info'", alert.findProp("type")!!.defaultValue)
        assertEquals(listOf("title", "default"), alert.slots.map { it.name })
        assertEquals("Alert.vue", alert.file!!.name)
        assertEquals("src", alert.root!!.name)

        val card = index.find("card")!!
        assertEquals("A card with an optional footer.", card.description)
        assertTrue(card.findProp("items")!!.required)
    }

    fun testNamesResolveInAnySpelling() {
        addStandardComponents()
        val index = index()
        for (name in listOf("card", "Card", "CARD".lowercase())) {
            assertEquals(name, "Card", index.find(name)?.name)
        }
        // Nuxt Content registers components/content without path prefixes: the path-prefixed name is never registered
        assertNull(index.find("prose-card"))
        assertNull(index.find("nope"))
    }

    fun testDependenciesAndBuildOutputAreSkipped() {
        addStandardComponents()
        myFixture.addFileToProject("node_modules/lib/components/Foreign.vue", BADGE)
        myFixture.addFileToProject("dist/components/Built.vue", BADGE)
        myFixture.addFileToProject(".output/components/Output.vue", BADGE)
        myFixture.addFileToProject("pages/index.vue", BADGE)
        val names = index().components.map { it.name }
        assertSameElements(names, "Alert", "Card", "Badge")
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

    fun testModuleComponentFromRealComponentMetaIsCompleted() {
        myFixture.addFileToProject("package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("components/content/Local.vue", "<template><div/></template>")
        val module = File("src/test/testData/components/component-meta-deduplicated.mjs").readText()
        myFixture.addFileToProject(".nuxt/component-meta.mjs", module.replace("components/content/Alert.vue", "node_modules/ui/Alert.vue"))
        assertTrue(index().components.any { it.source == ComponentSource.COMPONENT_META })
        assertEquals(listOf("title", "open"), index().find("alert")!!.props.map { it.name })
    }

    fun testUnsavedHalfTypedDefineModelKeepsCompletionWorking() {
        val vue = myFixture.addFileToProject("components/content/Alert.vue", ALERT)
        myFixture.addFileToProject("components/Badge.vue", BADGE)
        myFixture.openFileInEditor(vue.virtualFile)
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("<script setup lang=\"ts\">\nconst model = defineModel('\n</script>\n<template><slot/></template>\n")
        }
        configure("::<caret>")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "badge", "alert")
    }

    fun testIndexRebuildAfterVueKeystroke() {
        myFixture.addFileToProject("package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        val vues = (0 until 400).map { i -> myFixture.addFileToProject("components/content/group${i % 20}/Widget$i.vue", ALERT) }
        val json = buildString {
            append("{")
            for (i in 0 until 800) {
                if (i > 0) append(",")
                append("\"UModule$i\": {\"pascalName\": \"UModule$i\", \"filePath\": \"node_modules/@nuxt/ui/dist/runtime/components/Module$i.vue\", ")
                append("\"meta\": {\"props\": [{\"name\": \"color\", \"type\": \"string\"}], \"slots\": []}}")
            }
            append("}")
        }
        myFixture.addFileToProject(".nuxt/component-meta.json", json)
        index()
        val document = FileDocumentManager.getInstance().getDocument(vues[0].virtualFile)!!
        val times = ArrayList<Long>()
        repeat(5) {
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
            val start = System.nanoTime()
            val size = index().components.size
            times += (System.nanoTime() - start) / 1_000_000
            assertTrue(size >= 1200)
        }
        assertTrue("index rebuild after one keystroke in a .vue file took $times ms", times.max() < 300)
    }

    fun testEditedTypeDependencyStillInvalidatesTheIndex() {
        myFixture.addFileToProject("types/alert.ts", "export interface AlertProps { title: string }")
        myFixture.addFileToProject(
            "components/content/Alert.vue",
            "<script setup lang=\"ts\">\nimport type { AlertProps } from '../../types/alert'\ndefineProps<AlertProps>()\n</script>\n",
        )
        assertEquals(listOf("title"), index().find("alert")!!.props.map { it.name })
        val types = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("types/alert.ts"))!!
        WriteCommandAction.runWriteCommandAction(project) { types.setText("export interface AlertProps { title: string; icon?: string }") }
        assertEquals(listOf("title", "icon"), index().find("alert")!!.props.map { it.name })
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
