package cz.xprees.comark.meta

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.meta.ScriptScanner.findClosing
import cz.xprees.comark.meta.ScriptScanner.isCommentStart
import cz.xprees.comark.meta.ScriptScanner.isIdentPart
import cz.xprees.comark.meta.ScriptScanner.isIdentStart
import cz.xprees.comark.meta.ScriptScanner.isWordAt
import cz.xprees.comark.meta.ScriptScanner.normalize
import cz.xprees.comark.meta.ScriptScanner.objectEntries
import cz.xprees.comark.meta.ScriptScanner.parseDoc
import cz.xprees.comark.meta.ScriptScanner.skipComment
import cz.xprees.comark.meta.ScriptScanner.skipString
import cz.xprees.comark.meta.ScriptScanner.skipTrivia
import cz.xprees.comark.meta.ScriptScanner.splitTopLevel
import cz.xprees.comark.meta.ScriptScanner.startsWith
import cz.xprees.comark.meta.ScriptScanner.typeMembers

/** What [VueSfcParser] found in a single-file component. Offsets are into the parsed text. */
class SfcInfo(val description: String?, val props: List<ComarkProp>, val slots: List<ComarkSlotInfo>) {
    fun findProp(name: String): ComarkProp? {
        val key = ComponentNaming.looseKey(name)
        return props.firstOrNull { it.name == name } ?: props.firstOrNull { ComponentNaming.looseKey(it.name) == key }
    }

    fun findSlot(name: String): ComarkSlotInfo? = slots.firstOrNull { it.name == name }

    companion object {
        val EMPTY = SfcInfo(null, emptyList(), emptyList())
    }
}

/** The text of a TypeScript module a component imports types from. [file] is null for text that has no file (tests). */
class TypeModule(val text: CharSequence, val file: VirtualFile? = null)

/** Finds the modules that import specifiers of a component's scripts (and of the modules they import) refer to. */
fun interface TypeModuleResolver {
    /** The module [specifier] (`'./types'`, `'~/types/alert'`) refers to when imported by [from] (null: the component), or null. */
    fun resolve(specifier: String, from: TypeModule?): TypeModule?
}

/**
 * Extracts props, slots and a description from a Vue single-file component by reading its text.
 *
 * This deliberately does not use the Vue plugin: it is not available in every IDE the plugin supports (and not on its compile
 * classpath), and Markdown completion should not have to build full Vue/TypeScript PSI for every component in the project. The
 * parser understands the common ways of declaring a component's API:
 * - `defineProps<{ ... }>()` with a type literal or a local `interface` / `type` (intersections, `Partial`, `Omit`, `Pick`),
 *   optional `?` members, JSDoc descriptions and `@default` tags,
 * - named prop types imported from other modules (`import type { Props } from './types'`, also through `export { ... } from`
 *   and `export * from` re-exports and `extends` of imported interfaces), when a [TypeModuleResolver] supplies their text,
 * - `defineProps({ ... })` / `defineProps([...])` runtime declarations with `type`, `required` and `default`,
 * - defaults from `withDefaults(defineProps<...>(), { ... })` and from reactive props destructuring,
 * - `defineModel()`,
 * - the options API (`export default { props }`, `defineComponent({ props })`),
 * - slots from `<slot>` / `<slot name>` in the template, `defineSlots<{ ... }>()`, `SlotsType<{ ... }>` and `$slots.name`,
 * - a description from a JSDoc comment (before `export default` / `defineComponent` / `defineOptions`, or leading the script),
 *   a leading HTML comment or a `<docs>` block.
 */
object VueSfcParser {
    /**
     * Parses the single-file component [text]. Type names imported from other modules are resolved through [modules]; without
     * one, only types declared in the component itself are known.
     */
    fun parse(text: CharSequence, modules: TypeModuleResolver? = null): SfcInfo {
        val blocks = findBlocks(text)
        val scripts = blocks.filter { it.tag == "script" }
        val template = blocks.firstOrNull { it.tag == "template" }
        val types = TypeContext(modules)

        val props = LinkedHashMap<String, ComarkProp>()
        for (script in scripts) {
            ProgressManager.checkCanceled()
            ScriptProps(text, script, scripts, types).collect(props)
        }

        val slots = LinkedHashMap<String, ComarkSlotInfo>()
        template?.let { collectTemplateSlots(text, it, slots) }
        for (script in scripts) collectScriptSlots(text, script, scripts, types, slots)
        collectSlotReferences(text, blocks.filter { it.tag == "template" || it.tag == "script" }, slots)

        return SfcInfo(describe(text, blocks), props.values.toList(), slots.values.toList())
    }

    // ---- Top-level blocks -------------------------------------------------------------------------------------------

    /** A top-level SFC block (`<template>`, `<script setup>`, `<docs>`, ...) and the HTML comment right before it, if any. */
    internal class Block(val tag: String, val attributes: String, val start: Int, val contentStart: Int, val contentEnd: Int, val comment: String?) {
        val isSetup: Boolean get() = Regex("""(^|\s)setup(\s|=|$)""").containsMatchIn(attributes)
    }

