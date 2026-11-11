package cz.xprees.comark.components

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.util.PsiUtilBase
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.navigation.ComarkGotoDeclarationHandler
import cz.xprees.comark.navigation.ComarkSourceElement

/** Components whose `defineProps<Props>()` type is imported from a TypeScript file of the project. */
class ImportedPropTypesTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
    }

    private fun propNames(component: String): Set<String> =
        index().find(component)?.props?.map { it.name }?.toSet() ?: throw AssertionError("no component $component")

    private fun complete(text: String): List<String> {
        configure(text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun addAlert(importLine: String, typesPath: String) {
        myFixture.addFileToProject(typesPath, TYPES)
        myFixture.addFileToProject(
            "components/content/Alert.vue",
            "<script setup lang=\"ts\">\n$importLine\nwithDefaults(defineProps<Props>(), { type: 'info' })\n</script>\n<template><slot /></template>",
        )
    }

    fun testRelativeImport() {
        addAlert("import type { Props } from '../../types/alert'", "types/alert.ts")
        assertEquals(setOf("title", "type"), propNames("alert"))
        val type = index().find("alert")!!.findProp("type")!!
        assertEquals("'info'", type.defaultValue)
        assertEquals("alert.ts", type.file?.name)
    }

    fun testIndexAndDeclarationFiles() {
        myFixture.addFileToProject("types/index.ts", "export * from './badge'")
        myFixture.addFileToProject("types/badge.d.ts", "export interface BadgeProps { label: string }")
        myFixture.addFileToProject("components/Badge.vue", "<script setup lang=\"ts\">\nimport { type BadgeProps } from '../types'\ndefineProps<BadgeProps>()\n</script>")
        assertEquals(setOf("label"), propNames("badge"))
    }

    fun testSourceAliasesPointToTheRoot() {
        addAlert("import type { AlertProps as Props } from '~/types/alert'", "types/alert.ts")
        assertEquals(setOf("title", "type"), propNames("alert"))
    }

    fun testSourceAliasesPointToTheNuxt4AppDirectory() {
        myFixture.addFileToProject("app/types/alert.ts", TYPES)
        myFixture.addFileToProject("shared/base.ts", "export interface Base { id?: string }")
        myFixture.addFileToProject(
            "app/components/Notice.vue",
            "<script setup lang=\"ts\">\nimport type { Props } from '@/types/alert'\nimport type { Base } from '~~/shared/base'\ndefineProps<Props & Base>()\n</script>",
        )
        assertEquals(setOf("title", "type", "id"), propNames("notice"))
    }

    fun testCompletionOffersImportedPropsAndSeesEdits() {
        addAlert("import type { Props } from '~/types/alert'", "types/alert.ts")
        assertContainsElements(complete("::alert{<caret>}\n::"), "title", "type")

        // an unsaved edit of the imported file
        val types = myFixture.findFileInTempDir("types/alert.ts")
        val document = FileDocumentManager.getInstance().getDocument(types)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(TYPES.replace("title: string", "title: string\n  closable?: boolean"))
        }
        assertContainsElements(complete("::alert{<caret>}\n::"), "title", "type", "closable", ":closable")

        // a saved change on disk
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().saveAllDocuments()
            VfsUtil.saveText(types, TYPES.replace("title: string", "heading: string"))
        }
        val items = complete("::alert{<caret>}\n::")
        assertContainsElements(items, "heading", "type")
        assertDoesntContain(items, "title", "closable")
    }

    fun testCreatingTheImportedFileResolvesIt() {
        myFixture.addFileToProject(
            "components/content/Alert.vue",
            "<script setup lang=\"ts\">\nimport type { Props } from '../../types/alert'\ndefineProps<Props>()\n</script>",
        )
        assertEquals(emptySet<String>(), propNames("alert"))
        myFixture.addFileToProject("types/alert.ts", TYPES)
        assertEquals(setOf("title", "type"), propNames("alert"))
    }

    fun testAttributeGoesToTheImportedDeclaration() {
        addAlert("import type { Props } from '~/types/alert'", "types/alert.ts")
        configure("::alert{ti<caret>tle=\"Hi\"}\n::")
        val source = myFixture.file.findElementAt(myFixture.caretOffset)
        val target = ComarkGotoDeclarationHandler().getGotoDeclarationTargets(source, myFixture.caretOffset, myFixture.editor)!!.single()
        assertInstanceOf(target, ComarkSourceElement::class.java)
        assertEquals("alert.ts", target.containingFile.name)
        assertEquals(TYPES.indexOf("title: string"), target.textOffset)
        assertEquals("alert.ts", PsiUtilBase.getVirtualFile(target.navigationElement)!!.name)
    }

    fun testRefreshKeepsImportedProps() {
        addAlert("import type { Props } from '~/types/alert'", "types/alert.ts")
        assertEquals(setOf("title", "type"), propNames("alert"))
        ComarkComponentService.getInstance(project).refresh()
        assertEquals(setOf("title", "type"), propNames("alert"))
    }

    companion object {
        private val TYPES = """
            /** Props of the alert. */
            export interface Props {
              /** Heading shown above the content */
              title: string
              type?: 'info' | 'warning'
            }

            export type { Props as AlertProps }
        """.trimIndent()
    }
}
