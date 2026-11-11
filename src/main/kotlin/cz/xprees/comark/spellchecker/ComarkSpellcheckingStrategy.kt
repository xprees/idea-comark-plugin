package cz.xprees.comark.spellchecker

import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkAttribute

/**
 * Spellchecking of the Comark-language tokens of a Comark file (the Markdown elements in it keep the Markdown plugin's strategy).
 *
 * Prose is checked: a component's `[content]` and plain attribute values (`title="..."`). Names are not: component, slot and
 * attribute names, ids and classes are identifiers, and `:binding` values are expressions. The props YAML is left to the YAML
 * plugin's own strategy through its injection.
 */
class ComarkSpellcheckingStrategy : SpellcheckingStrategy(), DumbAware {
    override fun getTokenizer(element: PsiElement): Tokenizer<*> = when (element.elementType) {
        ComarkTypes.INLINE_CONTENT -> TEXT_TOKENIZER
        ComarkTypes.ATTR_VALUE -> if ((element.parent as? ComarkAttribute)?.isBinding == true) EMPTY_TOKENIZER else TEXT_TOKENIZER
        else -> EMPTY_TOKENIZER
    }
}
