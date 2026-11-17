package cz.xprees.comark.translations

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.settings.ComarkSettings
import java.util.Locale

/**
 * Creates a page's translation in a locale where it is missing: a copy of the page at the path the user confirms below that
 * locale's directory (the same path by default). When the new file's name differs from the page's, its front matter gets the
 * first *Front matter keys* entry (`counterpart`) naming the page, so the two stay paired.
 */
internal object ComarkTranslationCreator {
    private val PAGE = Regex("""^[^/].*\.(?:md|mdc|markdown)$""", RegexOption.IGNORE_CASE)

    /** Asks for the path below [locale]'s directory and creates the translation there; opens it. */
    fun promptAndCreate(project: Project, translations: ComarkTranslations.Translations, locale: String) {
        val directory = translations.directories[locale] ?: return
        val label = locale.uppercase(Locale.ROOT)
        val relative = Messages.showInputDialog(
            project,
            ComarkBundle.message("translations.create.prompt", label, directory),
            ComarkBundle.message("translations.create.title", label),
            null,
            translations.relativePath,
            object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? = validate(translations.file.fileSystem, directory, inputString)
                override fun checkInput(inputString: String) = validate(translations.file.fileSystem, directory, inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
            },
        ) ?: return
        val created = create(project, translations.file, directory, relative.trim()) ?: return
        ComarkTranslationNavigation.open(project, created, sideBySide = false)
    }

    /** Why [relative] (below [directory] in [fileSystem]) cannot be created, or null. */
    internal fun validate(fileSystem: VirtualFileSystem, directory: String, relative: String): String? {
        val path = relative.trim()
        return when {
            !PAGE.matches(path) || path.split('/').any { it == ".." || it.isEmpty() } -> ComarkBundle.message("translations.create.invalid")
            fileSystem.findFileByPath("$directory/$path") != null -> ComarkBundle.message("translations.create.exists")
            else -> null
        }
    }

    /** Creates the translation of [source] at [relative] below [directory] (one undoable command); null when it cannot. */
    internal fun create(project: Project, source: VirtualFile, directory: String, relative: String): VirtualFile? {
        if (validate(source.fileSystem, directory, relative) != null) return null
        val text = FileDocumentManager.getInstance().getDocument(source)?.text ?: VfsUtilCore.loadText(source)
        val name = relative.substringAfterLast('/')
        val content = counterpartFor(source, name)?.let { (key, value) -> withFrontMatterValue(text, key, value) } ?: text
        return WriteCommandAction.writeCommandAction(project).withName(ComarkBundle.message("translations.create.command")).compute<VirtualFile?, RuntimeException> {
            val base = source.fileSystem.findFileByPath(directory) ?: createDirectories(source, directory) ?: return@compute null
            val parentPath = relative.substringBeforeLast('/', "")
            val parent = if (parentPath.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, parentPath) ?: return@compute null
            val psiDirectory = PsiManager.getInstance(project).findDirectory(parent) ?: return@compute null
            val file = PsiFileFactory.getInstance(project).createFileFromText(name, source.fileType, content)
            (psiDirectory.add(file) as? PsiFile)?.virtualFile
        }
    }

    private fun createDirectories(source: VirtualFile, path: String): VirtualFile? {
        // the locale directory may not exist yet: create it below the nearest existing ancestor
        var existing = path.substringBeforeLast('/')
        val missing = ArrayList<String>().apply { add(path.substringAfterLast('/')) }
        while (existing.isNotEmpty() && source.fileSystem.findFileByPath(existing) == null) {
            missing.add(0, existing.substringAfterLast('/'))
            existing = existing.substringBeforeLast('/', "")
        }
        val ancestor = source.fileSystem.findFileByPath(existing) ?: return null
        return VfsUtil.createDirectoryIfMissing(ancestor, missing.joinToString("/"))
    }

    /** The front matter key and value naming [source] when its translation is called [name], or null when the names agree. */
    internal fun counterpartFor(source: VirtualFile, name: String): Pair<String, String>? {
        val key = ComarkTranslations.parseList(ComarkSettings.getInstance().translationKeys).firstOrNull() ?: return null
        val sourceStem = ComarkTranslations.normalize(source.nameWithoutExtension)
        if (ComarkTranslations.normalize(name.substringBeforeLast('.')) == sourceStem) return null
        val text = FileDocumentManager.getInstance().getDocument(source)?.text ?: VfsUtilCore.loadText(source)
        val slug = ComarkTranslations.parseFrontMatter(text)["slug"]
        return key to (slug?.let(ComarkTranslations::normalize)?.takeIf { it.isNotEmpty() } ?: sourceStem)
    }

    /** [text] with the top-level front matter [key] set to [value] (replaced, added at the end, or in a new front matter). */
    internal fun withFrontMatterValue(text: String, key: String, value: String): String {
        val line = "$key: $value"
        val lines = text.split('\n').toMutableList()
        if (lines.firstOrNull()?.trimEnd() != "---") return "---\n$line\n---\n\n$text"
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            ?: return "---\n$line\n---\n\n$text"
        val existing = (1 until end).firstOrNull { lines[it].startsWith("$key:") }
        if (existing != null) lines[existing] = line else lines.add(end, line)
        return lines.joinToString("\n")
    }
}
