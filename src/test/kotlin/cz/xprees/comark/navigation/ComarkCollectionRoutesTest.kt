package cz.xprees.comark.navigation

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/** Routes of a Nuxt Content v3 package with several collections: `prefix`, `include`, `exclude` and front matter `path`. */
class ComarkCollectionRoutesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.16.0" } }""")
        myFixture.addFileToProject(
            "site/content.config.ts",
            """
            import { defineCollection, defineContentConfig } from '@nuxt/content'
            import { z } from 'zod'
            const pageSchema = z.object({ title: z.string().optional(), path: z.string().optional() })
            export default defineContentConfig({
                collections: {
                    pages_en: defineCollection({ type: 'page', source: { include: 'pages/en/**/*.md', prefix: '/' }, schema: pageSchema.passthrough() }),
                    pages_cs: defineCollection({ type: 'page', source: { include: 'pages/cs/**/*.md', prefix: '/cs' }, schema: pageSchema }),
                    threats_en: defineCollection({ type: 'page', source: { include: 'threats/en/**/*.md', prefix: '/threats', exclude: ['**/drafts/**'] } }),
                    docs: defineCollection({ type: 'page', source: 'docs/**' }),
                    authors: defineCollection({ type: 'data', source: 'authors/**/*.md' }),
                },
            })
            """.trimIndent(),
        )
        myFixture.addFileToProject("site/content/pages/en/about.md", "# About\n")
        myFixture.addFileToProject("site/content/pages/en/index.md", "# Home\n")
        myFixture.addFileToProject("site/content/pages/cs/about.md", "---\ntitle: O nás\npath: /cs/o-nas\n---\n\n# O nás\n")
        myFixture.addFileToProject("site/content/threats/en/01.phishing.md", "# Phishing\n")
        myFixture.addFileToProject("site/content/threats/en/drafts/wip.md", "# WIP\n")
        myFixture.addFileToProject("site/content/docs/1.guide/2.Getting Started.md", "# Start\n")
        myFixture.addFileToProject("site/content/authors/jane.md", "# Jane\n")
    }

    /** Content-relative path of the file [link] resolves to from a page of the package, or null. */
    private fun target(link: String): String? {
        val file = myFixture.addFileToProject("site/content/pages/en/source-${link.hashCode().toUInt()}.md", "[x]($link)\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val reference = PsiTreeUtil.findChildrenOfType(myFixture.file, MarkdownLinkDestination::class.java)
            .flatMap { it.references.filterIsInstance<ComarkRouteReference>() }.singleOrNull() ?: return "<no reference>"
        return (reference.resolve() as? PsiFile)?.virtualFile?.path?.substringAfter("/site/content/")
    }

    fun testCollectionPrefixes() {
        assertEquals("pages/en/about.md", target("/about"))
        assertEquals("pages/en/index.md", target("/"))
        assertEquals("threats/en/01.phishing.md", target("/threats/phishing"))
        // the directory path is not a route when the collection mounts it elsewhere
        assertNull(target("/pages/en/about"))
        assertNull(target("/threats/en/phishing"))
    }

    fun testDefaultPrefixIsTheStaticPartOfInclude() {
        assertEquals("docs/1.guide/2.Getting Started.md", target("/docs/guide/getting-started"))
    }

    fun testFrontMatterPathOverridesTheRoute() {
        assertEquals("pages/cs/about.md", target("/cs/o-nas"))
        assertNull(target("/cs/about"))
    }

    fun testExcludedFilesAndDataCollectionsHaveNoRoutes() {
        assertNull(target("/threats/drafts/wip"))
        assertNull(target("/authors/jane"))
    }

    fun testCollectionRoute() {
        assertEquals("/threats/phishing", ComarkContentRoutes.collectionRoute("/threats", "01.phishing.md"))
        assertEquals("/", ComarkContentRoutes.collectionRoute("/", "index.md"))
        assertEquals("/cs/guide", ComarkContentRoutes.collectionRoute("/cs", "1.guide/index.draft.md"))
        assertEquals("/v1.2/notes", ComarkContentRoutes.collectionRoute("", "v1.2/notes.md"))
    }
}
