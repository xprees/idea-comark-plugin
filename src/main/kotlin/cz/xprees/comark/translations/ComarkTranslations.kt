package cz.xprees.comark.translations

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import cz.xprees.comark.format.ComarkCollections
import cz.xprees.comark.format.JsScanner
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.lang.ComarkProjectDetector
import cz.xprees.comark.settings.ComarkSettings
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translations of Comark content pages: localized content kept in one directory per locale (`content/pages/en/about.md` next to
 * `content/pages/cs/o-nas.md`).
 *
 * **Locale directories.** A directory is a locale directory when its name is a locale code and either the locales are known (the
 * *Translations › Locales* setting, else the `i18n.locales` of the package's `nuxt.config.*`) and list it, or at least one sibling
 * directory is a locale code too (ISO 639-1, optionally with a region or script: `en`, `pt-br`, `zh-hant`). The nearest such
 * directory above a page is its locale; its parent is the *group* whose locale directories are the page's translations. The
 * group's locales are its locale directories plus the known locales, so a locale without a directory yet still has a page missing.
 *
 * **Counterparts.** The translation of a page in another locale is, in this order:
 * 1. the page at the same path below that locale's directory (`threats/en/01.phishing.md` and `threats/cs/01.phishing.md`);
 * 2. otherwise the pages a front matter key names (*Translations › Front matter keys*, `counterpart` by default; a stem, slug or
 *    path, compared without ordering prefixes and extension), in either direction: this page naming the other, or the other
 *    naming this page;
 * 3. otherwise the pages a string map in `content.config.*` pairs this page with (`{ about: 'o-nas', terms: 'podminky' }`), when
 *    one side names this page and the other a page of that locale.
 *
 * Nothing is guessed beyond that: a locale with no such page has the page missing, and every match is listed when several match.
 */
@Service(Service.Level.PROJECT)
class ComarkTranslations(private val project: Project) {
    /** A page of a locale directory: the file, its path below the directory, and what identifies it to other locales. */
    internal class Page(val file: VirtualFile, val relativePath: String, val keys: Set<String>, val names: List<String>)

    /** The locale directories of a group and their pages, plus the string maps of the content configuration. */
    internal class Group(val directory: VirtualFile, val locales: Map<String, VirtualFile?>, val pages: Map<String, List<Page>>, val pairs: List<Pair<String, String>>)

    /**
     * The translations of a page in [locale] (its directory [localeDirectory], the page at [relativePath] below it): for every other
     * locale of the group, the matching pages ([others], empty when the page is missing there) and that locale's directory, which
     * may not exist yet ([directories]).
     */
    class Translations(
        val file: VirtualFile,
        val locale: String,
        val localeDirectory: VirtualFile,
        val relativePath: String,
        val others: Map<String, List<VirtualFile>>,
        val directories: Map<String, String>,
    ) {
        /** The other locales in which no page matches, in order. */
        val missing: List<String> get() = others.filterValues { it.isEmpty() }.keys.toList()

        /** The other locales with at least one matching page, in order. */
        val present: List<String> get() = others.filterValues { it.isNotEmpty() }.keys.toList()
    }

    private class FrontMatter(val stamp: Long, val values: Map<String, String>)

    private val frontMatters = ConcurrentHashMap<String, FrontMatter>()

    /** Bumped when the translation settings change. */
    val settingsTracker = SimpleModificationTracker()

    /** The locale of [file] and its group directory, cheaply (no page index): null when [file] is no localized Comark page. */
    fun localeOf(file: VirtualFile): Pair<String, VirtualFile>? {
        if (file.isDirectory || file.extension?.lowercase(Locale.ROOT) !in PAGE_EXTENSIONS) return null
        if (!ComarkProjectDetector.getInstance(project).isComarkContext(file)) return null
        var directory = file.parent
        while (directory != null) {
            val parent = directory.parent ?: return null
            if (isLocaleDirectory(directory, parent)) return code(directory.name) to directory
            if (parent.findChild(MANIFEST)?.isDirectory == false) return null
            directory = parent
        }
        return null
    }

    /** The translations of [file], or null when it is no localized Comark page. */
    fun translationsOf(file: VirtualFile): Translations? {
        val (locale, localeDirectory) = localeOf(file) ?: return null
        val groupDirectory = localeDirectory.parent ?: return null
        val group = group(groupDirectory) ?: return null
        val relativePath = VfsUtilCore.getRelativePath(file, localeDirectory) ?: return null
        val current = group.pages[locale]?.firstOrNull { it.file == file } ?: page(file, relativePath)
        val others = LinkedHashMap<String, List<VirtualFile>>()
        val directories = LinkedHashMap<String, String>()
        for ((other, directory) in group.locales) {
            if (other == locale) continue
            directories[other] = directory?.path ?: "${groupDirectory.path}/$other"
            others[other] = counterparts(current, group.pages[other].orEmpty(), group.pairs)
        }
        if (others.isEmpty()) return null
        return Translations(file, locale, localeDirectory, relativePath, others, directories)
    }

    private fun counterparts(page: Page, candidates: List<Page>, pairs: List<Pair<String, String>>): List<VirtualFile> {
        candidates.firstOrNull { it.relativePath == page.relativePath }?.let { return listOf(it.file) }
        val result = LinkedHashSet<VirtualFile>()
        // front matter keys, both ways
        for (name in page.names) candidates.filter { name in it.keys }.forEach { result += it.file }
        for (candidate in candidates) if (candidate.names.any { it in page.keys }) result += candidate.file
        // string maps of the content configuration
        for ((a, b) in pairs) {
            if (a in page.keys) candidates.filter { b in it.keys }.forEach { result += it.file }
            if (b in page.keys) candidates.filter { a in it.keys }.forEach { result += it.file }
        }
        return result.toList()
    }

    private fun group(directory: VirtualFile): Group? {
        val psiDirectory = PsiManager.getInstance(project).findDirectory(directory) ?: return null
        return CachedValuesManager.getCachedValue(psiDirectory) {
            val dependencies = mutableListOf<Any>(
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                PsiModificationTracker.getInstance(project).forLanguage(ComarkLanguage),
                settingsTracker,
            )
            val configuration = ComarkCollections.getInstance(project).configurationAt(directory)
            configuration?.first?.let { dependencies += it }
            nuxtConfig(directory)?.let { dependencies += it }
            CachedValueProvider.Result.create(buildGroup(directory, configuration?.first), *dependencies.toTypedArray())
        }
    }

    private fun buildGroup(directory: VirtualFile, contentConfig: VirtualFile?): Group {
        val locales = LinkedHashMap<String, VirtualFile?>()
        for (child in directory.children.sortedBy { it.name }) {
            if (child.isDirectory && isLocaleDirectory(child, directory)) locales[code(child.name)] = child
        }
        for (known in knownLocales(directory)) locales.putIfAbsent(known, null)
        val pages = locales.mapValues { (_, localeDirectory) ->
            val result = ArrayList<Page>()
            if (localeDirectory != null) collect(localeDirectory, localeDirectory, result)
            result
        }
        return Group(directory, locales, pages, contentConfig?.let { stringPairs(it) }.orEmpty())
    }

    private fun collect(root: VirtualFile, directory: VirtualFile, into: MutableList<Page>) {
        ProgressManager.checkCanceled()
        for (child in directory.children) {
            if (child.isDirectory) {
                if (child.name != NODE_MODULES && !child.name.startsWith(".")) collect(root, child, into)
            } else if (child.extension?.lowercase(Locale.ROOT) in PAGE_EXTENSIONS) {
                VfsUtilCore.getRelativePath(child, root)?.let { into += page(child, it) }
            }
        }
    }

    private fun page(file: VirtualFile, relativePath: String): Page {
        val values = frontMatter(file)
        val keys = LinkedHashSet<String>()
        val stem = relativePath.substringBeforeLast('.')
        keys += normalize(stem)
        keys += normalize(stem.substringAfterLast('/'))
        values["slug"]?.let { keys += normalize(it) }
        values["path"]?.let { keys += normalize(it.substringAfterLast('/')) }
        keys.remove("")
        val names = counterpartKeys().mapNotNull { values[it] }.flatMap { listOf(normalize(it), normalize(it.substringAfterLast('/'))) }
            .filter { it.isNotEmpty() }.distinct()
        return Page(file, relativePath, keys, names)
    }

    /** Top-level scalar values of the front matter of [file], read from its document when open; cached per modification stamp. */
    private fun frontMatter(file: VirtualFile): Map<String, String> {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        frontMatters[file.path]?.takeIf { it.stamp == stamp }?.let { return it.values }
        val text = document?.charsSequence?.toString() ?: try {
            VfsUtilCore.loadText(file, 8192)
        } catch (_: Exception) {
            ""
        }
        val values = parseFrontMatter(text)
        frontMatters[file.path] = FrontMatter(stamp, values)
        return values
    }

    private fun isLocaleDirectory(directory: VirtualFile, parent: VirtualFile): Boolean {
        val name = directory.name
        if (!LocaleCodes.isCode(name)) return false
        val known = knownLocales(parent)
        if (known.isNotEmpty()) return code(name) in known
        return parent.children.any { it != directory && it.isDirectory && LocaleCodes.isCode(it.name) }
    }

    /** The configured locales, else the `i18n.locales` of the package's Nuxt configuration; empty when neither names any. */
    private fun knownLocales(directory: VirtualFile): Set<String> {
        val configured = parseList(ComarkSettings.getInstance().translationLocales).map(::code)
        if (configured.isNotEmpty()) return configured.toSet()
        val config = nuxtConfig(directory) ?: return emptySet()
        val stamp = config.modificationStamp
        nuxtLocales[config.path]?.takeIf { it.first == stamp }?.let { return it.second }
        val text = try {
            VfsUtilCore.loadText(config)
        } catch (_: Exception) {
            ""
        }
        val locales = i18nLocales(text).map(::code).toSet()
        nuxtLocales[config.path] = stamp to locales
        return locales
    }

    private val nuxtLocales = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    /** The `nuxt.config.*` of the package [directory] belongs to (next to the nearest `package.json` at or above it). */
    private fun nuxtConfig(directory: VirtualFile): VirtualFile? {
        var current: VirtualFile? = directory
        while (current != null) {
            if (current.findChild(MANIFEST)?.isDirectory == false) {
                return current.children.firstOrNull { !it.isDirectory && NUXT_CONFIG.matches(it.name) }
            }
            current = current.parent
        }
        return null
    }

    private fun stringPairs(config: VirtualFile): List<Pair<String, String>> = try {
        stringMaps(VfsUtilCore.loadText(config))
    } catch (_: Exception) {
        emptyList()
    }

    private fun counterpartKeys(): List<String> = parseList(ComarkSettings.getInstance().translationKeys)

    companion object {
        private const val MANIFEST = "package.json"
        private const val NODE_MODULES = "node_modules"
        private val PAGE_EXTENSIONS = setOf("md", "mdc", "markdown")
        private val NUXT_CONFIG = Regex("""nuxt\.config\.(?:ts|js|mjs|mts|cjs|cts)""")
        private val ORDERING_PREFIX = Regex("""^\d+\.""")
        private val LOCALES = Regex("""\blocales\s*:\s*\[""")
        private val CODE = Regex("""\bcode\s*:\s*(['"`])([^'"`]+)\1""")
        private val STRING = Regex("""(['"`])([^'"`$]*)\1""")
        private val SCALAR = Regex("""^([A-Za-z_$][\w$-]*)\s*:\s*(.*?)\s*$""")

        fun getInstance(project: Project): ComarkTranslations = project.service()

        /** A locale directory name as a locale: lower case, `_` as `-`. */
        internal fun code(name: String): String = name.lowercase(Locale.ROOT).replace('_', '-')

        /** A stem, slug or path as compared between locales: lower case, no ordering prefixes, extension or surrounding slashes. */
        internal fun normalize(value: String): String =
            value.trim().trim('/').substringBeforeLast(".md").split('/').filter { it.isNotEmpty() }
                .joinToString("/") { it.replace(ORDERING_PREFIX, "") }.lowercase(Locale.ROOT)

        /** Entries of a setting: separated by commas, spaces or lines. */
        internal fun parseList(text: String): List<String> = text.split(',', ' ', '\n', '\t', ';').map { it.trim() }.filter { it.isNotEmpty() }

        /** The locale codes of `i18n: { locales: [...] }` in a Nuxt configuration: `'en'` or `{ code: 'en', ... }` items. */
        internal fun i18nLocales(text: String): List<String> {
            val code = JsScanner.blankComments(text)
            val match = LOCALES.find(code) ?: return emptyList()
            val open = match.range.last
            val close = JsScanner.matching(code, open) ?: return emptyList()
            val result = ArrayList<String>()
            for (item in JsScanner.items(code, open, close)) {
                val itemText = code.substring(item.first, item.last + 1).trim()
                if (itemText.startsWith("{")) {
                    CODE.find(itemText)?.groupValues?.get(2)?.let { result += it }
                } else {
                    STRING.matchEntire(itemText)?.groupValues?.get(2)?.let { result += it }
                }
            }
            return result.filter { LocaleCodes.isCode(it) }
        }

        /**
         * The entries of the object literals in [text] whose values are all plain strings and that have at least two entries
         * (`{ about: 'o-nas', terms: 'podminky' }`), normalized; candidates for page pairs, used only where both sides name pages.
         */
        internal fun stringMaps(text: String): List<Pair<String, String>> {
            val code = JsScanner.blankComments(text)
            val result = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < code.length) {
                val c = code[i]
                if (c == '\'' || c == '"' || c == '`') {
                    i = JsScanner.skipStringAt(code, i)
                    continue
                }
                if (c == '{') {
                    val close = JsScanner.matching(code, i)
                    if (close != null) {
                        val entries = JsScanner.entries(code, i, close)
                        val values = entries.mapValues { (_, range) -> JsScanner.string(code, range) }
                        if (values.size >= 2 && values.values.all { it != null && '$' !in it }) {
                            values.forEach { (key, value) -> result += normalize(key) to normalize(value!!) }
                        }
                    }
                }
                i++
            }
            return result.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        }

        /** Top-level `key: value` scalars of the front matter at the start of [text] (quotes removed, comments dropped). */
        internal fun parseFrontMatter(text: String): Map<String, String> {
            val lines = text.removePrefix("﻿").lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trimEnd() != "---") return emptyMap()
            val result = LinkedHashMap<String, String>()
            while (lines.hasNext()) {
                val line = lines.next()
                val trimmed = line.trimEnd()
                if (trimmed == "---" || trimmed == "...") return result
                if (line.isEmpty() || line[0].isWhitespace()) continue
                val match = SCALAR.matchEntire(line) ?: continue
                var value = match.groupValues[2]
                if (value.startsWith("'") || value.startsWith("\"")) {
                    val quote = value[0]
                    val end = value.indexOf(quote, 1)
                    value = if (end > 0) value.substring(1, end) else value.substring(1)
                } else {
                    value = value.substringBefore(" #")
                }
                if (value.isNotEmpty() && value != "|" && value != ">") result.putIfAbsent(match.groupValues[1], value.trim())
            }
            return emptyMap()
        }
    }
}
