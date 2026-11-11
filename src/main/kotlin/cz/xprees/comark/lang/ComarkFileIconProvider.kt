package cz.xprees.comark.lang

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.ComarkIcons
import org.intellij.plugins.markdown.lang.hasMarkdownType
import javax.swing.Icon

/**
 * Shows the Comark icon for Comark files: `.mdc` files, and the Markdown files the plugin treats as Comark (see
 * [ComarkLanguageSubstitutor.isComarkFile]). They keep the Markdown file type, so every Markdown feature applies, which would otherwise
 * give them the Markdown icon.
 */
class ComarkFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isDirectory || !file.hasMarkdownType()) return null
        if (file.extension.equals(ComarkLanguageSubstitutor.MDC_EXTENSION, ignoreCase = true)) return ComarkIcons.FILE
        if (project == null || project.isDisposed) return null
        return if (ComarkLanguageSubstitutor.isComarkFile(file, project)) ComarkIcons.FILE else null
    }
}
