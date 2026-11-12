package cz.xprees.comark.format

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [ComarkCollections]: the collection of a content file, from `content.config.ts`. */
class ComarkCollectionsTest : BasePlatformTestCase() {
    private val config = """
        import { defineCollection, defineContentConfig, z } from '@nuxt/content'
        // a comment with defineCollection({ type: 'data' })
        export default defineContentConfig({
          collections: {
            authors: defineCollection({
              type: 'data',
              source: 'authors/**.yml',
              schema: z.object({ name: z.string(), avatar: z.string() }),
            }),
            blog: defineCollection({
              type: 'page',
              source: { include: 'blog/**/*.md', exclude: ['blog/drafts/**'] },
              schema: z.object({
                date: z.date(),
                tags: z.array(z.string()),
                'cover-image': z.object({ src: z.string(), alt: z.string() }),
              }),
            }),
            docs: defineCollection({
              type: 'page',
              source: [{ include: '**', cwd: 'docs' }],
            }),
            content: defineCollection({ type: 'page', source: '**/*.md' }),
          },
        })
    """.trimIndent()

    fun testParse() {
        val collections = ComarkCollections.parse(config)
        assertEquals(listOf("authors", "blog", "docs", "content"), collections.map { it.name })
        assertEquals(StudioCollection.Type.DATA, collections[0].type)
        assertEquals(listOf("name", "avatar"), collections[0].fields)
        assertEquals(listOf("date", "tags", "cover-image"), collections[1].fields)
        assertEquals("blog/**/*.md", collections[1].sources.single().include)
        assertEquals(listOf("blog/drafts/**"), collections[1].sources.single().exclude)
        assertEquals("docs", collections[2].sources.single().cwd)
        assertEquals(emptyList<String>(), collections[3].fields)
    }

    fun testGlobs() {
        assertTrue(ComarkCollections.globRegex("**/*.md").matches("index.md"))
        assertTrue(ComarkCollections.globRegex("**/*.md").matches("a/b/c.md"))
        assertFalse(ComarkCollections.globRegex("*.md").matches("a/b.md"))
        assertTrue(ComarkCollections.globRegex("blog/*.{md,mdc}").matches("blog/x.mdc"))
        assertTrue(ComarkCollections.globRegex("**").matches("x/y.md"))
    }

    fun testCollectionOfAFile() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/content.config.ts", config)
        val post = myFixture.addFileToProject("site/content/blog/2026/hello.md", "# Hello\n")
        val draft = myFixture.addFileToProject("site/content/blog/drafts/wip.md", "# WIP\n")
        val doc = myFixture.addFileToProject("site/docs/guide.md", "# Guide\n")
        val collections = ComarkCollections.getInstance(project)
        assertEquals(StudioCollection("blog", StudioCollection.Type.PAGE, listOf("date", "tags", "cover-image")), collections.collectionFor(post))
        // excluded from blog, so the catch-all collection
        assertEquals("content", collections.collectionFor(draft)?.name)
        assertEquals("docs", collections.collectionFor(doc)?.name)
        // the context handed to the adapter
        assertEquals("blog", ComarkStudioFormatting.context(post, ComarkStyle.VSCODE).collection?.name)
    }

    fun testNoConfiguration() {
        val file = myFixture.addFileToProject("other/content/page.md", "# Page\n")
        assertNull(ComarkCollections.getInstance(project).collectionFor(file))
    }
}
