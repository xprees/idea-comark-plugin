package cz.xprees.comark.components

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComponentIndex
import cz.xprees.comark.settings.ComarkSettings

/** Fixture project with a few Nuxt components; settings are restored after each test. */
abstract class ComponentsTestBase : BasePlatformTestCase() {
    private var savedCompletion = true
    private var savedUrl = ""
    private var savedCache = 30

    override fun setUp() {
        super.setUp()
        val settings = ComarkSettings.getInstance()
        savedCompletion = settings.componentCompletion
        savedUrl = settings.componentMetadataUrl
        savedCache = settings.componentMetadataCacheMinutes
        settings.componentCompletion = true
        settings.componentMetadataUrl = ""
    }

    override fun tearDown() {
        try {
            val settings = ComarkSettings.getInstance()
            settings.componentCompletion = savedCompletion
            settings.componentMetadataUrl = savedUrl
            settings.componentMetadataCacheMinutes = savedCache
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    protected fun addStandardComponents() {
        myFixture.addFileToProject("components/content/Alert.vue", ALERT)
        myFixture.addFileToProject("components/content/prose/Card.vue", CARD)
        myFixture.addFileToProject("components/Badge.vue", BADGE)
    }

    protected fun index(): ComponentIndex = ComarkComponentService.getInstance(project).index()

    protected fun configure(text: String): PsiFile = myFixture.configureByText("test.mdc", text)

    companion object {
        val ALERT = """
            <script setup lang="ts">
            /** Highlights important information. */
            const props = withDefaults(defineProps<{
              /** Visual style */
              type?: 'info' | 'warning' | 'danger'
              /** Heading shown above the content */
              title: string
              dismissible?: boolean
              count?: number
            }>(), { type: 'info' })
            </script>

            <template>
              <div class="alert">
                <slot name="title" />
                <slot />
              </div>
            </template>
        """.trimIndent()

        val CARD = """
            <!-- A card with an optional footer. -->
            <template>
              <section>
                <slot />
                <footer><slot name="footer" /></footer>
              </section>
            </template>
            <script setup>
            defineProps({
              elevated: { type: Boolean, default: false },
              items: { type: Array, required: true },
            })
            </script>
        """.trimIndent()

        val BADGE = """
            <script setup lang="ts">
            defineProps<{ color?: string; label: string }>()
            </script>
            <template><span><slot /></span></template>
        """.trimIndent()
    }
}
