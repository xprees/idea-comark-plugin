package cz.xprees.comark.components

import cz.xprees.comark.meta.ComarkProp
import cz.xprees.comark.meta.ComponentMetaParser
import cz.xprees.comark.meta.PropKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComponentMetaParserTest {
    private val fixture = File("src/test/testData/components/component-meta.mjs").readText()

    @Test
    fun parsesNuxtComponentMetaModule() {
        val records = ComponentMetaParser.parse(fixture)!!
        assertEquals(listOf("Alert", "ProseA"), records.map { it.name })

        val alert = records.first { it.name == "Alert" }
        assertEquals("components/content/Alert.vue", alert.filePath)
        assertEquals("/does/not/exist/components/content/Alert.vue", alert.fullPath)
        assertEquals(listOf("type", "title", "dismissible"), alert.props.map { it.name })
        val type = alert.props.first { it.name == "type" }
        assertEquals("Visual style of the alert", type.description)
        assertEquals("'info'", type.defaultValue)
        assertEquals("\"info\" | \"warning\" | \"danger\"", type.displayType)
        assertEquals(PropKind.STRING, type.kind)
        assertTrue(alert.props.first { it.name == "title" }.required)
        assertEquals(PropKind.BOOLEAN, alert.props.first { it.name == "dismissible" }.kind)
        assertEquals("false", alert.props.first { it.name == "dismissible" }.defaultValue)
        assertEquals(listOf("default", "icon"), alert.slots.map { it.name })
        assertEquals("Alert body", alert.slots.first().description)
    }

    @Test
    fun globalPropsAreSkipped() {
        val proseA = ComponentMetaParser.parse(fixture)!!.first { it.name == "ProseA" }
        assertEquals(listOf("href", "target"), proseA.props.map { it.name })
        // the `(string & object)` idiom is still a string
        assertEquals(PropKind.STRING, proseA.props.first { it.name == "target" }.kind)
    }

    /** Output of nuxt-component-meta 0.19.0's `updateOutput` (optimiseJSON / deduplicateJSON), generated with the real code. */
    @Test
    fun parsesRealNuxtComponentMetaOutput() {
        val module = File("src/test/testData/components/component-meta-deduplicated.mjs").readText()
        val records = ComponentMetaParser.parse(module)
        assertNotNull("the deduplicated .mjs nuxt-component-meta 0.19 writes is not understood", records)
        assertEquals(listOf("Alert", "Badge", "Card"), records!!.map { it.name })
        val alert = records.first { it.name == "Alert" }
        assertEquals(listOf("title", "open"), alert.props.map { it.name })
        assertEquals("Heading", alert.props.first().description)
        assertEquals("boolean | undefined", alert.props.last().type)
        assertEquals(listOf("default"), alert.slots.map { it.name })
        assertEquals("components/content/Alert.vue", alert.filePath)
    }

    @Test
    fun modulesWithCommentsEscapesAndSeveralDeclarations() {
        val module = """
            /* header */
            const _s = ['it\'s', "tab\tand A"], _ref0 = {"name": _s[0], required: true, default: -1.5};
            let other = [ _ref0, ]; // trailing comma
            export default { "My-Card": { pascalName: "MyCard", meta: { props: other, slots: [], events: [{ huge: [1, 2, { x: _s[1] }] }] } } }
        """.trimIndent()
        val record = ComponentMetaParser.parse(module)!!.single()
        assertEquals("MyCard", record.name)
        val prop = record.props.single()
        assertEquals("it's", prop.name)
        assertTrue(prop.required)
        assertEquals("-1.5", prop.defaultValue)
    }

    @Test
    fun truncatedModulesAreRejectedWithoutErrors() {
        val module = File("src/test/testData/components/component-meta-deduplicated.mjs").readText()
        for (length in module.indices) ComponentMetaParser.parse(module.substring(0, length))
        for (length in fixture.indices step 7) ComponentMetaParser.parse(fixture.substring(0, length))
    }

    @Test
    fun parsesPlainJson() {
        val json = """{ "MyCard": { "pascalName": "MyCard", "meta": { "props": [ { "name": "title", "type": "string", "required": true } ], "slots": [] } } }"""
        val record = ComponentMetaParser.parse(json)!!.single()
        assertEquals("MyCard", record.name)
        assertEquals("title", record.props.single().name)
    }

    @Test
    fun parsesVsCodeCustomFormat() {
        val json = """
            [
              {
                "mdc_name": "page-hero",
                "description": "A hero section",
                "documentation_markdown": "## Page hero\nUse at the top.",
                "docs_url": "https://example.com/hero",
                "component_meta": {
                  "meta": {
                    "props": [ { "name": "headline", "type": "string", "description": "Big text" } ],
                    "slots": [ { "name": "title" } ]
                  }
                }
              }
            ]
        """.trimIndent()
        val record = ComponentMetaParser.parse(json)!!.single()
        assertEquals("PageHero", record.name)
        assertEquals("page-hero", record.kebabName)
        assertEquals("A hero section", record.description)
        assertEquals("## Page hero\nUse at the top.", record.documentation)
        assertEquals("https://example.com/hero", record.docsUrl)
        assertEquals("Big text", record.props.single().description)
        assertEquals("title", record.slots.single().name)
    }

    @Test
    fun keyIsUsedWhenNamesAreMissing() {
        val record = ComponentMetaParser.parse("""export default { "BlogPost": { "meta": { "props": [] } } }""")!!.single()
        assertEquals("BlogPost", record.name)
    }

    @Test
    fun rejectsUnsupportedContent() {
        assertNull(ComponentMetaParser.parse("console.log('hi')"))
        assertNull(ComponentMetaParser.parse(""))
        assertNull(ComponentMetaParser.parse("export default {"))
        assertNotNull(ComponentMetaParser.parse("export default {}"))
    }

    @Test
    fun literalUnionValues() {
        assertEquals(listOf("info", "warning"), ComarkProp("type", "'info' | \"warning\" | undefined").literalValues)
        assertEquals(listOf("sm", "md"), ComarkProp("size", "'sm' | 'md' | (string & {})").literalValues)
        assertEquals(emptyList<String>(), ComarkProp("title", "string").literalValues)
        assertEquals(emptyList<String>(), ComarkProp("untyped").literalValues)
    }

    @Test
    fun cleansUndefinedFromTypes() {
        assertEquals("string", ComarkProp.cleanType("string | undefined"))
        assertEquals("number", ComarkProp.cleanType("undefined | number"))
        assertEquals("'a' | 'b'", ComarkProp.cleanType("'a' | 'b' | null | undefined"))
        assertFalse(PropKind.of("string") == PropKind.OBJECT)
    }
}