    internal fun findBlocks(text: CharSequence): List<Block> {
        val blocks = ArrayList<Block>()
        val n = text.length
        var i = 0
        var comment: String? = null
        var commentEnd = -1
        while (i < n) {
            val lt = indexOfChar(text, '<', i)
            if (lt < 0) break
            if (startsWith(text, lt, "<!--")) {
                val close = ScriptScanner.indexOf(text, "-->", lt + 4)
                comment = text.subSequence(lt + 4, if (close < 0) n else close).toString().trim()
                i = if (close < 0) n else close + 3
                commentEnd = i
                continue
            }
            var j = lt + 1
            if (j >= n || !text[j].isLetter()) {
                i = lt + 1
                continue
            }
            while (j < n && (text[j].isLetterOrDigit() || text[j] == '-')) j++
            val tag = text.subSequence(lt + 1, j).toString().lowercase()
            val tagEnd = findTagEnd(text, j)
            if (tagEnd < 0) break
            val selfClosing = text[tagEnd - 1] == '/'
            val attributes = text.subSequence(j, if (selfClosing) tagEnd - 1 else tagEnd).toString()
            val leading = comment?.takeIf { commentEnd >= 0 && text.subSequence(commentEnd, lt).isBlank() }
            comment = null
            if (selfClosing) {
                blocks += Block(tag, attributes, lt, tagEnd + 1, tagEnd + 1, leading)
                i = tagEnd + 1
                continue
            }
            val contentStart = tagEnd + 1
            val closeStart = if (tag == "template") findTemplateClose(text, contentStart) else indexOfIgnoreCase(text, "</$tag", contentStart)
            blocks += Block(tag, attributes, lt, contentStart, if (closeStart < 0) n else closeStart, leading)
            i = if (closeStart < 0) n else indexOfChar(text, '>', closeStart).let { if (it < 0) n else it + 1 }
        }
        return blocks
    }

    /** Index of the `>` ending the tag whose attributes start at [from] (quoted attribute values are skipped). */
    private fun findTagEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '"', '\'' -> {
                    val close = indexOfChar(text, text[i], i + 1)
                    if (close < 0) return -1
                    i = close + 1
                }

                '>' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** Start of the `</template>` closing the top-level template, counting nested `<template>` tags (v-slot templates). */
    private fun findTemplateClose(text: CharSequence, from: Int): Int {
        var depth = 0
        var i = from
        while (true) {
            val lt = indexOfChar(text, '<', i)
            if (lt < 0) return -1
            when {
                startsWith(text, lt, "<!--") -> {
                    val close = ScriptScanner.indexOf(text, "-->", lt + 4)
                    if (close < 0) return -1
                    i = close + 3
                }

                isTag(text, lt + 2, "template") && text[lt + 1] == '/' -> {
                    if (depth == 0) return lt
                    depth--
                    i = lt + 2
                }

                isTag(text, lt + 1, "template") -> {
                    val end = findTagEnd(text, lt + 9)
                    if (end < 0) return -1
                    if (text[end - 1] != '/') depth++
                    i = end + 1
                }

                else -> i = lt + 1
            }
        }
    }

    private fun isTag(text: CharSequence, at: Int, name: String): Boolean {
        if (at < 0 || at + name.length > text.length) return false
        if (!text.subSequence(at, at + name.length).toString().equals(name, ignoreCase = true)) return false
        val after = at + name.length
        return after >= text.length || text[after].isWhitespace() || text[after] == '>' || text[after] == '/'
    }

    // ---- Props ------------------------------------------------------------------------------------------------------

    /** Props declared in one `<script>` block. Type references are resolved against all script blocks of the file. */
    private class ScriptProps(private val text: CharSequence, script: Block, private val scripts: List<Block>, private val types: TypeContext) {
        private val start = script.contentStart
        private val end = script.contentEnd

        fun collect(into: MutableMap<String, ComarkProp>) {
            for (call in findCalls(text, start, end, "defineProps")) {
                val declared = when {
                    call.typeOpen >= 0 -> TypeResolver(text, scripts, null, types).props(call.typeOpen + 1, call.typeClose)
                    call.argsOpen >= 0 -> runtimeArgumentProps(call)
                    else -> emptyList()
                }
                val defaults = withDefaults(call) + destructuredDefaults(call)
                for (prop in declared) {
                    val default = defaults[prop.name]
                    into.putIfAbsent(prop.name, if (default != null && prop.defaultValue == null) prop.withDefault(default) else prop)
                }
            }
            for (call in findCalls(text, start, end, "defineModel")) modelProp(call)?.let { into.putIfAbsent(it.name, it) }
            optionsObject()?.let { (open, close) ->
                val propsEntry = objectEntries(text, open, close).firstOrNull { it.key == "props" && it.valueStart >= 0 }
                if (propsEntry != null) runtimeValueProps(propsEntry.valueStart, propsEntry.valueEnd).forEach { into.putIfAbsent(it.name, it) }
            }
        }

        private fun runtimeArgumentProps(call: Call): List<ComarkProp> {
            val first = splitTopLevel(text, call.argsOpen + 1, call.argsClose).firstOrNull() ?: return emptyList()
            return runtimeValueProps(first.first, first.last + 1)
        }

        /** Props of a runtime declaration value: an object literal, an array of names, or a local constant holding either. */
        private fun runtimeValueProps(valueStart: Int, valueEnd: Int): List<ComarkProp> {
            val s = skipTrivia(text, valueStart, valueEnd)
            if (s >= valueEnd) return emptyList()
            return when {
                text[s] == '{' -> {
                    val close = findClosing(text, s, valueEnd)
                    if (close < 0) emptyList() else objectEntries(text, s, close).map(::runtimeProp)
                }

                text[s] == '[' -> {
                    val close = findClosing(text, s, valueEnd)
                    if (close < 0) emptyList() else stringLiterals(s + 1, close).map { (name, offset) -> ComarkProp(name, offset = offset) }
                }

                isIdentStart(text[s]) -> {
                    var e = s
                    while (e < valueEnd && isIdentPart(text[e])) e++
                    val constant = findConstant(text, scripts, text.subSequence(s, e).toString()) ?: return emptyList()
                    runtimeValueProps(constant, findClosing(text, constant, text.length) + 1)
                }

                else -> emptyList()
            }
        }

