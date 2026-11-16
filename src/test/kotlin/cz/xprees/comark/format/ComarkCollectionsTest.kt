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

    fun testFieldTypesAndDefaults() {
        val fields = ComarkCollections.parse(
            """
            export default defineContentConfig({ collections: { blog: defineCollection({
              type: 'page', source: '**',
              schema: z.object({
                author: z.string().optional(),
                order: z.number().default(0),
                draft: z.boolean().default(false),
                kind: z.enum(['a', 'b']).default('a'),
                published: z.string().datetime(),
                date: z.coerce.date(),
                tags: z.array(z.string()).default([]),
                image: property(z.object({ src: z.string().default('x') })).editor({ input: 'media' }),
                seo: z.object({}).default({ title: 'T', robots: [1, "two"] }),
                computed: z.string().default(() => 'x'),
                template: z.string().default(`a${'$'}{b}`),
              }),
            }) } })
            """.trimIndent(),
        ).single().schema
        assertEquals(
            listOf(
                StudioSchemaField("author", StudioFieldType.STRING),
                StudioSchemaField("order", StudioFieldType.NUMBER, true, 0.0),
                StudioSchemaField("draft", StudioFieldType.BOOLEAN, true, false),
                StudioSchemaField("kind", StudioFieldType.STRING, true, "a"),
                StudioSchemaField("published", StudioFieldType.DATETIME),
                StudioSchemaField("date", StudioFieldType.DATE),
                StudioSchemaField("tags", StudioFieldType.JSON, true, emptyList<Any?>()),
                // the nested default belongs to `src`, not to `image`
                StudioSchemaField("image", StudioFieldType.JSON),
                StudioSchemaField("seo", StudioFieldType.JSON, true, mapOf("title" to "T", "robots" to listOf(1.0, "two"))),
                // not plain literals: no default
                StudioSchemaField("computed", StudioFieldType.STRING),
                StudioSchemaField("template", StudioFieldType.STRING),
            ),
            fields,
        )
        assertTrue(ComarkCollections.parse("export default { c: defineCollection({ type: 'page', source: '**' }) }").single().schemaKnown)
        // a schema built from something the configuration doesn't define (an import) is not known
        val extended = ComarkCollections.parse(
            "export default defineContentConfig({ collections: { docs: defineCollection({ type: 'page', source: '**', schema: base.extend({ x: z.string() }) }) } })",
        ).single()
        assertFalse(extended.schemaKnown)
        assertFalse(ComarkCollections.parse("export default { c: defineCollection({ type: 'page', source: '**', schema: z.object({ a: z.string() }).merge(other) }) }").single().schemaKnown)
        val passthrough = ComarkCollections.parse("export default { c: defineCollection({ type: 'page', source: '**', schema: z.object({ a: z.string() }).passthrough() }) }").single()
        assertTrue(passthrough.schemaKnown)
        assertEquals(listOf("a"), passthrough.fields)
        assertFalse(ComarkCollections.parse("export default { c: defineCollection({ type: 'page', source: '**', schema: z.object({ ...shared }) }) }").single().schemaKnown)
        assertFalse(ComarkCollections.parse("export default { c: defineCollection({ type: 'page', source: '**', schema: z.object({ a: z.string() }).transform(f) }) }").single().schemaKnown)
    }

    fun testSchemasHeldInVariables() {
        val code = """
            import { z } from 'zod'
            const base = z.object({
                title: z.string(),
                icon: z.string().default('i-lucide-box'),
                hidden: z.boolean().default(false).describe('Hide'),
            })
            const extra = z.object({ order: z.number().default(0) })
            // with a type annotation, over several lines
            const page: any = base
                .passthrough()
                .extend({ icon: z.string().optional(), toc: z.boolean().default(false) })
            export default defineContentConfig({
                collections: {
                    plain: defineCollection({ type: 'page', source: 'a/**', schema: base }),
                    extended: defineCollection({ type: 'page', source: 'b/**', schema: page.extend({ sitemap: defineSitemapSchema({ z }) }) }),
                    merged: defineCollection({ type: 'page', source: 'c/**', schema: base.merge(extra).omit({ hidden: true }) }),
                    picked: defineCollection({ type: 'page', source: 'd/**', schema: base.pick({ title: true }).partial() }),
                    imported: defineCollection({ type: 'page', source: 'e/**', schema: importedSchema }),
                },
            })
        """.trimIndent()
        val parsed = ComarkCollections.parse(code).associateBy { it.name }
        assertEquals(listOf("title", "icon", "hidden"), parsed.getValue("plain").fields)
        assertTrue(parsed.getValue("plain").schemaKnown)
        // a replaced field keeps its place, new ones come last
        val extended = parsed.getValue("extended")
        assertEquals(listOf("title", "icon", "hidden", "toc", "sitemap"), extended.fields)
        assertEquals(StudioSchemaField("icon", StudioFieldType.STRING), extended.schema[1])
        assertEquals(StudioSchemaField("toc", StudioFieldType.BOOLEAN, true, false), extended.schema[3])
        assertEquals(StudioSchemaField("sitemap", StudioFieldType.JSON), extended.schema[4])
        assertEquals(listOf("title", "icon", "order"), parsed.getValue("merged").fields)
        assertEquals(listOf("title"), parsed.getValue("picked").fields)
        assertFalse(parsed.getValue("imported").schemaKnown)
    }

    fun testSourcePrefix() {
        val source = ComarkCollections.parse(
            "export default defineContentConfig({ collections: { t: defineCollection({ type: 'page', source: { include: 'threats/en/**/*.md', prefix: '/threats' } }), " +
                "d: defineCollection({ type: 'page', source: 'docs/**' }), f: defineCollection({ type: 'page', source: 'single.md' }) } })",
        ).associateBy { it.name }
        val threats = source.getValue("t").sources.single()
        assertEquals("threats/en/", threats.fixed)
        assertEquals("/threats", threats.routePrefix)
        // without a prefix, the static part of include
        assertEquals("/docs", source.getValue("d").sources.single().routePrefix)
        assertEquals("", source.getValue("f").sources.single().fixed)
        assertEquals("", source.getValue("f").sources.single().routePrefix)
    }

    fun testCollectionsDefinedInVariables() {
        val shorthand = ComarkCollections.parse(
            "const blog = defineCollection({ type: 'data', source: 'blog/**' })\nexport default defineContentConfig({ collections: { blog } })",
        )
        assertEquals(listOf("blog"), shorthand.map { it.name })
        assertEquals(StudioCollection.Type.DATA, shorthand.single().type)
        assertEquals("blog/**", shorthand.single().sources.single().include)
        val renamed = ComarkCollections.parse(
            "const docs = defineCollection({ type: 'page', source: 'docs/**' })\nconst unused = defineCollection({ type: 'data', source: 'x/**' })\n" +
                "export default defineContentConfig({ collections: { guide: docs, other: defineCollection({ type: 'data', source: 'o/**' }) } })",
        )
        assertEquals(listOf("other" to StudioCollection.Type.DATA, "guide" to StudioCollection.Type.PAGE), renamed.map { it.name to it.type })
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
        assertEquals(
            StudioCollection(
                "blog",
                StudioCollection.Type.PAGE,
                listOf(
                    StudioSchemaField("date", StudioFieldType.DATE),
                    StudioSchemaField("tags", StudioFieldType.JSON),
                    StudioSchemaField("cover-image", StudioFieldType.JSON),
                ),
            ),
            collections.collectionFor(post),
        )
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
