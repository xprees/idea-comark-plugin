package cz.xprees.comark.components

import cz.xprees.comark.meta.PropKind
import cz.xprees.comark.meta.SfcInfo
import cz.xprees.comark.meta.TypeModule
import cz.xprees.comark.meta.TypeModuleResolver
import cz.xprees.comark.meta.VueSfcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VueSfcParserTest {
    private fun parse(text: String): SfcInfo = VueSfcParser.parse(text.trimIndent())

    private fun SfcInfo.prop(name: String) = findProp(name) ?: throw AssertionError("no prop $name in ${props.map { it.name }}")

    private companion object {
        val FUZZ_TYPES = """
            import type { Base } from './base'
            export * from './more'
            export { Local as Exported } from './local'
            /** Shared props. */
            export interface Shared extends Base, Pick<Other, 'a' | "b"> {
              /** @default 'x' */
              shared?: 'x' | 'y'
              ['computed']: string
            }
            export type Mode = `a-${'$'}{string}` | 'b'
            type Local = Omit<Shared, 'shared'> & { local: Mode }
            export { type Local as Alias }
        """.trimIndent()

        val FUZZ_COMPONENTS = listOf(
            """
            <!-- A component with everything. -->
            <script setup lang="ts" generic="T extends string">
            import type { Shared, Mode } from './types'
            import { computed, useSlots } from 'vue'
            /** Highlights important information. */
            type Variant = 'solid' | 'outline' | "ghost"
            interface Props extends Shared {
              /** Visual style */
              variant?: Variant
              title: string // the title
              items?: Array<{ id: number; label: string }>
              onClose?: (reason: 'x' | 'y') => void
              'aria-label'?: string
              mode: Mode
            }
            const { variant = 'solid', title: heading = "Hi", ...rest } = withDefaults(defineProps<Partial<Omit<Props, 'mode'>> & Pick<Props, "mode">>(), {
              variant: 'solid',
              items: () => [],
              size: () => ({ a: 1 }),
            })
            const model = defineModel<string>('value', { required: true, default: 'x' })
            const open = defineModel({ type: Boolean })
            const slots = defineSlots<{ default(props: { item: T }): any; footer?: () => any }>()
            const has = computed(() => !!slots.header || ${'$'}slots['side-bar'])
            const re = /[<'"`]/g
            const s = `template ${'$'}{'literal'} with <slot>`
            </script>
            <template>
              <div :class="['a', { b: true }]" @click="emit('x')">
                <!-- @slot The heading -->
                <slot name="title" :heading="heading" />
                <template v-if="${'$'}slots.footer"><slot name="footer"></slot></template>
                <slot :name="dynamic" />
                <slot />
              </div>
            </template>
            <docs>
            Renders **things**.
            </docs>
            """.trimIndent(),
            """
            <script lang="ts">
            /** Options API component. */
            import { defineComponent, type PropType, type SlotsType } from 'vue'
            const shared = { tone: { type: String, default: 'info' } }
            export default defineComponent({
              name: 'Legacy',
              props: {
                /** The size */
                size: { type: [String, Number] as PropType<'sm' | 'md'>, required: true, default: () => 'sm' },
                items: Array as PropType<string[]>,
                'data-x': String,
                flag: Boolean,
              },
              slots: Object as SlotsType<{ default: { item: string }; empty: {} }>,
            })
            </script>
            <script setup>
            defineProps(['a', "b", `c`])
            defineProps(shared)
            </script>
            <template><slot name='x' /><slot/></template>
            """.trimIndent(),
            """
            <template>
              <section>
                <template #header><slot name="header" /></template>
              </section>
            </template>
            <script setup lang="ts">
            export interface Props { a?: string; b: number }
            const props = defineProps<Props>()
            const { a = 'x' } = defineProps<{ a?: string }>()
            defineModel('count')
            defineModel("name", { default: "n" })
            </script>
            """.trimIndent(),
        )
    }

    @Test
    fun typeLiteralProps() {
        val info = parse(
            """
            <script setup lang="ts">
            const props = defineProps<{
              /** Alert title */
              title: string
              type?: 'info' | 'warning' | 'danger'
              count?: number;
              closable?: boolean,
              items?: string[]
              'aria-label'?: string
              onClose?: () => void
            }>()
            </script>
            <template><div>{{ title }}</div></template>
            """,
        )
        assertEquals(listOf("title", "type", "count", "closable", "items", "aria-label", "onClose"), info.props.map { it.name })
        val title = info.prop("title")
        assertTrue(title.required)
        assertEquals("string", title.type)
        assertEquals("Alert title", title.description)
        assertEquals("'info' | 'warning' | 'danger'", info.prop("type").type)
        assertFalse(info.prop("type").required)
        assertEquals(PropKind.NUMBER, info.prop("count").kind)
        assertEquals(PropKind.BOOLEAN, info.prop("closable").kind)
        assertEquals(PropKind.ARRAY, info.prop("items").kind)
        assertEquals(PropKind.FUNCTION, info.prop("onClose").kind)
    }

    @Test
    fun propOffsetsPointAtNames() {
        val text = "<script setup lang=\"ts\">\ndefineProps<{ title: string; 'icon-name'?: string }>()\n</script>"
        val info = VueSfcParser.parse(text)
        assertEquals(text.indexOf("title"), info.prop("title").offset)
        assertEquals(text.indexOf("icon-name"), info.prop("icon-name").offset)
    }

    @Test
    fun multiLineUnionTypes() {
        val info = parse(
            """
            <script setup lang="ts">
            defineProps<{
              variant?:
                | 'solid'
                | 'outline'
              size?: 'sm' | 'md'
                | 'lg'
              color: string
            }>()
            </script>
            """,
        )
        assertEquals(listOf("variant", "size", "color"), info.props.map { it.name })
        assertEquals("'solid' | 'outline'", info.prop("variant").type)
        assertEquals("'sm' | 'md' | 'lg'", info.prop("size").type)
    }

    @Test
    fun interfaceAndTypeAliasProps() {
        val info = parse(
            """
            <script setup lang="ts">
            interface Base {
              /** Shared id */
              id?: string
            }
            export interface Props extends Base {
              title: string
              /**
               * Visual style.
               * @default 'info'
               */
              type?: string
            }
            const props = withDefaults(defineProps<Props>(), {
              type: 'info',
              id: () => 'generated',
            })
            </script>
            """,
        )
        assertEquals(setOf("title", "type", "id"), info.props.map { it.name }.toSet())
        assertEquals("'info'", info.prop("type").defaultValue)
        assertEquals("Visual style.", info.prop("type").description)
        assertEquals("'generated'", info.prop("id").defaultValue)
        assertEquals("Shared id", info.prop("id").description)
    }

    @Test
    fun typeAliasIntersectionAndUtilityTypes() {
        val info = parse(
            """
            <script setup lang="ts">
            type Common = { size?: number; hidden: boolean }
            type Props = Omit<Common, 'hidden'> & Partial<{ label: string }> & {
              icon: string
            }
            defineProps<Props>()
            </script>
            """,
        )
        assertEquals(setOf("size", "label", "icon"), info.props.map { it.name }.toSet())
        assertFalse(info.prop("label").required)
        assertTrue(info.prop("icon").required)
    }

    @Test
    fun reactiveDestructureDefaults() {
        val info = parse(
            """
            <script setup lang="ts">
            const { size = 'md', open: isOpen = false, label } = defineProps<{ size?: string; open?: boolean; label: string }>()
            </script>
            """,
        )
        assertEquals("'md'", info.prop("size").defaultValue)
        assertEquals("false", info.prop("open").defaultValue)
        assertNull(info.prop("label").defaultValue)
    }

    @Test
    fun runtimeObjectProps() {
        val info = parse(
            """
            <script setup>
            const props = defineProps({
              // The heading
              title: String,
              level: { type: Number, default: 2 },
              tags: { type: Array, default: () => [] },
              config: { type: Object, default: () => ({ dense: true }) },
              mode: { type: String as PropType<'light' | 'dark'>, required: true },
              value: [String, Number],
              anything: null,
            })
            </script>
            """,
        )
        assertEquals(listOf("title", "level", "tags", "config", "mode", "value", "anything"), info.props.map { it.name })
        assertEquals("string", info.prop("title").type)
        assertEquals("The heading", info.prop("title").description)
        assertEquals("number", info.prop("level").type)
        assertEquals("2", info.prop("level").defaultValue)
        assertEquals("[]", info.prop("tags").defaultValue)
        assertEquals(PropKind.ARRAY, info.prop("tags").kind)
        assertEquals("{ dense: true }", info.prop("config").defaultValue)
        assertEquals(PropKind.OBJECT, info.prop("config").kind)
        assertEquals("'light' | 'dark'", info.prop("mode").type)
        assertTrue(info.prop("mode").required)
        assertEquals("string | number", info.prop("value").type)
        assertFalse(info.prop("title").required)
    }

    @Test
    fun runtimeArrayProps() {
        val info = parse("<script setup>\ndefineProps(['title', \"subtitle\"])\n</script>")
        assertEquals(listOf("title", "subtitle"), info.props.map { it.name })
    }

    @Test
    fun runtimePropsFromConstant() {
        val info = parse(
            """
            <script setup>
            const propsDefinition = { heading: { type: String, required: true } }
            defineProps(propsDefinition)
            </script>
            """,
        )
        assertTrue(info.prop("heading").required)
    }

    @Test
    fun optionsApiProps() {
        val info = parse(
            """
            <script>
            /** A card with a title. */
            export default {
              name: 'MyCard',
              props: {
                title: { type: String, required: true },
                elevated: Boolean,
              },
            }
            </script>
            """,
        )
        assertEquals(listOf("title", "elevated"), info.props.map { it.name })
        assertTrue(info.prop("title").required)
        assertEquals("boolean", info.prop("elevated").type)
        assertEquals("A card with a title.", info.description)
    }

    @Test
    fun defineComponentProps() {
        val info = parse(
            """
            <script lang="ts">
            import { defineComponent } from 'vue'
            export default defineComponent({
              props: ['to', 'external'],
              setup() { return {} },
            })
            </script>
            """,
        )
        assertEquals(listOf("to", "external"), info.props.map { it.name })
    }

    @Test
    fun defineModel() {
        val info = parse(
            """
            <script setup lang="ts">
            const model = defineModel<string>()
            const count = defineModel<number>('count', { required: true, default: 0 })
            </script>
            """,
        )
        assertEquals("string", info.prop("modelValue").type)
        assertTrue(info.prop("count").required)
        assertEquals("0", info.prop("count").defaultValue)
    }

    @Test
    fun scriptAndScriptSetupTogether() {
        val info = parse(
            """
            <script lang="ts">
            export interface ButtonProps { label: string; to?: string }
            </script>
            <script setup lang="ts">
            defineProps<ButtonProps>()
            </script>
            """,
        )
        assertEquals(listOf("label", "to"), info.props.map { it.name })
    }

    @Test
    fun commentsAndStringsDoNotConfuseTheParser() {
        val info = parse(
            """
            <script setup lang="ts">
            // defineProps<{ fake: string }>()
            const text = "defineProps<{ alsoFake: string }>()"
            /* defineProps({ nope: String }) */
            defineProps<{ real: string }>()
            </script>
            """,
        )
        assertEquals(listOf("real"), info.props.map { it.name })
    }

    @Test
    fun templateSlots() {
        val text = """
            <template>
              <div>
                <header><slot name="header" /></header>
                <!-- The card body -->
                <slot />
                <template v-if="x"><slot name='footer'>Default footer</slot></template>
                <slot :name="dynamic" />
                <!-- <slot name="commented" /> -->
              </div>
            </template>
            """.trimIndent()
        val info = VueSfcParser.parse(text)
        assertEquals(listOf("header", "default", "footer"), info.slots.map { it.name })
        assertEquals("The card body", info.findSlot("default")!!.description)
        assertEquals(text.indexOf("header\""), info.findSlot("header")!!.offset)
        assertEquals(text.indexOf("<slot />") + 1, info.findSlot("default")!!.offset)
    }

    @Test
    fun nestedTemplatesDoNotEndTheTemplateEarly() {
        val info = parse(
            """
            <template>
              <List>
                <template #item="{ item }"><span>{{ item }}</span></template>
              </List>
              <slot name="after" />
            </template>
            """,
        )
        assertEquals(listOf("after"), info.slots.map { it.name })
    }

    @Test
    fun defineSlotsAndSlotReferences() {
        val info = parse(
            """
            <script setup lang="ts">
            defineSlots<{
              /** Main content */
              default(props: { item: string }): any
              title?: (props: {}) => any
            }>()
            const slots = useSlots()
            const hasIcon = computed(() => !!slots.icon)
            </script>
            <template><div v-if="${'$'}slots.footer"><slot name="footer" /></div></template>
            """,
        )
        assertEquals(setOf("default", "title", "footer", "icon"), info.slots.map { it.name }.toSet())
        assertEquals("Main content", info.findSlot("default")!!.description)
    }

    @Test
    fun slotsTypeInOptionsApi() {
        val info = parse(
            """
            <script lang="ts">
            export default defineComponent({
              slots: Object as SlotsType<{ default: {}; actions: { close: () => void } }>,
            })
            </script>
            """,
        )
        assertEquals(listOf("default", "actions"), info.slots.map { it.name })
    }

    @Test
    fun descriptionFromLeadingHtmlComment() {
        val info = parse(
            """
            <!-- Shows a dismissible banner. -->
            <template><div /></template>
            """,
        )
        assertEquals("Shows a dismissible banner.", info.description)
    }

    @Test
    fun descriptionFromLeadingScriptJsDoc() {
        val info = parse(
            """
            <script setup lang="ts">
            /**
             * Highlights an important note.
             * @displayName Callout
             */
            defineProps<{ icon?: string }>()
            </script>
            """,
        )
        assertEquals("Highlights an important note.", info.description)
    }

    @Test
    fun jsDocOnInterfaceIsNotTheComponentDescription() {
        val info = parse(
            """
            <script setup lang="ts">
            /** The props */
            interface Props { a: string }
            defineProps<Props>()
            </script>
            """,
        )
        assertNull(info.description)
    }

    @Test
    fun descriptionFromDocsBlock() {
        val info = parse("<template><div /></template>\n<docs>\nRenders a **badge**.\n</docs>")
        assertEquals("Renders a **badge**.", info.description)
    }

    @Test
    fun brokenFilesYieldPartialResults() {
        parse("<script setup lang=\"ts\">\ndefineProps<{ a: string")
        parse("<template><slot name=\"x\"")
        parse("<script>export default { props: { a: { type: String, ")
        val info = parse("<template>\n<slot name=\"ok\" />\n<script setup>defineProps<{ b: number }>()</script>")
        assertTrue(info.slots.any { it.name == "ok" })
    }

    @Test
    fun halfTypedDefineModel() {
        val variants = listOf(
            "<script setup lang=\"ts\">\nconst model = defineModel('\n</script>\n<template><slot/></template>\n",
            "<script setup lang=\"ts\">\nconst model = defineModel(')\n</script>\n<template><slot/></template>\n",
            "<script setup lang=\"ts\">\nconst model = defineModel(\"\n</script>\n<template><slot/></template>\n",
        )
        val failures = variants.mapNotNull { v ->
            try {
                VueSfcParser.parse(v)
                null
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}: ${e.message} for ${v.lines()[1]}"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    /** Every prefix of realistic components (as while typing them) parses without an exception. */
    @Test
    fun truncatedComponentsNeverThrow() {
        val modules = TypeModuleResolver { specifier, _ -> if (specifier == "./types") TypeModule(FUZZ_TYPES) else null }
        val failures = ArrayList<String>()
        for (sfc in FUZZ_COMPONENTS) {
            for (length in 0..sfc.length) {
                val text = sfc.substring(0, length)
                for (variant in listOf(text, text + "\n</script>\n<template><slot /></template>\n")) {
                    try {
                        VueSfcParser.parse(variant, modules)
                    } catch (e: Throwable) {
                        failures += "${e.javaClass.simpleName}: ${e.message} at length $length of:\n${variant.takeLast(80)}"
                    }
                }
            }
        }
        for (length in 0..FUZZ_TYPES.length) {
            val types = FUZZ_TYPES.substring(0, length)
            try {
                VueSfcParser.parse(FUZZ_COMPONENTS.first()) { _, _ -> TypeModule(types) }
            } catch (e: Throwable) {
                failures += "${e.javaClass.simpleName}: ${e.message} in a module truncated at $length"
            }
        }
        assertTrue(failures.take(5).joinToString("\n\n"), failures.isEmpty())
    }

    @Test
    fun localTypeAliasesOfMembersAreExpanded() {
        val info = parse(
            """
            <script setup lang="ts">
            type Variant = 'solid' | 'outline'
            type Size = Sizes
            type Sizes = 'sm' | 'md'
            type Item = { label: string }
            interface Props { variant?: Variant; label: string; size?: Size | undefined; item?: Item; other?: Unknown }
            const props = withDefaults(defineProps<Props>(), { variant: 'solid' })
            </script>
            """,
        )
        assertEquals(listOf("solid", "outline"), info.prop("variant").literalValues)
        assertEquals(PropKind.STRING, info.prop("variant").kind)
        assertEquals("'solid'", info.prop("variant").defaultValue)
        assertEquals(true, info.prop("label").required)
        assertEquals(listOf("sm", "md"), info.prop("size").literalValues)
        assertEquals("Item", info.prop("item").type)
        assertEquals("Unknown", info.prop("other").type)
    }

    @Test
    fun emptyFile() {
        val info = parse("")
        assertTrue(info.props.isEmpty())
        assertTrue(info.slots.isEmpty())
        assertNull(info.description)
    }
}