        private fun stringLiterals(from: Int, to: Int): List<Pair<String, Int>> = splitTopLevel(text, from, to).mapNotNull { range ->
            quoted(text, range)?.let { it to range.first + 1 }
        }

        private fun runtimeProp(entry: ScriptScanner.ObjectEntry): ComarkProp {
            val doc = entry.doc?.let(::parseDoc)
            val value = entry.value(text)
            var type: String? = null
            var required = false
            var default: String? = doc?.tags?.get("default") ?: doc?.tags?.get("defaultValue")
            if (value != null) {
                if (value.startsWith("{")) {
                    val close = findClosing(text, entry.valueStart, entry.valueEnd + 1)
                    if (close > 0) {
                        for (option in objectEntries(text, entry.valueStart, close)) {
                            when (option.key) {
                                "type" -> type = option.value(text)?.let(::constructorType)
                                "required" -> required = option.value(text) == "true"
                                "default" -> option.value(text)?.let { default = simplifyDefault(it) }
                            }
                        }
                    }
                } else {
                    type = constructorType(value)
                }
            }
            return ComarkProp(entry.key, type, required, default, doc?.description, entry.keyStart)
        }

        private fun withDefaults(call: Call): Map<String, String> {
            for (wrapper in findCalls(text, start, end, "withDefaults")) {
                if (wrapper.argsOpen < 0 || call.nameStart !in wrapper.argsOpen..wrapper.argsClose) continue
                val second = splitTopLevel(text, wrapper.argsOpen + 1, wrapper.argsClose).getOrNull(1) ?: continue
                if (text[second.first] != '{') continue
                val close = findClosing(text, second.first, wrapper.argsClose)
                if (close < 0) continue
                return objectEntries(text, second.first, close).mapNotNull { e -> e.value(text)?.let { e.key to simplifyDefault(it) } }.toMap()
            }
            return emptyMap()
        }

        /** Defaults of reactive props destructuring: `const { size = 'md', open: isOpen = false } = defineProps<...>()`. */
        private fun destructuredDefaults(call: Call): Map<String, String> {
            var i = call.nameStart - 1
            while (i >= start && text[i].isWhitespace()) i--
            if (i < start || text[i] != '=' || (i > start && (text[i - 1] == '=' || text[i - 1] == '!'))) return emptyMap()
            i--
            while (i >= start && text[i].isWhitespace()) i--
            if (i < start || text[i] != '}') return emptyMap()
            val close = i
            var depth = 0
            while (i >= start) {
                when (text[i]) {
                    '}' -> depth++
                    '{' -> if (--depth == 0) break
                }
                i--
            }
            if (i < start) return emptyMap()
            val result = HashMap<String, String>()
            for (part in splitTopLevel(text, i + 1, close)) {
                val entry = text.subSequence(part.first, part.last + 1).toString()
                if (entry.startsWith("...")) continue
                val eq = topLevelEquals(entry)
                if (eq < 0) continue
                val name = entry.substring(0, eq).substringBefore(':').trim()
                if (name.isNotEmpty()) result[name] = simplifyDefault(entry.substring(eq + 1).trim())
            }
            return result
        }

        private fun modelProp(call: Call): ComarkProp? {
            if (call.argsOpen < 0) return null
            val args = splitTopLevel(text, call.argsOpen + 1, call.argsClose)
            var name = "modelValue"
            var offset = call.nameStart
            var required = false
            var default: String? = null
            var type = if (call.typeOpen >= 0) normalize(text.subSequence(call.typeOpen + 1, call.typeClose)) else null
            for (arg in args) {
                val c = text[arg.first]
                if (c == '"' || c == '\'' || c == '`') {
                    // a half-typed `defineModel('` has no complete name yet
                    name = quoted(text, arg) ?: continue
                    offset = arg.first + 1
                } else if (c == '{') {
                    val close = findClosing(text, arg.first, arg.last + 1)
                    if (close < 0) continue
                    for (option in objectEntries(text, arg.first, close)) {
                        when (option.key) {
                            "required" -> required = option.value(text) == "true"
                            "default" -> default = option.value(text)?.let(::simplifyDefault)
                            "type" -> if (type == null) type = option.value(text)?.let(::constructorType)
                        }
                    }
                }
            }
            return ComarkProp(name, type, required, default, null, offset)
        }

        /** The options object of `export default { ... }` / `export default defineComponent({ ... })`, as brace offsets. */
        private fun optionsObject(): Pair<Int, Int>? {
            var i = start
            while (i < end) {
                val c = text[i]
                when {
                    c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                    isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                    isWordAt(text, i, "export", end) -> {
                        val d = skipTrivia(text, i + 6, end)
                        if (isWordAt(text, d, "default", end)) return objectAfter(skipTrivia(text, d + 7, end))
                        i = d
                    }

                    else -> i++
                }
            }
            return findCalls(text, start, end, "defineComponent").firstNotNullOfOrNull { call ->
                if (call.argsOpen < 0) null else objectAfter(skipTrivia(text, call.argsOpen + 1, call.argsClose))
            }
        }

        /** `{ ... }` or `anyCall({ ... })` at [at]. */
        private fun objectAfter(at: Int): Pair<Int, Int>? {
            if (at >= end) return null
            if (text[at] == '{') return findClosing(text, at, end).takeIf { it > 0 }?.let { at to it }
            var i = at
            while (i < end && (isIdentPart(text[i]) || text[i] == '.')) i++
            i = skipTrivia(text, i, end)
            if (i >= end || text[i] != '(') return null
            val inner = skipTrivia(text, i + 1, end)
            return if (inner < end && text[inner] == '{') findClosing(text, inner, end).takeIf { it > 0 }?.let { inner to it } else null
        }
    }

