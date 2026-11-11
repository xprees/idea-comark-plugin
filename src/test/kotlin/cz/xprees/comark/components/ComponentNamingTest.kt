package cz.xprees.comark.components

import cz.xprees.comark.meta.ComponentNaming
import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentNamingTest {
    @Test
    fun splitByCaseFollowsScule() {
        assertEquals(listOf("foo", "Bar"), ComponentNaming.splitByCase("fooBar"))
        assertEquals(listOf("XML", "Http", "Request"), ComponentNaming.splitByCase("XMLHttpRequest"))
        assertEquals(listOf("prose", "alert"), ComponentNaming.splitByCase("prose-alert"))
        assertEquals(listOf("Prose", "H1"), ComponentNaming.splitByCase("ProseH1"))
        assertEquals(listOf("a", "b", "c"), ComponentNaming.splitByCase("a_b/c"))
    }

    @Test
    fun caseConversions() {
        assertEquals("ProseAlert", ComponentNaming.pascalCase("prose-alert"))
        assertEquals("ProseAlert", ComponentNaming.pascalCase("proseAlert"))
        assertEquals("ProseAlert", ComponentNaming.pascalCase("ProseAlert"))
        assertEquals("prose-alert", ComponentNaming.kebabCase("ProseAlert"))
        assertEquals("prose-h1", ComponentNaming.kebabCase("ProseH1"))
        assertEquals("iconName", ComponentNaming.camelCase("icon-name"))
        assertEquals("ui-button", ComponentNaming.kebabCase("UIButton"))
    }

    @Test
    fun lookupKeysUnifySpellings() {
        assertEquals(ComponentNaming.lookupKey("ProseAlert"), ComponentNaming.lookupKey("prose-alert"))
        assertEquals(ComponentNaming.lookupKey("ProseAlert"), ComponentNaming.lookupKey("proseAlert"))
        assertEquals(ComponentNaming.looseKey("UIButton"), ComponentNaming.looseKey("ui-button"))
    }

    @Test
    fun nuxtPathPrefixedNames() {
        assertEquals(listOf("Alert"), ComponentNaming.componentNames("Alert.vue"))
        assertEquals(listOf("BaseFooButton"), ComponentNaming.componentNames("base/foo/Button.vue"))
        assertEquals(listOf("ProseAlert"), ComponentNaming.componentNames("prose/ProseAlert.vue"))
        assertEquals(listOf("ProseAlert"), ComponentNaming.componentNames("prose/Alert.vue"))
        assertEquals(listOf("MyCard"), ComponentNaming.componentNames("my-card.vue"))
        assertEquals(listOf("Foo"), ComponentNaming.componentNames("foo/index.vue"))
        assertEquals(listOf("Item"), ComponentNaming.componentNames("Item/Item/Item.vue"))
    }

    @Test
    fun clientServerAndGlobalSuffixesAreDropped() {
        assertEquals(listOf("Comments"), ComponentNaming.componentNames("Comments.client.vue"))
        assertEquals(listOf("Stats"), ComponentNaming.componentNames("Stats.server.vue"))
        assertEquals(listOf("Banner"), ComponentNaming.componentNames("Banner.global.vue"))
    }

    @Test
    fun contentAndGlobalDirectoriesAreComponentRoots() {
        // components/content/prose/Alert.vue -> ProseAlert; Nuxt Content's runtime name (no path prefix) is an alias
        assertEquals(listOf("ProseAlert", "Alert"), ComponentNaming.componentNames("content/prose/Alert.vue"))
        assertEquals(listOf("Alert"), ComponentNaming.componentNames("content/Alert.vue"))
        assertEquals(listOf("Hero"), ComponentNaming.componentNames("global/Hero.vue"))
        assertEquals(listOf("LayoutHero"), ComponentNaming.componentNames("global/layout/Hero.vue"))
        assertEquals(listOf("Callout"), ComponentNaming.componentNames("content/callout/index.vue"))
    }
}
