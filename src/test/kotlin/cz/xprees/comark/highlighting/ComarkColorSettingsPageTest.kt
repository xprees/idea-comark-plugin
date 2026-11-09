package cz.xprees.comark.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComarkColorSettingsPageTest : BasePlatformTestCase() {
    private val allKeys: Set<TextAttributesKey> = ComarkHighlighterColors::class.java.declaredFields
        .filter { it.type == TextAttributesKey::class.java }
        .map { it.get(null) as TextAttributesKey }
        .toSet()

    fun testEveryKeyHasADescriptorAndAFallback() {
        val page = ComarkColorSettingsPage()
        val described = page.attributeDescriptors.map { it.key }.toSet()
        assertEquals(allKeys, described)
        for (descriptor in page.attributeDescriptors) {
            assertFalse(descriptor.displayName.isBlank())
            assertNotNull("${descriptor.key} needs a fallback", descriptor.key.fallbackAttributeKey)
        }
    }

    fun testDemoTagsAreKnownAndCoverEveryKey() {
        val page = ComarkColorSettingsPage()
        val tags = page.additionalHighlightingTagToDescriptorMap
        val used = Regex("<(\\w+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        assertEquals("unknown tags", emptySet<String>(), used - tags.keys)
        assertEquals("keys without a tag in the demo", emptySet<TextAttributesKey>(), allKeys - used.map { tags.getValue(it) }.toSet())
    }

    fun testDemoIsLexedWithoutGaps() {
        val page = ComarkColorSettingsPage()
        val plain = page.demoText.replace(Regex("</?\\w+>"), "")
        val lexer = page.highlighter.highlightingLexer
        lexer.start(plain)
        var offset = 0
        while (lexer.tokenType != null) {
            assertEquals(offset, lexer.tokenStart)
            offset = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(plain.length, offset)
    }
}