    /**
     * State shared by the [TypeResolver]s of one parse, across the component and the modules it imports from: how deep type
     * resolution is nested, and which named types are being resolved (so that cyclic imports and `extends` chains end).
     */
    private class TypeContext(val modules: TypeModuleResolver?) {
        var depth = 0
        val resolving = HashSet<Pair<Any, String>>()
    }

    /**
     * Resolves a props / slots type expression to its members: literals, interfaces and type aliases, utility types. Named types
     * are looked up in [scripts] of [text] first, then followed through imports and re-exports into other modules. [module] is the
     * module [text] belongs to, or null for the component; members declared in a module are tagged with its file.
     */
    private class TypeResolver(
        private val text: CharSequence,
        private val scripts: List<Block>,
        private val module: TypeModule?,
        private val context: TypeContext,
    ) {
        private val imports: Imports by lazy { findImports(text, scripts) }

        fun props(from: Int, to: Int): List<ComarkProp> = members(from, to).map { member ->
            val doc = member.doc?.let(::parseDoc)
            ComarkProp(
                name = member.name,
                type = if (member.isMethod) "function" else member.type,
                required = !member.optional,
                defaultValue = doc?.tags?.get("default") ?: doc?.tags?.get("defaultValue"),
                description = doc?.description,
                offset = member.nameStart,
                file = member.file,
            )
        }

        fun members(from: Int, to: Int): List<ScriptScanner.TypeMember> {
            if (++context.depth > MAX_TYPE_DEPTH) {
                context.depth--
                return emptyList()
            }
            try {
                val result = LinkedHashMap<String, ScriptScanner.TypeMember>()
                for (part in ScriptScanner.splitType(text, from, to, '&')) {
                    partMembers(part.first, part.last + 1).forEach { result.putIfAbsent(it.name, it) }
                }
                return result.values.toList()
            } finally {
                context.depth--
            }
        }

        private fun partMembers(from: Int, to: Int): List<ScriptScanner.TypeMember> {
            val s = skipTrivia(text, from, to)
            if (s >= to) return emptyList()
            if (text[s] == '{') {
                val close = findClosing(text, s, to)
                return if (close < 0) emptyList() else local(typeMembers(text, s, close))
            }
            if (text[s] == '(') {
                val close = findClosing(text, s, to)
                return if (close < 0) emptyList() else members(s + 1, close)
            }
            if (!isIdentStart(text[s])) return emptyList()
            var e = s
            while (e < to && (isIdentPart(text[e]) || text[e] == '.')) e++
            val name = text.subSequence(s, e).toString()
            val argsOpen = skipTrivia(text, e, to)
            val args = if (argsOpen < to && text[argsOpen] == '<') {
                val close = findClosing(text, argsOpen, to)
                if (close < 0) emptyList() else ScriptScanner.splitTopLevel(text, argsOpen + 1, close)
            } else {
                emptyList()
            }
            return when (name) {
                "Partial" -> args.firstOrNull()?.let { a -> members(a.first, a.last + 1).map { it.copy(optional = true) } }.orEmpty()
                "Required" -> args.firstOrNull()?.let { a -> members(a.first, a.last + 1).map { it.copy(optional = false) } }.orEmpty()
                "Readonly" -> args.firstOrNull()?.let { a -> members(a.first, a.last + 1) }.orEmpty()
                "Omit", "Pick" -> {
                    val base = args.firstOrNull()?.let { a -> members(a.first, a.last + 1) }.orEmpty()
                    val keys = args.getOrNull(1)?.let { keyLiterals(it.first, it.last + 1) }.orEmpty()
                    if (name == "Omit") base.filter { it.name !in keys } else base.filter { it.name in keys }
                }

                else -> namedMembers(name).orEmpty()
            }
        }

        /**
         * Members of the type [name]: declared in [text], or imported / re-exported from another module. Null when the name is
         * not known at all (so that `export *` re-exports can try the next module).
         */
        fun namedMembers(name: String): List<ScriptScanner.TypeMember>? {
            val key = (module?.file ?: module?.text ?: text) to name
            if (!context.resolving.add(key)) return emptyList()
            try {
                declaredMembers(name)?.let { return it }
                imports.aliases[name]?.let { local -> return namedMembers(local) }
                return importedMembers(name)
            } finally {
                context.resolving.remove(key)
            }
        }

        /** Members of [name] when it is imported (`import type { Props }`) or re-exported (`export { Props } from`) by [text]. */
        private fun importedMembers(name: String): List<ScriptScanner.TypeMember>? {
            val resolver = context.modules ?: return null
            imports.named[name]?.let { (imported, specifier) ->
                return resolver.resolve(specifier, module)?.let { moduleResolver(it).namedMembers(imported) }
            }
            for (specifier in imports.star) {
                ProgressManager.checkCanceled()
                val target = resolver.resolve(specifier, module) ?: continue
                moduleResolver(target).namedMembers(name)?.let { return it }
            }
            return null
        }

        private fun moduleResolver(target: TypeModule) =
            TypeResolver(target.text, listOf(Block("script", "", 0, 0, target.text.length, null)), target, context)

        /**
         * [members] declared in [text]: a member type naming a type alias is replaced by the aliased type ([memberType]), and
         * members of a module are tagged with its file (offsets are only kept when there is a file to point at).
         */
        private fun local(members: List<ScriptScanner.TypeMember>): List<ScriptScanner.TypeMember> = members.map {
            val nameStart = if (module == null || module.file != null) it.nameStart else -1
            ScriptScanner.TypeMember(it.name, nameStart, it.optional, memberType(it.type), it.isMethod, it.doc, module?.file ?: it.file)
        }

        /**
         * [type] with a type alias it consists of (`Variant`, also `Variant | undefined`) replaced by the aliased type when that is
         * no object type (`type Variant = 'solid' | 'outline'` turns `Variant` into `'solid' | 'outline'`), so literal values and
         * the kind of a prop are known. Aliases are looked up like named types: in [text], then through imports.
         */
        private fun memberType(type: String?): String? {
            if (type == null) return null
            val name = ComarkProp.cleanType(type)
            return if (isAliasCandidate(name)) aliasedType(name) ?: type else type
        }

        /**
         * The type a type alias [name] stands for, as text, when it is declared in [text] (or imported / re-exported from another
         * module) and is no object type; null otherwise. An alias of another alias is followed in the module declaring it.
         */
        fun aliasedType(name: String): String? {
            val key = (module?.file ?: module?.text ?: text) to "type $name"
            if (context.depth > MAX_TYPE_DEPTH || !context.resolving.add(key)) return null
            context.depth++
            try {
                when (val declaration = declaration(name)) {
                    is Declaration.Alias -> {
                        val expression = ScriptScanner.normalize(text.subSequence(declaration.start, declaration.end))
                        if (expression.isEmpty() || expression.startsWith("{")) return null
                        return if (isAliasCandidate(expression)) aliasedType(expression) ?: expression else expression
                    }

                    is Declaration.Interface -> return null
                    null -> {}
                }
                imports.aliases[name]?.let { local -> return aliasedType(local) }
                val resolver = context.modules ?: return null
                imports.named[name]?.let { (imported, specifier) ->
                    return resolver.resolve(specifier, module)?.let { moduleResolver(it).aliasedType(imported) }
                }
                for (specifier in imports.star) {
                    ProgressManager.checkCanceled()
                    val target = resolver.resolve(specifier, module) ?: continue
                    moduleResolver(target).aliasedType(name)?.let { return it }
                }
                return null
            } finally {
                context.depth--
                context.resolving.remove(key)
            }
        }

        /** Whether [type] is a bare type name that may be an alias (`Variant`, `Ns.Variant`; not `string`). */
        private fun isAliasCandidate(type: String): Boolean =
            type.isNotEmpty() && isIdentStart(type[0]) && type.all { isIdentPart(it) || it == '.' } && type !in BUILT_IN_TYPES

        private fun keyLiterals(from: Int, to: Int): Set<String> =
            ScriptScanner.splitType(text, from, to, '|').mapNotNull { quoted(text, it) }.toSet()

        /** Members of an `interface Name { ... }` (with its `extends`) or `type Name = ...` declared in [text], or null. */
        private fun declaredMembers(name: String): List<ScriptScanner.TypeMember>? = when (val declaration = declaration(name)) {
            null -> null
            is Declaration.Alias -> members(declaration.start, declaration.end)
            is Declaration.Interface -> {
                if (declaration.open < 0 || declaration.close < 0) {
                    emptyList()
                } else {
                    val own = local(typeMembers(text, declaration.open, declaration.close))
                    val inherited = Regex("""\bextends\s+(.+)""", RegexOption.DOT_MATCHES_ALL).find(declaration.heritage)?.groupValues?.get(1)
                        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?.flatMap { base -> resolveName(base) }.orEmpty()
                    (own + inherited).distinctBy { it.name }
                }
            }
        }

        /** The first `interface Name` or `type Name = ...` declared in [scripts] of [text], or null. */
        private fun declaration(name: String): Declaration? {
            for (script in scripts) {
                val s = script.contentStart
                val e = script.contentEnd
                var i = s
                while (i < e) {
                    val c = text[i]
                    when {
                        c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, e)
                        isCommentStart(text, i, e) -> i = skipComment(text, i, e)
                        isWordAt(text, i, "interface", e) -> {
                            val n = skipTrivia(text, i + 9, e)
                            if (isWordAt(text, n, name, e)) {
                                val open = ScriptScanner.indexOf(text, "{", n, e)
                                if (open < 0) return Declaration.Interface("", -1, -1)
                                return Declaration.Interface(text.subSequence(n + name.length, open).toString(), open, findClosing(text, open, e))
                            }
                            i = n
                        }

                        isWordAt(text, i, "type", e) -> {
                            val n = skipTrivia(text, i + 4, e)
                            if (isWordAt(text, n, name, e)) {
                                var eq = skipTrivia(text, n + name.length, e)
                                if (eq < e && text[eq] == '<') {
                                    val close = findClosing(text, eq, e)
                                    eq = if (close < 0) e else skipTrivia(text, close + 1, e)
                                }
                                // `import { type Name }` and `export { type Name }` mention the name without declaring it
                                if (eq < e && text[eq] == '=') {
                                    val exprStart = skipTrivia(text, eq + 1, e)
                                    return Declaration.Alias(exprStart, ScriptScanner.memberEnd(text, exprStart, e))
                                }
                            }
                            i = n
                        }

                        else -> i++
                    }
                }
            }
            return null
        }

