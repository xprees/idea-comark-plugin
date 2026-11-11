package cz.xprees.comark.images

import com.intellij.openapi.ui.DialogPanel
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import cz.xprees.comark.settings.ComarkConfigurable
import cz.xprees.comark.settings.ComarkSettings
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.text.JTextComponent

/** The image settings: state, the Images group of the settings page, and what applying it does to the resolver. */
class ComarkImageSettingsTest : NuxtImagesTestBase() {
    private var configurable: ComarkConfigurable? = null

    override fun tearDown() {
        try {
            configurable?.disposeUIResources()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testDefaults() {
        val state = ComarkSettings.State()
        assertTrue(state.imagesEnabled)
        assertEquals("", state.imageDirectories)
        assertEquals("", state.imageCdnUrl)
        assertFalse(state.previewImagesFromCdn)
    }

    fun testStateRoundTrip() {
        val state = ComarkSettings.State().apply {
            imagesEnabled = false
            imageDirectories = "docs-images\n# old\nsite/static"
            imageCdnUrl = "https://cdn.example.com"
            previewImagesFromCdn = true
        }
        val copy = XmlSerializer.deserialize(XmlSerializer.serialize(state), ComarkSettings.State::class.java)
        assertFalse(copy.imagesEnabled)
        assertEquals(state.imageDirectories, copy.imageDirectories)
        assertEquals("https://cdn.example.com", copy.imageCdnUrl)
        assertTrue(copy.previewImagesFromCdn)
    }

    fun testSettersTrimAndDirectoryListDropsComments() {
        val settings = ComarkSettings.getInstance()
        settings.imageCdnUrl = "  https://cdn.example.com  "
        settings.imageDirectories = "\n docs-images \n# comment\n\n site/static\n"
        assertEquals("https://cdn.example.com", settings.imageCdnUrl)
        assertEquals(listOf("docs-images", "site/static"), settings.imageDirectoryList())
    }

    fun testImagesGroupResetsFromAndAppliesToTheSettings() {
        val settings = ComarkSettings.getInstance()
        settings.imageDirectories = "docs-images"
        val panel = panel()
        configurable().reset()
        assertTrue(checkBox(panel, ComarkConfigurable.IMAGES_ENABLED).isSelected)
        assertEquals("docs-images", text(panel, ComarkConfigurable.IMAGE_DIRECTORIES).text)
        assertEquals("", text(panel, ComarkConfigurable.IMAGE_CDN_URL).text)
        assertFalse(checkBox(panel, ComarkConfigurable.PREVIEW_FROM_CDN).isSelected)
        assertFalse(configurable().isModified)

        text(panel, ComarkConfigurable.IMAGE_DIRECTORIES).text = "site/static\ndocs-images"
        text(panel, ComarkConfigurable.IMAGE_CDN_URL).text = "//cdn.example.com"
        checkBox(panel, ComarkConfigurable.PREVIEW_FROM_CDN).isSelected = true
        assertTrue(configurable().isModified)
        configurable().apply()
        assertEquals("site/static\ndocs-images", settings.imageDirectories)
        assertEquals("//cdn.example.com", settings.imageCdnUrl)
        assertTrue(settings.previewImagesFromCdn)
        assertFalse(configurable().isModified)

        checkBox(panel, ComarkConfigurable.IMAGES_ENABLED).isSelected = false
        configurable().apply()
        assertFalse(settings.imagesEnabled)
    }

    fun testImageFieldsFollowTheEnableCheckbox() {
        val panel = panel()
        configurable().reset()
        val directories = text(panel, ComarkConfigurable.IMAGE_DIRECTORIES)
        assertTrue(directories.isEnabled)
        checkBox(panel, ComarkConfigurable.IMAGES_ENABLED).isSelected = false
        assertFalse(directories.isEnabled)
        assertFalse(text(panel, ComarkConfigurable.IMAGE_CDN_URL).isEnabled)
        assertFalse(checkBox(panel, ComarkConfigurable.PREVIEW_FROM_CDN).isEnabled)
    }

    fun testInvalidCdnUrlFailsValidation() {
        val panel = panel() as DialogPanel
        configurable().reset()
        text(panel, ComarkConfigurable.IMAGE_CDN_URL).text = "cdn.example.com"
        assertEquals(listOf("Enter an http:// or https:// URL, or a //host URL"), panel.validateAll().map { it.message })
        text(panel, ComarkConfigurable.IMAGE_CDN_URL).text = "//cdn.example.com"
        assertEmpty(panel.validateAll())
    }

    fun testCdnUrlValidation() {
        val c = configurable()
        assertTrue(c.isCdnUrl(""))
        assertTrue(c.isCdnUrl(" https://cdn.example.com/site "))
        assertTrue(c.isCdnUrl("http://localhost:3000"))
        assertTrue(c.isCdnUrl("//cdn.example.com"))
        assertFalse(c.isCdnUrl("cdn.example.com"))
        assertFalse(c.isCdnUrl("ftp://cdn.example.com"))
        assertFalse(c.isCdnUrl("//"))
        assertFalse(c.isCdnUrl("/images"))
    }

    fun testDetectedDirectoriesLine() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        val detected = component<JEditorPane>(panel(), ComarkConfigurable.DETECTED_DIRECTORIES).text
        assertTrue(detected, detected.contains("assets/images &gt; public") || detected.contains("assets/images > public"))
        assertTrue(detected, detected.contains(project.name))
    }

    fun testDetectedDirectoriesLineWithoutDirectoriesSaysNone() {
        settingsOverride("missing-dir")
        val detected = component<JEditorPane>(panel(), ComarkConfigurable.DETECTED_DIRECTORIES).text
        assertTrue(detected, detected.contains("none"))
    }

    fun testApplyInvalidatesTheResolverSoOverridesTakeEffect() {
        nuxtProject()
        image("site/public/x.png")
        image("docs-images/x.png")
        val page = page()
        assertEquals("site/public/x.png", resolved("/x.png", page))
        val before = directories().tracker.modificationCount

        val panel = panel()
        configurable().reset()
        text(panel, ComarkConfigurable.IMAGE_DIRECTORIES).text = "docs-images"
        configurable().apply()
        assertTrue(directories().tracker.modificationCount > before)
        assertEquals("docs-images/x.png", resolved("/x.png", page))
        assertEquals("docs-images", directories().detectedLabel(page))
    }

    fun testApplyWithoutImageChangesKeepsTheResolverCache() {
        nuxtProject()
        val page = page()
        directories().forFile(page)
        val before = directories().tracker.modificationCount
        panel()
        configurable().reset()
        configurable().apply()
        assertEquals(before, directories().tracker.modificationCount)
    }

    private fun settingsOverride(entries: String) {
        ComarkSettings.getInstance().imageDirectories = entries
        directories().invalidate()
    }

    private fun configurable(): ComarkConfigurable = configurable ?: ComarkConfigurable().also { configurable = it }

    private fun panel(): JComponent = checkNotNull(configurable().createComponent())

    private fun checkBox(panel: JComponent, name: String): JCheckBox = component(panel, name)

    private fun text(panel: JComponent, name: String): JTextComponent = component(panel, name)

    private inline fun <reified T : JComponent> component(panel: JComponent, name: String): T =
        UIUtil.findComponentsOfType(panel, T::class.java).singleOrNull { it.name == name }
            ?: error("No ${T::class.java.simpleName} named $name")
}
