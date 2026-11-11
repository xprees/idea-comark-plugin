package cz.xprees.comark.components

import cz.xprees.comark.meta.ComponentUsage
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.meta.InlineComponentSyntax
import cz.xprees.comark.meta.PropsYaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentUsagesTest {
    private fun names(text: String) = InlineComponentSyntax.findAll(text, 0, text.length).map { it.name(text) }

    @Test
    fun inlineComponentsFollowThePreviousCharacterRule() {
        assertEquals(listOf("badge"), names(":badge"))
        assertEquals(listOf("icon-star", "badge"), names("Check :icon-star and :badge[New]{color=\"blue\"}."))
        assertEquals(listOf("a", "b"), names("**:a** *:b*"))
        // `_` is a name character, as in comark
        assertEquals(listOf("b_"), names("_:b_"))
        assertEquals(emptyList<String>(), names("https://example.com 10:30 Note: x a:b"))
        assertEquals(emptyList<String>(), names(":8100 ::block"))
    }

    @Test
    fun nestedInlineComponentsInContentAreFound() {
        assertEquals(listOf("outer", "inner"), names(":outer[with :inner inside]"))
    }

    @Test
    fun attributeValuesDoNotContainComponents() {
        assertEquals(listOf("badge"), names(":badge{label=\" :fake\" :bound=\"x\"}"))
    }

    @Test
    fun inlineComponentParts() {
        val text = "x :badge[New]{color=\"blue\"} y"
        val component = InlineComponentSyntax.findAll(text, 0, text.length).single()
        assertEquals(text.indexOf("badge"), component.nameStart)
        assertEquals(text.indexOf('['), component.contentOpen)
        assertEquals(text.indexOf(']'), component.contentClose)
        assertTrue(component.attributes!!.closed)
    }

    @Test
    fun inlineUsageAtOffset() {
        val text = "See :badge[New]{color=\"red\" :size=\"2\"} now"
        val name = ComponentUsages.inlineUsage(text, text.indexOf("adge")) as ComponentUsage.Name
        assertEquals("badge", name.componentName)
        val prop = ComponentUsages.inlineUsage(text, text.indexOf("olor")) as ComponentUsage.Prop
        assertEquals("color", prop.propName)
        val binding = ComponentUsages.inlineUsage(text, text.indexOf("ize")) as ComponentUsage.Prop
        assertEquals("size", binding.propName)
        assertNull(ComponentUsages.inlineUsage(text, text.indexOf("red")))
        assertNull(ComponentUsages.inlineUsage(text, text.indexOf("now")))
    }

    @Test
    fun propsYamlKeys() {
        val yaml = "title: Hello\nconfig:\n  nested: 1\n'quoted-key': x\nitems:\n  - a\n# comment: no\n"
        assertEquals(listOf("title", "config", "quoted-key", "items"), PropsYaml.keys(yaml).map { it.name })
        assertEquals("title", PropsYaml.keyAt(yaml, 2)!!.name)
        assertEquals("title", PropsYaml.keyAt(yaml, 5)!!.name)
        assertNull(PropsYaml.keyAt(yaml, yaml.indexOf("Hello")))
        assertNull(PropsYaml.keyAt(yaml, yaml.indexOf("nested")))
        assertEquals("quoted-key", PropsYaml.keyAt(yaml, yaml.indexOf("quoted"))!!.name)
    }

    @Test
    fun indentedPropsYaml() {
        val yaml = "  title: Hello\n  nested:\n    deep: 1\n"
        assertEquals(listOf("title", "nested"), PropsYaml.keys(yaml).map { it.name })
        assertEquals(2, PropsYaml.baseIndent(yaml))
    }
}
