package cz.xprees.comark.meta

import com.intellij.openapi.vfs.VirtualFile

/** Where the description of a component came from. Later sources win when the same component is described twice. */
enum class ComponentSource(val displayName: String) {
    /** Parsed from a `.vue` file in a `components/` directory. */
    VUE_FILE("Vue component"),

    /** Read from a `.nuxt/component-meta.mjs` / `.json` file written by nuxt-component-meta. */
    COMPONENT_META("nuxt-component-meta"),

    /** Fetched from [cz.xprees.comark.settings.ComarkSettings.componentMetadataUrl]. */
    REMOTE("remote metadata"),
}

/**
 * Broad value category of a prop. It decides how completion inserts the prop: booleans can be written bare (`{open}`), everything
 * that is not a string is usually passed as a `:binding` so Comark JSON-parses the value, and YAML props get a value skeleton.
 */
enum class PropKind {
    STRING, NUMBER, BOOLEAN, ARRAY, OBJECT, FUNCTION, UNKNOWN;

    companion object {
        /**
         * Kind of a TypeScript-ish type text such as `string | undefined`, `'info' | 'warning'`, `number[]` or `Record<string, any>`.
         * Mirrors the VS Code extension's `getPropValueType` (objects first, then arrays, booleans, numbers and strings).
         */
        fun of(type: String?): PropKind {
            if (type.isNullOrBlank()) return UNKNOWN
            val t = ComarkProp.cleanType(type.trim())
            val lower = t.lowercase()
            return when {
                // string literal unions, also the `'a' | 'b' | (string & object)` autocomplete idiom
                t.startsWith("'") || t.startsWith("\"") || t.startsWith("`") -> STRING
                t.contains("Record<")|| lower.contains("object") || t.startsWith("{") -> OBJECT
                t.endsWith("[]") || t.contains("[] ") || t.contains("[]|") || t.contains("Array<") || lower == "array" -> ARRAY
                lower.contains("=>") || lower == "function" -> FUNCTION
                Regex("""\bboolean\b""").containsMatchIn(lower) || lower == "true" || lower == "false" -> BOOLEAN
                Regex("""\bnumber\b""").containsMatchIn(lower) || Regex("""^-?\d+(\.\d+)?(\s*\|\s*-?\d+(\.\d+)?)*$""").matches(t) -> NUMBER
                Regex("""\bstring\b""").containsMatchIn(lower) || t.startsWith("'") || t.startsWith("\"") || t.startsWith("`") -> STRING
                else -> UNKNOWN
            }
        }
    }
}

/**
 * A prop a component accepts. [offset] is where its name is declared in the parsed source text, or -1 when unknown. [file] is the
 * TypeScript file the prop is declared in when that is not the component's own file (a props interface imported from another
 * module); [offset] is then an offset into that file.
 */
class ComarkProp(
    val name: String,
    val type: String? = null,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
    val offset: Int = -1,
    val file: VirtualFile? = null,
) {
    val kind: PropKind get() = PropKind.of(type)

    /** The type without the `| undefined` / `| null` noise vue-component-meta adds to optional props. */
    val displayType: String?
        get() = type?.let(::cleanType)?.takeIf { it.isNotEmpty() }

    /** How the prop is written in Markdown attributes and YAML: kebab-case (`iconName` -> `icon-name`). */
    val attributeName: String get() = ComponentNaming.kebabCase(name).ifEmpty { name }

    /** The values of a string literal union type (`'info' | 'warning' | (string & {})` -> `info`, `warning`), else empty. */
    val literalValues: List<String>
        get() {
            val type = displayType ?: return emptyList()
            return ScriptScanner.splitType(type, 0, type.length, '|').mapNotNull { range ->
                val part = type.substring(range.first, range.last + 1)
                if (part.length >= 2 && part[0] in "'\"`" && part.last() == part[0]) part.substring(1, part.length - 1) else null
            }.distinct()
        }

    fun withDescription(description: String?, defaultValue: String?): ComarkProp =
        ComarkProp(name, type, required, this.defaultValue ?: defaultValue, this.description ?: description, offset, file)

    override fun toString(): String = "$name: $type${if (required) " (required)" else ""}"

    companion object {
        fun cleanType(type: String): String = type
            .replace(Regex("""\s*\|\s*(undefined|null)\b"""), "")
            .replace(Regex("""^(undefined|null)\s*\|\s*"""), "")
            .trim()
    }
}

/** A slot a component renders. [offset] is where it is declared in the parsed source text (`<slot name>`), or -1. */
class ComarkSlotInfo(
    val name: String,
    val description: String? = null,
    val type: String? = null,
    val offset: Int = -1,
) {
    override fun toString(): String = "#$name"
}

/**
 * Everything known about one component, merged from all metadata sources.
 *
 * [name] is the PascalCase component name Nuxt registers (`ProseAlert`); Markdown usually writes it in kebab-case
 * ([kebabName], `prose-alert`). [aliases] are further names the same component resolves under (the name a `.vue` file was
 * discovered under when metadata names it differently). [file] is the `.vue` file when one is known, [root] the
 * directory of the Nuxt app it belongs to, used to prefer an app's own components in a monorepo.
 */
class ComarkComponent(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val documentation: String? = null,
    val docsUrl: String? = null,
    val props: List<ComarkProp> = emptyList(),
    val slots: List<ComarkSlotInfo> = emptyList(),
    val source: ComponentSource,
    val file: VirtualFile? = null,
    val root: VirtualFile? = null,
) {
    val kebabName: String = ComponentNaming.kebabCase(name).ifEmpty { name }

    /** Every name this component answers to, primary first. */
    val allNames: List<String> get() = listOf(name) + aliases

    fun findProp(name: String): ComarkProp? {
        val key = ComponentNaming.looseKey(name)
        return props.firstOrNull { it.name == name } ?: props.firstOrNull { ComponentNaming.looseKey(it.name) == key }
    }

    fun findSlot(name: String): ComarkSlotInfo? = slots.firstOrNull { it.name == name }
        ?: slots.firstOrNull { ComponentNaming.looseKey(it.name) == ComponentNaming.looseKey(name) }

    /** Named slots plus `default` when the component renders one. */
    val hasDefaultSlot: Boolean get() = slots.any { it.name == "default" }

    override fun toString(): String = "ComarkComponent($name, ${source.name}, props=${props.map { it.name }}, slots=${slots.map { it.name }})"
}
