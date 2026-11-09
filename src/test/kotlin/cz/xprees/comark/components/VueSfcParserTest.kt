package cz.xprees.comark.components

import cz.xprees.comark.meta.PropKind
import cz.xprees.comark.meta.SfcInfo
import cz.xprees.comark.meta.VueSfcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VueSfcParserTest {
    private fun parse(text: String): SfcInfo = VueSfcParser.parse(text.trimIndent())

    private fun SfcInfo.prop(name: String) = findProp(name) ?: throw AssertionError("no prop $name in ${props.map { it.name }}")

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
    fun emptyFile() {
        val info = parse("")
        assertTrue(info.props.isEmpty())
        assertTrue(info.slots.isEmpty())
        assertNull(info.description)
    }
}