        private fun resolveName(expression: String): List<ScriptScanner.TypeMember> {
            val name = expression.takeWhile { isIdentPart(it) || it == '.' }
            return if (name.isEmpty()) emptyList() else namedMembers(name).orEmpty()
        }

        private fun ScriptScanner.TypeMember.copy(optional: Boolean) = ScriptScanner.TypeMember(name, nameStart, optional, type, isMethod, doc, file)
    }

    /** A named type declared in a script: an interface ([open] / [close] of its body, -1 when missing) or a type alias. */
    private sealed class Declaration {
        class Interface(val heritage: String, val open: Int, val close: Int) : Declaration()

        /** `type Name = <expression>`, the expression spanning [start] until [end]. */
        class Alias(val start: Int, val end: Int) : Declaration()
    }

    /** Types that are never aliases, so member types naming them need no lookup. */
    private val BUILT_IN_TYPES = setOf(
        "string", "number", "boolean", "bigint", "symbol", "object", "any", "unknown", "never", "void", "null", "undefined",
        "true", "false", "String", "Number", "Boolean", "Object", "Function", "Array", "Date", "RegExp", "Promise", "Map", "Set",
        "Element", "HTMLElement", "Event",
    )

    /**
     * Named imports and re-exports of a module (local name to imported name and specifier), its `export *` specifiers and its
     * local export aliases (`export { A as B }`: exported name to local name).
     */
    private class Imports(val named: Map<String, Pair<String, String>>, val star: List<String>, val aliases: Map<String, String>)

    private val NAMED_IMPORT = Regex("""\b(?:import|export)\s+(?:type\s+)?(?:[A-Za-z_$][\w$]*\s*,\s*)?\{([^}]*)}\s*from\s*(['"])([^'"\n]+)\2""")
    private val STAR_EXPORT = Regex("""\bexport\s+(?:type\s+)?\*\s+from\s*(['"])([^'"\n]+)\1""")
    private val LOCAL_EXPORT = Regex("""\bexport\s+(?:type\s+)?\{([^}]*)}(?!\s*from\b)""")

    /**
     * `import type { A, B as C } from '...'`, `import { type A } from '...'`, `export { A as B } from '...'`,
     * `export * from '...'` and `export { A as B }` in [scripts] of [text], outside comments.
     */
    private fun findImports(text: CharSequence, scripts: List<Block>): Imports {
        val named = HashMap<String, Pair<String, String>>()
        val star = ArrayList<String>()
        val aliases = HashMap<String, String>()
        for (script in scripts) {
            val code = withoutComments(text, script.contentStart, script.contentEnd)
            for (match in NAMED_IMPORT.findAll(code)) {
                val specifier = match.groupValues[3]
                for ((imported, local) in specifiers(match.groupValues[1])) named.putIfAbsent(local, imported to specifier)
            }
            STAR_EXPORT.findAll(code).mapTo(star) { it.groupValues[2] }
            for (match in LOCAL_EXPORT.findAll(code)) {
                for ((local, exported) in specifiers(match.groupValues[1])) if (local != exported) aliases.putIfAbsent(exported, local)
            }
        }
        return Imports(named, star, aliases)
    }

    /** `A, type B as C` -> (A, A), (B, C). */
    private fun specifiers(list: String): List<Pair<String, String>> = list.split(',').mapNotNull { entry ->
        val parts = entry.trim().removePrefix("type ").trim().split(Regex("""\s+as\s+"""))
        val original = parts[0].trim()
        val renamed = parts.getOrNull(1)?.trim() ?: original
        if (original.isNotEmpty() && renamed.isNotEmpty() && original.all(::isIdentPart) && renamed.all(::isIdentPart)) original to renamed else null
    }

    /** The text of [start]..[end] with comments replaced by spaces (strings are kept, so `'//'` in a specifier is no comment). */
    private fun withoutComments(text: CharSequence, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val e = skipString(text, i, end)
                    builder.append(text, i, e)
                    i = e
                }

                isCommentStart(text, i, end) -> {
                    val e = skipComment(text, i, end)
                    repeat(e - i) { builder.append(' ') }
                    i = e
                }

                else -> {
                    builder.append(c)
                    i++
                }
            }
        }
        return builder.toString()
    }

    // ---- Slots ------------------------------------------------------------------------------------------------------

    private val SLOT_ATTRIBUTE = Regex("""(?:^|\s)((?:v-bind)?:?name)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun collectTemplateSlots(text: CharSequence, template: Block, into: MutableMap<String, ComarkSlotInfo>) {
        var i = template.contentStart
        val end = template.contentEnd
        var comment: String? = null
        var commentEnd = -1
        while (i < end) {
            val lt = indexOfChar(text, '<', i)
            if (lt < 0 || lt >= end) return
            if (startsWith(text, lt, "<!--")) {
                val close = ScriptScanner.indexOf(text, "-->", lt + 4, end)
                comment = text.subSequence(lt + 4, if (close < 0) end else close).toString().trim()
                i = if (close < 0) end else close + 3
                commentEnd = i
                continue
            }
            if (!isTag(text, lt + 1, "slot")) {
                i = lt + 1
                continue
            }
            val tagEnd = findTagEnd(text, lt + 5)
            if (tagEnd < 0 || tagEnd > end) return
            val attributes = text.subSequence(lt + 5, tagEnd).toString()
            val description = comment?.takeIf { commentEnd >= 0 && text.subSequence(commentEnd, lt).isBlank() }
                ?.removePrefix("@slot")?.trim()?.ifEmpty { null }
            val match = SLOT_ATTRIBUTE.find(attributes)
            when {
                match == null -> into.putIfAbsent("default", ComarkSlotInfo("default", description, offset = lt + 1))
                match.groupValues[1] == "name" -> {
                    val group = if (match.groups[2] != null) 2 else 3
                    val value = match.groups[group]!!
                    if (value.value.isNotBlank()) {
                        into.putIfAbsent(value.value, ComarkSlotInfo(value.value, description, offset = lt + 5 + value.range.first))
                    }
                }
                // dynamic `:name="..."` slots have no static name
            }
            comment = null
            i = tagEnd + 1
        }
    }

    private fun collectScriptSlots(
        text: CharSequence,
        script: Block,
        scripts: List<Block>,
        types: TypeContext,
        into: MutableMap<String, ComarkSlotInfo>,
    ) {
        for (call in findCalls(text, script.contentStart, script.contentEnd, "defineSlots")) {
            if (call.typeOpen < 0) continue
            TypeResolver(text, scripts, null, types).members(call.typeOpen + 1, call.typeClose).forEach { into.putIfAbsent(it.name, slotOf(it)) }
        }
        var from = script.contentStart
        while (true) {
            val at = ScriptScanner.indexOf(text, "SlotsType<", from, script.contentEnd)
            if (at < 0) break
            val open = at + "SlotsType".length
            val close = findClosing(text, open, script.contentEnd)
            if (close < 0) break
            TypeResolver(text, scripts, null, types).members(open + 1, close).forEach { into.putIfAbsent(it.name, slotOf(it)) }
            from = close
        }
    }

    /** A slot declared as a type member; slots of imported types have no position in the component file. */
    private fun slotOf(member: ScriptScanner.TypeMember) = ComarkSlotInfo(
        member.name,
        member.doc?.let(::parseDoc)?.description,
        member.type,
        if (member.file == null) member.nameStart else -1,
    )

    private val SLOTS_REFERENCE = Regex("""\${'$'}slots\s*(?:\.\s*([A-Za-z_$][\w$]*)|\[\s*['"]([^'"]+)['"]\s*])""")
    private val USE_SLOTS = Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*useSlots\s*\(\s*\)""")

    /** Slots a component checks for (`v-if="$slots.footer"`, `slots.footer?.()`) even when it renders them indirectly. */
    private fun collectSlotReferences(text: CharSequence, blocks: List<Block>, into: MutableMap<String, ComarkSlotInfo>) {
        val variables = blocks.filter { it.tag == "script" }.flatMap { b ->
            USE_SLOTS.findAll(text.subSequence(b.contentStart, b.contentEnd)).map { it.groupValues[1] }.toList()
        }
        val patterns = listOf(SLOTS_REFERENCE) + variables.map { v ->
            Regex("""(?<![\w$.])${Regex.escape(v)}\s*(?:\.\s*([A-Za-z_$][\w$]*)|\[\s*['"]([^'"]+)['"]\s*])""")
        }
        for (block in blocks) {
            val content = text.subSequence(block.contentStart, block.contentEnd)
            for (pattern in patterns) {
                for (match in pattern.findAll(content)) {
                    val group = match.groups[1] ?: match.groups[2] ?: continue
                    into.putIfAbsent(group.value, ComarkSlotInfo(group.value, offset = block.contentStart + group.range.first))
                }
            }
        }
    }

    // ---- Description ------------------------------------------------------------------------------------------------

    private fun describe(text: CharSequence, blocks: List<Block>): String? {
        val scripts = blocks.filter { it.tag == "script" }
        for (script in scripts) docBeforeDefinition(text, script)?.let { return it }
        blocks.firstOrNull()?.comment?.let(::cleanComment)?.let { return it }
        for (script in scripts.sortedByDescending { it.isSetup }) leadingDoc(text, script)?.let { return it }
        blocks.firstOrNull { it.tag == "template" }?.let { template ->
            val s = skipWhitespace(text, template.contentStart, template.contentEnd)
            if (startsWith(text, s, "<!--")) {
                val close = ScriptScanner.indexOf(text, "-->", s + 4, template.contentEnd)
                if (close > 0) cleanComment(text.subSequence(s + 4, close).toString())?.let { return it }
            }
        }
        return blocks.firstOrNull { it.tag == "docs" }?.let { text.subSequence(it.contentStart, it.contentEnd).toString().trimIndent().trim() }
            ?.ifEmpty { null }
    }

    /** A JSDoc right before `export default`, `defineComponent(` or `defineOptions(`. */
    private fun docBeforeDefinition(text: CharSequence, script: Block): String? {
        var i = script.contentStart
        val end = script.contentEnd
        var lastDoc: String? = null
        var lastDocEnd = -1
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                startsWith(text, i, "/**") -> {
                    val e = skipComment(text, i, end)
                    lastDoc = text.subSequence(i, e).toString()
                    lastDocEnd = e
                    i = e
                }

                isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                isWordAt(text, i, "export", end) || isWordAt(text, i, "defineComponent", end) || isWordAt(text, i, "defineOptions", end) -> {
                    if (lastDoc != null && text.subSequence(lastDocEnd, i).isBlank()) return descriptionOf(lastDoc)
                    i++
                }

                else -> i++
            }
        }
        return null
    }

    /** The JSDoc a script starts with, unless it documents the declaration that follows it (an interface or type). */
    private fun leadingDoc(text: CharSequence, script: Block): String? {
        val s = skipWhitespace(text, script.contentStart, script.contentEnd)
        if (!startsWith(text, s, "/**")) return null
        val e = skipComment(text, s, script.contentEnd)
        val next = skipWhitespace(text, e, script.contentEnd)
        if (isWordAt(text, next, "interface") || isWordAt(text, next, "type") || isWordAt(text, next, "export") && !startsWith(text, next, "export default")) {
            return null
        }
        return descriptionOf(text.subSequence(s, e).toString())
    }

    private fun descriptionOf(comment: String): String? {
        if (comment.contains("@license") || comment.contains("Copyright", ignoreCase = false)) return null
        return parseDoc(comment).description
    }

    private fun cleanComment(comment: String): String? =
        comment.lines().filterNot { it.trim().startsWith("@") }.joinToString("\n") { it.trim() }.trim().ifEmpty { null }

    // ---- Helpers ----------------------------------------------------------------------------------------------------

    /** A call `name<TypeArgs>(args)`: offsets of the brackets, -1 when absent. */
    internal class Call(val nameStart: Int, val typeOpen: Int, val typeClose: Int, val argsOpen: Int, val argsClose: Int)

    internal fun findCalls(text: CharSequence, start: Int, end: Int, name: String): List<Call> {
        val calls = ArrayList<Call>()
        var i = start
        while (i < end) {
            ProgressManager.checkCanceled()
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                isWordAt(text, i, name, end) -> {
                    var j = skipTrivia(text, i + name.length, end)
                    var typeOpen = -1
                    var typeClose = -1
                    if (j < end && text[j] == '<') {
                        val close = findClosing(text, j, end)
                        if (close > 0) {
                            typeOpen = j
                            typeClose = close
                            j = skipTrivia(text, close + 1, end)
                        }
                    }
                    if (j < end && text[j] == '(') {
                        val close = findClosing(text, j, end)
                        calls += Call(i, typeOpen, typeClose, j, if (close < 0) end else close)
                        i = j + 1
                    } else {
                        i += name.length
                    }
                }

                else -> i++
            }
        }
        return calls
    }

    /** Offset of the `{` / `[` initialising `const name = ...` in any script block. */
    private fun findConstant(text: CharSequence, scripts: List<Block>, name: String): Int? {
        for (script in scripts) {
            val match = Regex("""\b(?:const|let|var)\s+${Regex.escape(name)}\s*(?::[^=]+)?=\s*([{\[])""")
                .find(text.subSequence(script.contentStart, script.contentEnd)) ?: continue
            return script.contentStart + match.groups[1]!!.range.first
        }
        return null
    }

    /** `String` -> `string`, `[String, Number]` -> `string | number`, `Array as PropType<Item[]>` -> `Item[]`. */
    internal fun constructorType(value: String): String {
        val v = value.trim()
        val propType = v.indexOf("PropType<")
        if (propType >= 0) {
            val open = propType + "PropType".length
            val close = findClosing(v, open, v.length)
            if (close > 0) return normalize(v.substring(open + 1, close))
        }
        if (v.startsWith("[")) {
            val close = findClosing(v, 0, v.length)
            val inner = if (close > 0) v.substring(1, close) else v.drop(1)
            return inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ") { constructorType(it) }
        }
        val base = v.substringBefore(" as ").trim()
        return when (base) {
            "String" -> "string"
            "Number" -> "number"
            "Boolean" -> "boolean"
            "Array" -> "any[]"
            "Object" -> "object"
            "Function" -> "function"
            "Symbol" -> "symbol"
            "BigInt" -> "bigint"
            "null", "undefined" -> "any"
            else -> normalize(base)
        }
    }

    /** `() => []` -> `[]`, `() => ({ a: 1 })` -> `{ a: 1 }`; other values only get their whitespace collapsed. */
    internal fun simplifyDefault(value: String): String {
        var v = normalize(value)
        val arrow = Regex("""^(?:\(\s*\)|_)\s*=>\s*(.+)$""").find(v)
        if (arrow != null) {
            val body = arrow.groupValues[1].trim()
            if (!body.startsWith("{")) {
                v = if (body.startsWith("(") && body.endsWith(")") && findClosing(body, 0, body.length) == body.length - 1) {
                    body.substring(1, body.length - 1).trim()
                } else {
                    body
                }
            }
        }
        return if (v.length > 120) v.take(117) + "..." else v
    }

    /** Index of the first `=` that is not part of `==`, `=>`, `>=`, `<=`, `!=` at the top level of [entry], or -1. */
    private fun topLevelEquals(entry: String): Int {
        var i = 0
        while (i < entry.length) {
            val c = entry[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    i = skipString(entry, i, entry.length)
                    continue
                }

                c == '{' || c == '(' || c == '[' -> {
                    val close = findClosing(entry, i, entry.length)
                    if (close < 0) return -1
                    i = close + 1
                    continue
                }

                c == '=' -> {
                    val next = entry.getOrNull(i + 1)
                    val previous = entry.getOrNull(i - 1)
                    if (next != '=' && next != '>' && previous != '=' && previous != '!' && previous != '<' && previous != '>') return i
                }
            }
            i++
        }
        return -1
    }

    private fun ComarkProp.withDefault(default: String) = ComarkProp(name, type, required, default, description, offset, file)

    /** How deeply type expressions may nest (aliases of aliases, imports of imports) before resolution gives up. */
    private const val MAX_TYPE_DEPTH = 8

    /** The content of the complete string literal spanning [range] of [text], or null when it is no (complete) string literal. */
    private fun quoted(text: CharSequence, range: IntRange): String? {
        if (range.last <= range.first || range.last >= text.length) return null
        val quote = text[range.first]
        if (quote != '"' && quote != '\'' && quote != '`' || text[range.last] != quote) return null
        return text.subSequence(range.first + 1, range.last).toString()
    }

    private fun indexOfChar(text: CharSequence, c: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == c) return i
        return -1
    }

    private fun indexOfIgnoreCase(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        outer@ while (i + needle.length <= text.length) {
            for (k in needle.indices) {
                if (!text[i + k].equals(needle[k], ignoreCase = true)) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun skipWhitespace(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to && text[i].isWhitespace()) i++
        return i
    }
}
