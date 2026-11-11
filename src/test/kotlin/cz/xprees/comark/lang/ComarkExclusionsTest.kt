package cz.xprees.comark.lang

import com.intellij.ide.FileIconProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.ComarkIcons
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

class ComarkExclusionsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ComarkSettings.getInstance().excludedPatterns = ""
            ComarkSettings.getInstance().mode = ComarkSettings.Mode.AUTO
        } finally {
            super.tearDown()
        }
    }

    fun testNamePatternsMatchAtAnyLevel() {
        assertTrue(ComarkExclusions.matches("CHANGELOG.md", "CHANGELOG.md"))
        assertTrue(ComarkExclusions.matches("site/content/CHANGELOG.md", "CHANGELOG.md"))
        assertTrue(ComarkExclusions.matches("site/content/a.draft.md", "*.draft.md"))
        assertTrue(ComarkExclusions.matches("site/legacy/page.md", "legacy"))
        assertFalse(ComarkExclusions.matches("site/content/page.md", "CHANGELOG.md\n*.draft.md\nlegacy"))
    }

    fun testPathPatternsMatchFromTheProjectDirectory() {
        assertTrue(ComarkExclusions.matches("docs/legacy/a/b.md", "docs/legacy/**"))
        assertTrue(ComarkExclusions.matches("docs/legacy/a/b.md", "docs/legacy"))
        assertTrue(ComarkExclusions.matches("docs/legacy/a/b.md", "/docs/legacy/"))
        assertTrue(ComarkExclusions.matches("README.md", "/README.md"))
        assertFalse(ComarkExclusions.matches("site/README.md", "/README.md"))
        assertFalse(ComarkExclusions.matches("site/docs/legacy/b.md", "docs/legacy"))
        assertTrue(ComarkExclusions.matches("site/docs/legacy/b.md", "**/docs/legacy"))
    }

    fun testCommentsBlankLinesAndInvalidPatterns() {
        assertFalse(ComarkExclusions.matches("a.md", "# a.md\n\n   "))
        assertEquals(listOf("[oops"), ComarkExclusions.invalidPatterns("ok.md\n[oops\n# [comment"))
        assertTrue(ComarkExclusions.matches("ok.md", "[oops\nok.md"))
    }

    fun testExcludedMarkdownIsNotComarkButMdcStays() {
        ComarkSettings.getInstance().mode = ComarkSettings.Mode.ALWAYS
        ComarkSettings.getInstance().excludedPatterns = "notes/**\nCHANGELOG.md"
        assertInstanceOf(myFixture.addFileToProject("guide/page.md", "# Page\n"), ComarkFile::class.java)
        assertFalse(myFixture.addFileToProject("notes/page.md", "# Page\n") is ComarkFile)
        assertFalse(myFixture.addFileToProject("guide/CHANGELOG.md", "# Changes\n") is ComarkFile)
        assertInstanceOf(myFixture.addFileToProject("notes/page.mdc", "# Page\n"), ComarkFile::class.java)
    }

    fun testComarkMarkdownFilesGetTheComarkIcon() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        val page = myFixture.addFileToProject("site/content/page.md", "# Page\n").virtualFile
        val plain = myFixture.addFileToProject("other/readme.md", "# Readme\n").virtualFile
        fun iconOf(file: com.intellij.openapi.vfs.VirtualFile) =
            FileIconProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.getIcon(file, 0, project) }
        assertSame(ComarkIcons.FILE, iconOf(page))
        assertNotSame(ComarkIcons.FILE, iconOf(plain))
        ComarkSettings.getInstance().excludedPatterns = "content"
        assertNotSame(ComarkIcons.FILE, iconOf(page))
    }
}
