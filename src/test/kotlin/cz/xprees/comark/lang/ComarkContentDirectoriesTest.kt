package cz.xprees.comark.lang

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.ComarkIcons

/** In the default mode only Markdown files in content directories of Nuxt Content / Comark packages are Comark. */
class ComarkContentDirectoriesTest : BasePlatformTestCase() {
    private fun md(path: String): VirtualFile = myFixture.addFileToProject(path, "# Title\n").virtualFile

    private fun isComark(file: VirtualFile) = ComarkLanguageSubstitutor.isComarkFile(file, project)

    private fun iconOf(file: VirtualFile) = FileIconProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.getIcon(file, 0, project) }

    private fun nuxtContentPackage(directory: String) =
        myFixture.addFileToProject("$directory/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")

    fun testOnlyFilesInTheContentDirectoryAreComark() {
        nuxtContentPackage("site")
        val page = md("site/content/1.guide/install.md")
        val readme = md("site/README.md")
        val docs = md("site/docs/notes.md")
        assertTrue(isComark(page))
        assertFalse(isComark(readme))
        assertFalse(isComark(docs))
        assertSame(ComarkIcons.FILE, iconOf(page))
        assertNotSame(ComarkIcons.FILE, iconOf(readme))
        assertTrue(isComark(myFixture.addFileToProject("site/README.mdc", "# Title\n").virtualFile))
    }

    fun testLayersAndNestedAppsWithContentDirectories() {
        nuxtContentPackage("repo")
        assertTrue(isComark(md("repo/layers/base/content/index.md")))
        assertTrue(isComark(md("repo/docs/content/index.md")))
        assertFalse(isComark(md("repo/node_modules/theme/content/index.md")))
        assertFalse(isComark(md("repo/CHANGELOG.md")))
    }

    fun testNoContentOutsideComarkPackages() {
        myFixture.addFileToProject("app/package.json", """{ "dependencies": { "vue": "^3.0.0" } }""")
        assertFalse(isComark(md("app/content/index.md")))
    }

    fun testCollectionSourceDirectoriesFromTheContentConfig() {
        nuxtContentPackage("site")
        myFixture.addFileToProject(
            "site/content.config.ts",
            """
            import { defineCollection, defineContentConfig } from '@nuxt/content'
            import { resolve } from 'node:path'
            export default defineContentConfig({
              collections: {
                docs: defineCollection({ type: 'page', source: { include: '**', cwd: resolve(__dirname, 'documentation') } }),
                blog: defineCollection({ type: 'page', source: { include: '**/*.md', cwd: '../shared/posts', prefix: '/blog' } }),
                remote: defineCollection({ type: 'page', source: { repository: 'https://github.com/nuxt/content', include: 'docs/**' } }),
              },
            })
            """.trimIndent(),
        )
        assertTrue(isComark(md("site/documentation/intro.md")))
        assertFalse(isComark(md("shared/posts/hello.md")))
        assertFalse(isComark(md("site/README.md")))
    }

    fun testContentConfigOfAnOuterPackageReachesSiblingDirectories() {
        nuxtContentPackage("repo")
        myFixture.addFileToProject("repo/content.config.ts", "source: { include: '**', cwd: 'packages/docs' }")
        assertTrue(isComark(md("repo/packages/docs/guide.md")))
        assertFalse(isComark(md("repo/packages/core/README.md")))
    }

    fun testNuxtContent2FileSystemSources() {
        nuxtContentPackage("site")
        myFixture.addFileToProject(
            "site/nuxt.config.ts",
            """
            export default defineNuxtConfig({
              app: { baseURL: '/x/' },
              content: { sources: { docs: { driver: 'fs', prefix: '/docs', base: resolve(__dirname, 'docs') } } },
            })
            """.trimIndent(),
        )
        assertTrue(isComark(md("site/docs/intro.md")))
        assertFalse(isComark(md("site/README.md")))
    }

    private fun withContentConfig(path: String, test: () -> Unit) {
        val settings = cz.xprees.comark.settings.ComarkSettings.getInstance()
        settings.contentConfigPath = path
        ComarkProjectDetector.getInstance(project).invalidate()
        try {
            test()
        } finally {
            settings.contentConfigPath = ""
            ComarkProjectDetector.getInstance(project).invalidate()
        }
    }

    /** A configuration set in the settings replaces the discovered ones and needs no package.json. */
    fun testContentConfigFromTheSettings() {
        nuxtContentPackage("site")
        myFixture.addFileToProject("site/content.config.ts", "source: { include: '**', cwd: 'discovered' }")
        myFixture.addFileToProject("apps/docs/config/content.docs.ts", "source: { include: '**', cwd: '../pages' }")
        val page = md("apps/docs/pages/intro.md")
        val nextToConfig = md("apps/docs/config/content/index.md")
        val discovered = md("site/discovered/a.md")
        val content = md("site/content/index.md")
        val readme = md("apps/docs/README.md")
        withContentConfig("apps/docs/config/content.docs.ts") {
            assertTrue(isComark(page))
            assertTrue(isComark(nextToConfig))
            assertFalse(isComark(discovered))
            assertTrue(isComark(content))
            assertFalse(isComark(readme))
            assertSame(ComarkIcons.FILE, iconOf(page))
        }
        withContentConfig("missing/content.config.ts") {
            assertFalse(isComark(page))
            assertTrue(isComark(content))
        }
        assertFalse(isComark(page))
        assertTrue(isComark(discovered))
    }

    fun testConfigurationParsing() {
        assertEquals(
            listOf("documentation", "../posts", "./x"),
            ComarkProjectDetector.sourceDirectories(
                "a: { cwd: resolve(__dirname, 'documentation') }, b: { cwd: \"../posts\" }, c: { cwd: fileURLToPath(new URL('./x', import.meta.url)) }",
                nuxtConfig = false,
            ),
        )
        assertEquals(listOf("docs"), ComarkProjectDetector.sourceDirectories("{ driver: 'fs', base: 'docs' } { driver: 'github', base: 'x' }", nuxtConfig = true))
    }
}
