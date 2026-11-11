package cz.xprees.comark.images

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import com.intellij.psi.util.parents
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/** Find Usages, Rename and Move of image files (and their directories) used by Comark documents. */
class ComarkImageRefactoringTest : NuxtImagesTestBase() {
    private val pageText = """
        ---
        title: Threats
        image: /threats/phishing.webp
        ---

        ![a](/threats/phishing.webp) ![b](https://cdn.example.com/threats/phishing.webp?v=2)
        ![c](/docs/_ipx/w_200/threats/phishing.webp#x) ![d][pic] ![e](/threats/other.png)

        [pic]: /threats/phishing.webp

        ::hero{src="/threats/phishing.webp" title="phishing.webp"}
        ---
        image: /threats/phishing.webp
        ---
        Text :img{src="/threats/phishing.webp"}.
        ::

        `![code](/threats/phishing.webp)` and phishing.webp in text.
    """.trimIndent() + "\n"

    private val allForms = listOf(
        "image: [/threats/phishing.webp]",
        "![a]([/threats/phishing.webp]) ![b](https://cdn.example.com/threats/phishing.webp?v=2)",
        "![a](/threats/phishing.webp) ![b](https://cdn.example.com[/threats/phishing.webp]?v=2)",
        "![c](/docs/_ipx/w_200[/threats/phishing.webp]#x) ![d][pic] ![e](/threats/other.png)",
        "[pic]: [/threats/phishing.webp]",
        "::hero{src=\"[/threats/phishing.webp]\" title=\"phishing.webp\"}",
        "image: [/threats/phishing.webp]",
        "Text :img{src=\"[/threats/phishing.webp]\"}.",
    )

    override fun setUp() {
        super.setUp()
        nuxtProject(nuxtConfig = "app: { baseURL: '/docs/', cdnURL: 'https://cdn.example.com' },\nimage: { dir: 'assets/images' },")
        webp("site/public/threats/phishing.webp")
        image("site/public/threats/other.png")
    }

    private fun open(text: String = pageText, path: String = "site/content/page.md"): PsiFile {
        val file = page(text, path)
        // the project detector re-parses open Markdown files later after setUp's changes; let that happen before the file is opened,
        // not in the middle of a refactoring (which skips usages whose PSI was replaced)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file)
        assertInstanceOf(myFixture.file, ComarkFile::class.java)
        return myFixture.file
    }

    private fun psi(path: String): PsiFile = PsiManager.getInstance(project).findFile(file(path))!!

    private fun dir(path: String): PsiDirectory = PsiManager.getInstance(project).findDirectory(file(path))!!

