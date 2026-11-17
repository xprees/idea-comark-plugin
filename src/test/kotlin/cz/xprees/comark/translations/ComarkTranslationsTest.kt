package cz.xprees.comark.translations

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.settings.ComarkSettings

/** Translations of localized content pages: detection, counterparts, creation, the switcher and the inspection. */
class ComarkTranslationsTest : BasePlatformTestCase() {
    private lateinit var saved: Triple<Boolean, String, String>

    override fun setUp() {
        super.setUp()
        val s = ComarkSettings.getInstance()
        saved = Triple(s.translationsEnabled, s.translationLocales, s.translationKeys)
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.16.0" } }""")
        myFixture.addFileToProject(
            "site/content.config.ts",
            """
            import { defineCollection, defineContentConfig } from '@nuxt/content'
            const pageSchema = z.object({ title: z.string(), counterpart: z.string().optional() })
            export default defineContentConfig({
                collections: {
                    pages_en: defineCollection({ type: 'page', source: { include: 'pages/en/**/*.md', prefix: '/' }, schema: pageSchema.extend({
                        sitemap: defineSitemapSchema({ z, onUrl: (url, entry) => {
                            const pagePairs: Record<string, string> = { about: 'o-nas', terms: 'podminky', privacy: 'gdpr' }
                            url.alternatives = [{ hreflang: 'cs', href: '/cs/' + pagePairs[entry.slug] }]
                        } }),
                    }) }),
                    pages_cs: defineCollection({ type: 'page', source: { include: 'pages/cs/**/*.md', prefix: '/cs' }, schema: pageSchema }),
                    threats_en: defineCollection({ type: 'page', source: { include: 'threats/en/**/*.md', prefix: '/threats' } }),
                    threats_cs: defineCollection({ type: 'page', source: { include: 'threats/cs/**/*.md', prefix: '/cs/hrozby' } }),
                },
            })
            """.trimIndent(),
        )
        for (name in listOf("about", "privacy", "terms")) {
            myFixture.addFileToProject("site/content/pages/en/$name.md", "---\ntitle: ${name.uppercase()}\n---\n\n# $name\n")
        }
        myFixture.addFileToProject("site/content/pages/cs/o-nas.md", "---\ntitle: O nás\ncounterpart: about\n---\n\n# O nás\n")
        myFixture.addFileToProject("site/content/pages/cs/gdpr.md", "# GDPR\n")
        myFixture.addFileToProject("site/content/pages/cs/podminky.md", "# Podmínky\n")
        myFixture.addFileToProject("site/content/threats/en/01.phishing.md", "# Phishing\n")
        myFixture.addFileToProject("site/content/threats/en/02.vishing.md", "---\ntitle: Vishing\n---\n\n# Vishing\n")
        myFixture.addFileToProject("site/content/threats/cs/01.phishing.md", "# Phishing\n")
        // a lone directory named like a locale code is no locale
        myFixture.addFileToProject("site/content/docs/id/intro.md", "# Intro\n")
    }

    override fun tearDown() {
        try {
            val s = ComarkSettings.getInstance()
            s.translationsEnabled = saved.first
            s.translationLocales = saved.second
            s.translationKeys = saved.third
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String): VirtualFile = myFixture.findFileInTempDir("site/content/$path")!!
    private val translations get() = ComarkTranslations.getInstance(project)
    private fun of(path: String) = translations.translationsOf(file(path))!!
    private fun names(files: List<VirtualFile>?) = files.orEmpty().map { VfsUtilCore.getRelativePath(it, file("."))!! }

    private fun setLocales(value: String) {
        ComarkSettings.getInstance().translationLocales = value
        translations.settingsTracker.incModificationCount()
    }

    fun testLocalesAndGroups() {
        assertEquals("en", translations.localeOf(file("pages/en/about.md"))?.first)
        assertEquals("cs", translations.localeOf(file("threats/cs/01.phishing.md"))?.first)
        assertEquals(file("threats/en"), translations.localeOf(file("threats/en/02.vishing.md"))?.second)
        assertNull(translations.localeOf(file("docs/id/intro.md")))
        assertNull(translations.translationsOf(file("docs/id/intro.md")))
    }

    fun testCounterpartsBySamePathFrontMatterAndConfigMaps() {
        // the same path below the other locale
        assertEquals(listOf("threats/cs/01.phishing.md"), names(of("threats/en/01.phishing.md").others["cs"]))
        assertEquals(listOf("threats/en/01.phishing.md"), names(of("threats/cs/01.phishing.md").others["en"]))
        // `counterpart` in the front matter, both ways
        assertEquals(listOf("pages/en/about.md"), names(of("pages/cs/o-nas.md").others["en"]))
        assertEquals(listOf("pages/cs/o-nas.md"), names(of("pages/en/about.md").others["cs"]))
        // a string map of content.config.ts (pagePairs), both ways
        assertEquals(listOf("pages/cs/gdpr.md"), names(of("pages/en/privacy.md").others["cs"]))
        assertEquals(listOf("pages/en/terms.md"), names(of("pages/cs/podminky.md").others["en"]))
        // missing
        val vishing = of("threats/en/02.vishing.md")
        assertEquals(listOf("cs"), vishing.missing)
        assertEquals(file("threats").path + "/cs", vishing.directories["cs"])
    }

    fun testKnownLocalesAddMissingDirectories() {
        setLocales("en, cs, de")
        val phishing = of("threats/en/01.phishing.md")
        assertEquals(listOf("cs", "de"), phishing.others.keys.toList())
        assertEquals(listOf("de"), phishing.missing)
        assertEquals(file("threats").path + "/de", phishing.directories["de"])
        // only the listed locales count then
        setLocales("en")
        assertNull(translations.translationsOf(file("threats/en/01.phishing.md")))
    }

    fun testLocalesFromNuxtI18n() {
        myFixture.addFileToProject(
            "site/nuxt.config.ts",
            "export default defineNuxtConfig({ i18n: { locales: [{ code: 'en', file: 'en.json' }, { code: 'cs' }, { code: 'sk' }] } })",
        )
        assertEquals(listOf("cs", "sk"), of("threats/en/01.phishing.md").others.keys.toList())
    }

    fun testCreateTranslation() {
        val vishing = of("threats/en/02.vishing.md")
        // same name: a copy, no counterpart needed
        val copy = ComarkTranslationCreator.create(project, vishing.file, vishing.directories.getValue("cs"), "02.vishing.md")!!
        assertEquals("---\ntitle: Vishing\n---\n\n# Vishing\n", VfsUtilCore.loadText(copy))
        assertEquals(listOf("threats/cs/02.vishing.md"), names(of("threats/en/02.vishing.md").others["cs"]))
        // another name: `counterpart` names the page, in a locale directory that does not exist yet
        setLocales("en, cs, de")
        val about = of("pages/en/about.md")
        val german = ComarkTranslationCreator.create(project, about.file, about.directories.getValue("de"), "ueber-uns.md")!!
        assertEquals("---\ntitle: ABOUT\ncounterpart: about\n---\n\n# about\n", VfsUtilCore.loadText(german))
        assertEquals(listOf("pages/de/ueber-uns.md"), names(of("pages/en/about.md").others["de"]))
        // what cannot be created
        val fs = about.file.fileSystem
        val cs = about.directories.getValue("cs")
        assertNotNull(ComarkTranslationCreator.validate(fs, cs, "o-nas.md"))
        assertNotNull(ComarkTranslationCreator.validate(fs, cs, "../x.md"))
        assertNotNull(ComarkTranslationCreator.validate(fs, cs, "notes.txt"))
        assertNull(ComarkTranslationCreator.validate(fs, cs, "o-firme.md"))
    }

    fun testFrontMatterValue() {
        assertEquals("---\na: 1\ncounterpart: x\n---\nbody", ComarkTranslationCreator.withFrontMatterValue("---\na: 1\n---\nbody", "counterpart", "x"))
        assertEquals("---\ncounterpart: y\n---\nbody", ComarkTranslationCreator.withFrontMatterValue("---\ncounterpart: x\n---\nbody", "counterpart", "y"))
        assertEquals("---\ncounterpart: x\n---\n\n# T\n", ComarkTranslationCreator.withFrontMatterValue("# T\n", "counterpart", "x"))
    }

    fun testMissingTranslationInspection() {
        myFixture.enableInspections(ComarkMissingTranslationInspection())
        myFixture.configureFromExistingVirtualFile(file("threats/en/02.vishing.md"))
        val reported = myFixture.doHighlighting().filter { it.description == "No CS translation of this page" }
        assertEquals(reported.joinToString { "${it.startOffset}-${it.endOffset} ${it.severity}" }, 1, reported.size)
        val problem = reported.single()
        assertEquals("---", problem.text)
        // complete pages are not reported
        myFixture.configureFromExistingVirtualFile(file("threats/en/01.phishing.md"))
        assertTrue(myFixture.doHighlighting().none { it.description?.contains("translation") == true })
        // the fix asks for the path and creates the page
        myFixture.configureFromExistingVirtualFile(file("threats/en/02.vishing.md"))
        TestDialogManager.setTestInputDialog { "02.vishing.md" }
        // listed as an intention; applied through its descriptor (launchAction re-parses a .md page as plain Markdown)
        assertNotNull(myFixture.findSingleIntention("Create CS translation…"))
        val fix = ComarkMissingTranslationInspection.CreateTranslationFix("cs")
        val descriptor = com.intellij.codeInspection.InspectionManager.getInstance(project).createProblemDescriptor(
            myFixture.file, com.intellij.openapi.util.TextRange(0, 3), "", com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING, true, fix,
        )
        fix.applyFix(project, descriptor)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNotNull(myFixture.findFileInTempDir("site/content/threats/cs/02.vishing.md"))
    }

    private fun event(file: VirtualFile): AnActionEvent {
        val context: DataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()
        return AnActionEvent.createEvent(context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    }

    private fun items(file: VirtualFile): List<String?> {
        val group = ActionManager.getInstance().getAction(ComarkTranslationsGroup.ID) as ComarkTranslationsGroup
        return group.getChildren(event(file)).map { if (it is Separator) "--" else it.templatePresentation.text }
    }

    fun testSwitcherGroup() {
        val group = ActionManager.getInstance().getAction(ComarkTranslationsGroup.ID) as ComarkTranslationsGroup
        val e = event(file("threats/en/02.vishing.md"))
        group.update(e)
        assertTrue(e.presentation.isEnabledAndVisible)
        assertEquals("EN", e.presentation.text)
        assertEquals(listOf("EN · 02.vishing.md (this page)", "--", "Create CS translation…"), items(file("threats/en/02.vishing.md")))
        assertEquals(
            listOf("EN · about.md (this page)", "CS · o-nas.md", "--", "Open CS side by side · o-nas.md"),
            items(file("pages/en/about.md")),
        )
        // pages outside localized directories get no switcher
        val other = event(file("docs/id/intro.md"))
        group.update(other)
        assertFalse(other.presentation.isEnabledAndVisible)
    }

    fun testFloatingToolbarAndSwitchAction() {
        myFixture.configureFromExistingVirtualFile(file("pages/en/about.md"))
        val provider = ComarkTranslationsToolbarProvider()
        val context = SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor)
        assertTrue(provider.isApplicable(context))
        ComarkSettings.getInstance().translationsEnabled = false
        assertFalse(provider.isApplicable(context))
        ComarkSettings.getInstance().translationsEnabled = true
        // a single translation opens directly
        myFixture.testAction(ComarkSwitchTranslationAction())
        assertEquals(file("pages/cs/o-nas.md"), FileEditorManager.getInstance(project).selectedFiles.first())
        // Go to Related lists it
        val related = ComarkTranslationsGotoRelatedProvider().getItems(event(file("pages/en/about.md")).dataContext)
        assertEquals(listOf("o-nas.md"), related.map { (it.element as PsiFile).name })
    }

    fun testParsing() {
        assertEquals(listOf("en", "cs"), ComarkTranslations.i18nLocales("i18n: { defaultLocale: 'en', locales: ['en', 'cs'] }"))
        assertEquals(listOf("en", "pt-br"), ComarkTranslations.i18nLocales("locales: [{ code: 'en', iso: 'en-US' }, { code: \"pt-br\" }]"))
        assertEquals(
            listOf("about" to "o-nas", "terms" to "podminky"),
            ComarkTranslations.stringMaps("const p = { about: 'o-nas', terms: 'podminky' }; const q = { a: 1, b: 'x' }"),
        )
        assertEquals(mapOf("title" to "A: b", "slug" to "x"), ComarkTranslations.parseFrontMatter("---\ntitle: 'A: b'\nslug: x # c\nnested:\n  k: v\n---\n# T"))
        assertEquals("guide/install", ComarkTranslations.normalize("/01.guide/2.install.md"))
        assertTrue(LocaleCodes.isCode("en"))
        assertTrue(LocaleCodes.isCode("pt-BR"))
        assertTrue(LocaleCodes.isCode("zh_Hant"))
        assertFalse(LocaleCodes.isCode("xx"))
        assertFalse(LocaleCodes.isCode("docs"))
    }
}
