package cz.xprees.comark.components

import cz.xprees.comark.meta.SfcInfo
import cz.xprees.comark.meta.TypeModule
import cz.xprees.comark.meta.TypeModuleResolver
import cz.xprees.comark.meta.VueSfcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prop types that [VueSfcParser] follows into other modules through imports and re-exports. */
class ImportedPropTypesParserTest {
    /** Parses [sfc] with modules looked up by their exact specifier in [modules]; also records which specifiers were asked for. */
    private class Modules(private val modules: Map<String, String>) : TypeModuleResolver {
        val requested = ArrayList<String>()

        override fun resolve(specifier: String, from: TypeModule?): TypeModule? {
            requested += specifier
            return modules[specifier]?.let { TypeModule(it.trimIndent()) }
        }
    }

    private fun parse(sfc: String, vararg modules: Pair<String, String>): SfcInfo = VueSfcParser.parse(sfc.trimIndent(), Modules(modules.toMap()))

    private fun SfcInfo.prop(name: String) = findProp(name) ?: throw AssertionError("no prop $name in ${props.map { it.name }}")

    private fun SfcInfo.names() = props.map { it.name }.toSet()

    private val alertTypes = """
        /** Props of the alert. */
        export interface Props {
          /** Heading shown above the content */
          title: string
          /**
           * Visual style
           * @default 'info'
           */
          type?: 'info' | 'warning'
          dismissible?: boolean
        }
    """

    @Test
    fun importTypeOfAnInterface() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { Props } from './types'
            defineProps<Props>()
            </script>
            """,
            "./types" to alertTypes,
        )
        assertEquals(setOf("title", "type", "dismissible"), info.names())
        val title = info.prop("title")
        assertTrue(title.required)
        assertEquals("string", title.type)
        assertEquals("Heading shown above the content", title.description)
        assertEquals("'info'", info.prop("type").defaultValue)
        assertEquals(listOf("info", "warning"), info.prop("type").literalValues)
        // the module text has no file here, so there is no position to point at
        assertEquals(-1, title.offset)
        assertNull(title.file)
    }

    @Test
    fun withDefaultsAroundAnImportedType() {
        val info = parse(
            """
            <script setup lang="ts">
            import { type Props, ref } from '../types/alert'
            const props = withDefaults(defineProps<Props>(), { type: 'warning', dismissible: false })
            </script>
            """,
            "../types/alert" to alertTypes,
        )
        // a documented `@default` is kept, as for local types
        assertEquals("'info'", info.prop("type").defaultValue)
        assertEquals("false", info.prop("dismissible").defaultValue)
        assertFalse(info.prop("dismissible").required)
    }

    @Test
    fun aliasedImportOfATypeAlias() {
        val info = parse(
            """
            <script setup lang="ts">
            // import type { Props } from './wrong'
            import type { AlertProps as Props } from '~/types'
            defineProps<Props>()
            </script>
            """,
            "~/types" to "export type AlertProps = {\n  color?: string\n  size: 'sm' | 'lg'\n}",
            "./wrong" to "export interface Props { wrong: string }",
        )
        assertEquals(setOf("color", "size"), info.names())
        assertTrue(info.prop("size").required)
    }

    @Test
    fun namedAndStarReExports() {
        val sfc = """
            <script setup lang="ts">
            import type { AlertProps, BadgeProps } from '~/types'
            defineProps<AlertProps & BadgeProps>()
            </script>
        """
        val info = parse(
            sfc,
            "~/types" to "export type { Props as AlertProps } from './alert'\nexport * from './badge'",
            "./alert" to alertTypes,
            "./badge" to "export interface BadgeProps { label: string }",
        )
        assertEquals(setOf("title", "type", "dismissible", "label"), info.names())
    }

    @Test
    fun localInterfaceExtendsImportedBase() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { Base } from './base'
            interface Props extends Base {
              title: string
            }
            defineProps<Props>()
            </script>
            """,
            "./base" to "export interface Base {\n  /** Element id */\n  id?: string\n}",
        )
        assertEquals(setOf("title", "id"), info.names())
        assertEquals("Element id", info.prop("id").description)
    }

    @Test
    fun importedInterfaceExtendsBaseOfItsOwnModule() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { Props } from './types'
            defineProps<Props>()
            </script>
            """,
            "./types" to """
                import type { Shared } from './shared'
                interface Local { local?: number }
                export interface Props extends Shared, Local<string> { own: string }
            """,
            "./shared" to "export type Shared = { shared: boolean }",
        )
        assertEquals(setOf("own", "shared", "local"), info.names())
    }

    @Test
    fun cyclicImportsEnd() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { A } from './a'
            defineProps<A>()
            </script>
            """,
            "./a" to "import type { B } from './b'\nexport interface A extends B { a: string }",
            "./b" to "import type { A } from './a'\nexport interface B extends A { b: string }",
        )
        assertEquals(setOf("a", "b"), info.names())
    }

    @Test
    fun unresolvableTypesFallBackToWhatIsKnown() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { Missing } from './missing'
            import type { Props } from './types'
            defineProps<Omit<Missing, 'x'> & Props & { extra?: string }>()
            </script>
            """,
            "./types" to alertTypes,
        )
        assertEquals(setOf("title", "type", "dismissible", "extra"), info.names())
    }

    @Test
    fun localDeclarationsWinAndNeedNoModules() {
        val modules = Modules(mapOf("./types" to alertTypes))
        val info = VueSfcParser.parse(
            """
            <script setup lang="ts">
            import type { Other } from './types'
            interface Props { local: string }
            defineProps<Props>()
            </script>
            """.trimIndent(),
            modules,
        )
        assertEquals(setOf("local"), info.names())
        assertTrue(modules.requested.isEmpty())
    }

    @Test
    fun withoutAResolverImportedTypesAreUnknown() {
        val info = VueSfcParser.parse("<script setup lang=\"ts\">\nimport type { Props } from './types'\ndefineProps<Props>()\n</script>")
        assertTrue(info.props.isEmpty())
    }

    @Test
    fun importedSlotTypes() {
        val info = parse(
            """
            <script setup lang="ts">
            import type { Slots } from './slots'
            defineSlots<Slots>()
            </script>
            """,
            "./slots" to "export interface Slots {\n  /** The footer */\n  footer(props: {}): any\n}",
        )
        val footer = info.findSlot("footer")!!
        assertEquals("The footer", footer.description)
        assertEquals(-1, footer.offset)
    }
}