    private fun file(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("no $path")

    /** Each usage as its line (of the Comark file, for usages in injected YAML) with the hit in brackets, sorted by offset. */
    private fun usages(target: PsiElement): List<String> = myFixture.findUsages(target).map { usage ->
        val injections = InjectedLanguageManager.getInstance(project)
        val range = injections.injectedToHost(usage.element!!, TextRange.create(usage.segment!!))
        val text = injections.getTopLevelFile(usage.element!!).text
        val lineStart = text.lastIndexOf('\n', range.startOffset - 1) + 1
        val lineEnd = text.indexOf('\n', range.endOffset).let { if (it < 0) text.length else it }
        range.startOffset to text.substring(lineStart, range.startOffset) + "[" + text.substring(range.startOffset, range.endOffset) + "]" +
            text.substring(range.endOffset, lineEnd)
    }.sortedBy { it.first }.map { it.second }

    private fun text() = myFixture.editor.document.text

    /** [pageText] with [from] replaced by [to], except in the code span on its last line (an example, no usage). */
    private fun expected(from: String, to: String): String {
        val code = pageText.lastIndexOf("`![code]")
        return pageText.substring(0, code).replace(from, to) + pageText.substring(code)
    }

    private fun move(path: String, to: String) {
        val target = WriteAction.computeAndWait<VirtualFile, Throwable> {
            com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(myFixture.tempDirFixture.getFile("")!!, to)
        }
        val element: PsiElement = if (file(path).isDirectory) dir(path) else psi(path)
        MoveFilesOrDirectoriesProcessor(project, arrayOf(element), PsiManager.getInstance(project).findDirectory(target)!!, true, false, false, null, null).run()
    }

    fun testFindUsagesListsEveryFormOnce() {
        open()
        // not part of the Nuxt project: its paths resolve against the project directory
        myFixture.addFileToProject("outside/content/page.md", "![x](/threats/phishing.webp)\n")
        assertEquals(allForms, usages(psi("site/public/threats/phishing.webp")))
        assertEquals(listOf("![c](/docs/_ipx/w_200/threats/phishing.webp#x) ![d][pic] ![e]([/threats/other.png])"), usages(psi("site/public/threats/other.png")))
    }

    fun testReferencesToDirectory() {
        open()
        // Find Usages of a directory doesn't search references; Rename and Move of one do
        assertEquals(allForms.size + 1, ReferencesSearch.search(dir("site/public/threats")).findAll().size)
        assertEmpty(ReferencesSearch.search(dir("site/public")).findAll())
    }

    fun testNoUsagesWhenDisabled() {
        open()
        val image = psi("site/public/threats/phishing.webp")
        ComarkSettings.getInstance().imagesEnabled = false
        assertEmpty(usages(image))
    }

    fun testRenameKeepsPrefixesAndQuery() {
        open()
        myFixture.renameElement(psi("site/public/threats/phishing.webp"), "spoofing.webp")
        assertEquals(expected("/threats/phishing.webp", "/threats/spoofing.webp"), text())
        assertNotNull(myFixture.findFileInTempDir("site/public/threats/spoofing.webp"))
    }

    fun testRenameDirectory() {
        open()
        myFixture.renameElement(dir("site/public/threats"), "risks")
        assertEquals(expected("/threats/", "/risks/"), text())
    }

    fun testRenameNestedDirectory() {
        webp("site/public/a/b/deep.webp")
        open("![x](/a/b/deep.webp) :img{src=\"/a/b/deep.webp\"} and ![y](/a/b/deep.webp)\n")
        myFixture.renameElement(dir("site/public/a/b"), "c")
        assertEquals("![x](/a/c/deep.webp) :img{src=\"/a/c/deep.webp\"} and ![y](/a/c/deep.webp)\n", text())
    }

    fun testMoveToImageDirectoryKeepsPath() {
        open()
        move("site/public/threats/phishing.webp", "site/assets/images/threats")
        assertEquals(pageText, text())
        assertNotNull(myFixture.findFileInTempDir("site/assets/images/threats/phishing.webp"))
    }

    fun testMoveWithinPublicRewritesPath() {
        open()
        move("site/public/threats/phishing.webp", "site/public/other")
        assertEquals(expected("/threats/phishing.webp", "/other/phishing.webp"), text())
    }

    fun testMoveOutsideEveryDirectoryLeavesText() {
        open()
        move("site/public/threats/phishing.webp", "site/drafts")
        assertEquals(pageText, text())
    }

    fun testMoveDirectory() {
        open()
        move("site/public/threats", "site/public/archive")
        assertEquals(expected("/threats/", "/archive/threats/"), text())
    }

    fun testSingleEditWhenTheMarkdownFileReferenceResolvesToo() {
        // public/ as a resource root lets the Markdown plugin's own path references resolve as well (as WebStorm's Nuxt support may)
        val publicDir = file("site/public")
        PsiTestUtil.addSourceRoot(module, publicDir, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE)
        try {
            open("![a](/threats/phishing.webp) ![b](/threats/phishing.webp?v=1)\n")
            val destination = myFixture.file.findElementAt(5)!!.parents(withSelf = true).filterIsInstance<MarkdownLinkDestination>().first()
            val others = destination.references.filterNotNull().filter { it !is ComarkImageReference }
            assertTrue(others.toString(), others.any { (it.resolve() as? PsiFile)?.name == "phishing.webp" })
            myFixture.renameElement(psi("site/public/threats/phishing.webp"), "spoofing.webp")
            assertEquals("![a](/threats/spoofing.webp) ![b](/threats/spoofing.webp?v=1)\n", text())
        } finally {
            PsiTestUtil.removeSourceRoot(module, publicDir)
        }
    }
}
