package cz.xprees.comark.fixup

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.ComarkTestUtil

/**
 * Code fences indented inside components must be injected without platform errors (they used to log "After patch" errors), with
 * the same content the Markdown plugin injects for an equally indented fence in a list item (indentation kept, no final line break).
 */
class IndentedFenceInjectionTest : BasePlatformTestCase() {
    private fun check(text: String, expectedContent: String) {
        val file = myFixture.configureByText("test.mdc", text)
        ComarkTestUtil.assertConsistent(file)
        myFixture.doHighlighting()
        val offset = file.text.indexOf("const a")
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)
        assertNotNull("fence content must be injected", injected)
        assertEquals(expectedContent, injected!!.containingFile.text)
    }

    fun testFenceIndentedInNestedComponent() =
        check("::outer\n  :::inner\n  ```js\n  const a = 1\n  ```\n  :::\n::\n", "  const a = 1")

    fun testFenceIndentedTwoLevels() =
        check("::outer\n  :::inner\n    ```js\n    const a = 1\n    const b = 2\n    ```\n  :::\n::\n", "    const a = 1\n    const b = 2")

    fun testFenceInsideIndentedComponentWithCaret() {
        myFixture.configureByText("test.mdc", "::outer\n  :::inner\n  ```js\n  const a<caret> = 1\n  ```\n  :::\n::\n")
        myFixture.doHighlighting()
        myFixture.type("2")
        myFixture.doHighlighting()
    }
}
