package cz.xprees.comark.images

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener

/**
 * Remembers the element a running Rename refactoring renames, for [ComarkImageReference.handleElementRename], which is told only the
 * new name. A usage whose element was reparsed by an earlier edit (image paths in one paragraph) is renamed through a fresh
 * `PsiMultiReference` instead of the bindable reference the search found, so the rename of a directory (`threats` -> `risks`) arrives
 * as a plain new name that must replace the directory's segment of the path, not the file name.
 */
class ComarkImageRenameTarget(private val project: Project) : RefactoringEventListener {
    override fun refactoringStarted(refactoringId: String, beforeData: RefactoringEventData?) {
        if (refactoringId == RENAME) project.putUserData(KEY, beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY))
    }

    override fun refactoringDone(refactoringId: String, afterData: RefactoringEventData?) {
        if (refactoringId == RENAME) project.putUserData(KEY, null)
    }

    override fun undoRefactoring(refactoringId: String) {
        if (refactoringId == RENAME) project.putUserData(KEY, null)
    }

    companion object {
        private const val RENAME = "refactoring.rename"
        private val KEY = Key.create<PsiElement>("comark.image.renamed")

        /** The element the running Rename refactoring of [project] renames, or null. */
        fun of(project: Project): PsiElement? = project.getUserData(KEY)?.takeIf { it.isValid }
    }
}
