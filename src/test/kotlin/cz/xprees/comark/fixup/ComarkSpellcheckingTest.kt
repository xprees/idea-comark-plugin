package cz.xprees.comark.fixup

import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.spellchecker.tokenizer.LanguageSpellchecking
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.spellchecker.ComarkSpellcheckingStrategy

/**
 * The concrete spell checking inspection lives in the Grazie plugin, which the tests don't load, so this checks the strategy itself:
 * which Comark tokens it hands to the spellchecker, and that it is the strategy registered for Comark.
 */
class ComarkSpellcheckingTest : BasePlatformTestCase() {
    private val strategy = ComarkSpellcheckingStrategy()

    private fun checkedTexts(text: String): List<String> {
        val file = myFixture.configureByText("test.mdc", text)
        return SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null && it.textLength > 0 }
            .filter { it.language == ComarkLanguage && strategy.getTokenizer(it) !== SpellcheckingStrategy.EMPTY_TOKENIZER }
            .map(PsiElement::getText).toList()
    }

    fun testRegisteredForComark() {
        assertTrue(LanguageSpellchecking.INSTANCE.allForLanguage(ComarkLanguage).any { it is ComarkSpellcheckingStrategy })
    }

    fun testContentAndPlainValuesAreChecked() {
        val checked = checkedTexts("::alertt[Helllo wrld]{title=\"Missspelled titel\" .clss}\n::\n")
        assertEquals(listOf("Helllo wrld", "\"Missspelled titel\""), checked)
    }

    fun testBindingsAndNamesAreNotChecked() {
        assertEquals(emptyList<String>(), checkedTexts("::cardd{:colr=\"props.varint\" #idd bool}\n#slott\nText\n::\n"))
    }
}
