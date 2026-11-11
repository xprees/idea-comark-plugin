package cz.xprees.comark.meta

/**
 * Component naming the way Nuxt and Comark do it, so a `.vue` file found on disk gets the same name the app registers and a name
 * written in Markdown (`::prose-alert`, `::ProseAlert`, `:proseAlert`) finds it.
 *
 * The case helpers are ports of [scule](https://github.com/unjs/scule) (`splitByCase`, `pascalCase`, `kebabCase`), which both Nuxt
 * (component names) and Comark's Vue renderer (resolving `prose-alert` to `ProseAlert`) use; the path rules are Nuxt's
 * `scanComponents` / `resolveComponentNameSegments`. Keeping them faithful matters more than elegance: a rule that differs here
 * shows up as a false "unknown component" or a missing completion.
 */
object ComponentNaming {
    private const val SPLITTERS = "-_/."

    /** scule `splitByCase`: splits on `-`, `_`, `/`, `.` and case changes (`XMLHttpRequest` -> `XML`, `Http`, `Request`). */
    fun splitByCase(value: String): List<String> {
        val parts = ArrayList<String>()
        if (value.isEmpty()) return parts
        val buffer = StringBuilder()
        var previousUpper: Boolean? = null
        var previousSplitter: Boolean? = null
        for (char in value) {
            if (char in SPLITTERS) {
                parts += buffer.toString()
                buffer.setLength(0)
                previousUpper = null
                continue
            }
            val isUpper: Boolean? = if (char.isDigit()) null else char != char.lowercaseChar()
            if (previousSplitter == false) {
                if (previousUpper == false && isUpper == true) {
                    parts += buffer.toString()
                    buffer.setLength(0)
                    buffer.append(char)
                    previousUpper = isUpper
                    continue
                }
                if (previousUpper == true && isUpper == false && buffer.length > 1) {
                    val last = buffer[buffer.length - 1]
                    parts += buffer.substring(0, buffer.length - 1)
                    buffer.setLength(0)
                    buffer.append(last).append(char)
                    previousUpper = isUpper
                    continue
                }
            }
            buffer.append(char)
            previousUpper = isUpper
            previousSplitter = false
        }
        parts += buffer.toString()
        return parts.filter { it.isNotEmpty() }
    }

    fun pascalCase(value: String): String = pascalCase(splitByCase(value))

    fun pascalCase(parts: List<String>): String = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    fun camelCase(value: String): String = pascalCase(value).replaceFirstChar(Char::lowercaseChar)

    fun kebabCase(value: String): String = splitByCase(value).joinToString("-") { it.lowercase() }

    /**
     * The key a name is looked up by: its PascalCase form, which is what Comark's Vue renderer and Vue's own `resolveComponent`
     * end up comparing (`prose-alert`, `proseAlert` and `ProseAlert` all give `ProseAlert`).
     */
    fun lookupKey(name: String): String = pascalCase(name)

    /**
     * A forgiving key (lower case, separators dropped) used as a fallback, so acronyms (`UIButton` vs `ui-button`) and other case
     * slips still find their component for navigation and documentation.
     */
    fun looseKey(name: String): String = buildString(name.length) {
        for (c in name) if (c.isLetterOrDigit() || c == '$') append(c.lowercaseChar())
    }

    // ---- Nuxt component names from file paths ----------------------------------------------------------------------

    /**
     * Directories directly under `components/` that are component directories of their own: Nuxt registers `islands/` and
     * `global/`, Nuxt Content (and Comark's Nuxt module) `content/`.
     */
    private val OWN_DIRECTORIES = setOf("content", "global", "islands")

    /** Nuxt's `MODE_REPLACEMENT_RE`: `.client` / `.server`, then any `.global` / `.island` markers ending a file name. */
    private val MODE_SUFFIX = Regex("""(?:\.(?:client|server))?(?:\.global|\.island)*$""")

    /** Nuxt's grouping folders (`components/(forms)/Input.vue`), which only organise files and are not part of names. */
    private val GROUPING_FOLDER = Regex("""^\(.*\)$""")

    /**
     * Names of the component in [relativePath], a path relative to a Nuxt `components/` directory (`content/prose/Alert.vue`).
     * Nuxt registers one name per file, so the list has at most one entry (empty when the path names no component).
     *
     * `components/global/`, `components/islands/` and `components/content/` are component directories of their own (Nuxt
     * registers the first two, Nuxt Content the third), so their name is not part of the component name. Nuxt Content registers
     * `components/content/` without path prefixes, and Nuxt skips its files when it scans `components/` afterwards, so
     * `content/prose/Alert.vue` is only ever registered as `Alert`, never as `ProseAlert`.
     */
    fun componentNames(relativePath: String): List<String> {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val fileName = segments.last().substringBeforeLast('.')
        var directories = segments.dropLast(1)
        val own = directories.firstOrNull()?.takeIf { it in OWN_DIRECTORIES }
        if (own != null) directories = directories.drop(1)
        return listOf(nuxtComponentName(directories, fileName, pathPrefix = own != "content")).filter { it.isNotEmpty() }
    }

