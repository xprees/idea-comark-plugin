package cz.xprees.comark.lang

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.ComarkIcons
import javax.swing.Icon

/**
 * Shows the Comark icon for `.mdc` files. They keep the Markdown file type (so every Markdown feature applies), which would otherwise
 * give them the Markdown icon. `.md` files that are Comark keep the Markdown icon: they are still Markdown files to everything else.
 */
class ComarkFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (!file.isDirectory && file.extension.equals(ComarkLanguageSubstitutor.MDC_EXTENSION, ignoreCase = true)) ComarkIcons.FILE else null
}
