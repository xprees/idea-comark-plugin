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

    /** Directories directly under `components/` that Nuxt (and Nuxt Content) register as component directories of their own. */
    private val OWN_DIRECTORIES = setOf("content", "global")

    /**
     * Names of the component in [relativePath], a path relative to a Nuxt `components/` directory (`content/prose/Alert.vue`).
     * The first name is the primary one, the rest are aliases.
     *
     * `components/global/` and `components/content/` are component directories of their own (Nuxt registers the first, Nuxt
     * Content the second), so their name is not part of the component name: `content/prose/Alert.vue` is `ProseAlert`.
     * Nuxt Content registers `components/content/` without path prefixes, which makes the plain file name (`Alert`) the name it
     * resolves at runtime; that name is kept as an alias so both spellings resolve.
     */
    fun componentNames(relativePath: String): List<String> {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val fileName = segments.last().substringBeforeLast('.')
        var directories = segments.dropLast(1)
        val names = LinkedHashSet<String>()
        val own = directories.firstOrNull()?.takeIf { it in OWN_DIRECTORIES }
        if (own != null) directories = directories.drop(1)
        names += nuxtComponentName(directories, fileName, pathPrefix = true)
        if (own == "content") names += nuxtComponentName(directories, fileName, pathPrefix = false)
        return names.filter { it.isNotEmpty() }
    }

    /**
     * Nuxt's name for the file [fileName] (without extension) in [directories] below a component directory registered with
     * `pathPrefix` = [pathPrefix]. `.client` / `.server` / `.global` suffixes are mode markers, not part of the name, and an
     * `index` file is named after its directory.
     */
    fun nuxtComponentName(directories: List<String>, fileName: String, pathPrefix: Boolean): String {
        var name = fileName
        while (true) {
            val stripped = name.removeSuffix(".client").removeSuffix(".server").removeSuffix(".global")
            if (stripped == name) break
            name = stripped
        }
        if (name.equals("index", ignoreCase = true)) name = if (pathPrefix) "" else directories.lastOrNull().orEmpty()
        val prefixParts = if (pathPrefix) directories.flatMap(::splitByCase) else emptyList()
        return pascalCase(resolveComponentNameSegments(name.replace("\"", "").replace("'", ""), prefixParts))
    }

    /**
     * What a usage written as [written] (`prose-alert`, `ProseAlert`, `alert`) becomes when its component's file moves to
     * [relativePath] (relative to the `components/` directory, as for [componentNames]), or null when that path names no
     * component. A usage that spelled out the directory prefix keeps it, one that used Nuxt Content's bare file name alias keeps
     * using the alias, and the usage's case style is kept ([inStyleOf]).
     */
    fun renamedUsage(written: String, relativePath: String): String? {
        val names = componentNames(relativePath)
        if (names.isEmpty()) return null
        val name = if (names.size > 1 && !spellsDirectoryPrefix(written, relativePath)) names[1] else names[0]
        return inStyleOf(written, name)
    }

    /** [name] in the case style of [written]: kebab-case (`prose-alert`, also for all lower case), PascalCase or camelCase. */
    fun inStyleOf(written: String, name: String): String = when {
        written.contains('-') || written.none { it.isUpperCase() } -> kebabCase(name)
        written.first().isUpperCase() -> pascalCase(name)
        else -> camelCase(name)
    }

    /** True when [written] starts with the directory prefix of [relativePath] (or there is none to start with). */
    private fun spellsDirectoryPrefix(written: String, relativePath: String): Boolean {
        var directories = relativePath.split('/').filter { it.isNotEmpty() }.dropLast(1)
        if (directories.firstOrNull() in OWN_DIRECTORIES) directories = directories.drop(1)
        val prefix = looseKey(directories.joinToString(""))
        return prefix.isEmpty() || looseKey(written).startsWith(prefix)
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