    /**
     * Nuxt's name for the file [fileName] (without extension) in [directories] below a component directory registered with
     * `pathPrefix` = [pathPrefix]. `.client` / `.server` / `.global` / `.island` suffixes are mode markers, not part of the
     * name, grouping folders (`(forms)`) are not part of the prefix, and an `index` file is named after its directory.
     */
    fun nuxtComponentName(directories: List<String>, fileName: String, pathPrefix: Boolean): String {
        var name = fileName.replace(MODE_SUFFIX, "")
        if (name.equals("index", ignoreCase = true)) name = if (pathPrefix) "" else directories.lastOrNull().orEmpty()
        val prefixParts = if (pathPrefix) directories.filterNot { GROUPING_FOLDER.matches(it) }.flatMap(::splitByCase) else emptyList()
        return pascalCase(resolveComponentNameSegments(name.replace("\"", "").replace("'", ""), prefixParts))
    }

    /**
     * What a usage written as [written] (`prose-alert`, `ProseAlert`, `alert`) becomes when its component's file moves to
     * [relativePath] (relative to the `components/` directory, as for [componentNames]), or null when that path names no
     * component. The usage's case style is kept ([inStyleOf]).
     */
    fun renamedUsage(written: String, relativePath: String): String? {
        val name = componentNames(relativePath).firstOrNull() ?: return null
        val short = proseShortName(name)
        return inStyleOf(written, if (short != null && !isProsePrefixed(written)) short else name)
    }

    // ---- Prose components -------------------------------------------------------------------------------------------

    private const val PROSE = "Prose"

    /**
     * Tags Nuxt Content and Comark render through `Prose*` components on their own (`p` -> `ProseP`). Their prose components replace
     * plain Markdown and are not written as `::p`, so they get no short name.
     */
    private val PROSE_TAGS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "strong", "em", "s", "code", "span", "blockquote", "pre", "hr", "img",
        "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "script",
    )

    /** True when [name] starts with the `prose` word (`prose-alert`, `ProseAlert`, `proseAlert`). */
    fun isProsePrefixed(name: String): Boolean = splitByCase(name).let { it.size > 1 && it[0].equals(PROSE, ignoreCase = true) }

    /**
     * The name a `Prose*` component is written under without its prefix (`ProseAccordionItem` -> `accordion-item`), or null when
     * [name] is not a prose component or is one of the [PROSE_TAGS] renderers.
     *
     * Comark's Vue renderer resolves every tag to `Prose<Tag>` before `<Tag>`, and Nuxt UI maps its prose components the same way
     * for Nuxt Content (`mdc.components.map`: `accordion-item` -> `ProseAccordionItem`), so `::accordion-item` and
     * `::prose-accordion-item` render the same component.
     */
    fun proseShortName(name: String): String? {
        if (!isProsePrefixed(name)) return null
        val short = splitByCase(name).drop(1).joinToString("-") { it.lowercase() }
        return short.takeUnless { it.isEmpty() || it in PROSE_TAGS }
    }

    /** The lookup key of the `Prose*` component the unprefixed [name] can stand for (`accordion-item` -> `ProseAccordionItem`). */
    fun proseLookupKey(name: String): String = PROSE + lookupKey(name)

    /** [name] in the case style of [written]: kebab-case (`prose-alert`, also for all lower case), PascalCase or camelCase. */
    fun inStyleOf(written: String, name: String): String = when {
        written.contains('-') || written.none { it.isUpperCase() } -> kebabCase(name)
        written.first().isUpperCase() -> pascalCase(name)
        else -> camelCase(name)
    }


    /**
     * Port of Nuxt's `resolveComponentNameSegments`: joins directory and file name parts, dropping a directory prefix the file
     * name already repeats (`prose/ProseAlert.vue` is `ProseAlert`, not `ProseProseAlert`).
     */
    fun resolveComponentNameSegments(fileName: String, prefixParts: List<String>): List<String> {
        val fileNameParts = splitByCase(fileName)
        val fileNamePartsContent = fileNameParts.joinToString("/").lowercase()
        val componentNameParts = prefixParts.flatMap(::splitByCase).toMutableList()
        var index = prefixParts.size - 1
        val matchedSuffix = ArrayList<String>()
        while (index >= 0) {
            matchedSuffix.addAll(0, splitByCase(prefixParts[index]).map { it.lowercase() })
            val matchedSuffixContent = matchedSuffix.joinToString("/")
            val repeatsPrefix = fileNamePartsContent == matchedSuffixContent || fileNamePartsContent.startsWith("$matchedSuffixContent/")
            // e.g. Item/Item/Item.vue -> Item
            val repeatedDirectory = prefixParts[index].lowercase() == fileNamePartsContent &&
                index + 1 < prefixParts.size && prefixParts[index] == prefixParts[index + 1]
            if ((repeatsPrefix || repeatedDirectory) && index < componentNameParts.size) {
                while (componentNameParts.size > index) componentNameParts.removeAt(componentNameParts.size - 1)
            }
            index--
        }
        return componentNameParts + fileNameParts
    }
}
