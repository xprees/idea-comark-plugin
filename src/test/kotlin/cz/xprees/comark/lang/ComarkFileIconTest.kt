package cz.xprees.comark.lang

import com.intellij.ide.FileIconProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.ComarkIcons

class ComarkFileIconTest : BasePlatformTestCase() {
    private fun iconOf(name: String) = myFixture.configureByText(name, "# Title\n").virtualFile.let { file ->
        FileIconProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.getIcon(file, 0, project) }
    }

    fun testMdcFilesGetTheComarkIcon() {
        assertSame(ComarkIcons.FILE, iconOf("page.mdc"))
        assertSame(ComarkIcons.FILE, iconOf("PAGE.MDC"))
    }

    /** Outside Nuxt Content / Comark packages (the default mode), `.md` files are plain Markdown. */
    fun testMarkdownFilesKeepTheirIcon() {
        assertNotSame(ComarkIcons.FILE, iconOf("readme.md"))
    }
}
