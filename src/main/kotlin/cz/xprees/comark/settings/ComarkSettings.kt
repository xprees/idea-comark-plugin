package cz.xprees.comark.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Application level Comark settings (Settings | Languages & Frameworks | Comark). */
@Service(Service.Level.APP)
@State(name = "ComarkSettings", storages = [Storage("comark.xml")])
class ComarkSettings : PersistentStateComponent<ComarkSettings.State> {
    /** Which `.md` files are Comark (`.mdc` files always are). */
    enum class Mode {
        /** `.md` files in packages depending on Nuxt Content / MDC / Comark. */
        AUTO,

        /** Every `.md` file. */
        ALWAYS,

        /** Only `.mdc` files. */
        NEVER,
    }

    /** Whose rules decide where a component starts and ends when the source disagrees. */
    enum class Dialect {
        /**
         * Nuxt Content and Nuxt Studio (remark-mdc): a component opener counts at any indentation when it starts a block (after a
         * blank line or another component), its content is relative to the opener, and a closer may sit up to three columns deeper.
         */
        NUXT_CONTENT,

        /** The comark parser: strict CommonMark, a line indented four or more columns past its container is code. */
        COMARK,
    }

    class State {
        var mode: Mode = Mode.AUTO
        var dialect: Dialect = Dialect.NUXT_CONTENT
        var formatterEnabled: Boolean = true
        var componentCompletion: Boolean = true
        var componentMetadataUrl: String = ""
        var componentMetadataCacheMinutes: Int = 30
        var excludedPatterns: String = ""
        var contentConfigPath: String = ""
        var imagesEnabled: Boolean = true
        var imageDirectories: String = ""
        var imageCdnUrl: String = ""
        var previewImagesFromCdn: Boolean = false
    }

    private var state = State()

    var mode: Mode
        get() = state.mode
        set(value) {
            state.mode = value
        }

    var dialect: Dialect
        get() = state.dialect
        set(value) {
            state.dialect = value
        }

    /** Reformat Code uses the Comark formatter (a port of the VS Code extension's) for Comark files. */
    var formatterEnabled: Boolean
        get() = state.formatterEnabled
        set(value) {
            state.formatterEnabled = value
        }

    var componentCompletion: Boolean
        get() = state.componentCompletion
        set(value) {
            state.componentCompletion = value
        }

    var componentMetadataUrl: String
        get() = state.componentMetadataUrl
        set(value) {
            state.componentMetadataUrl = value.trim()
        }

    /** How long component metadata fetched from [componentMetadataUrl] is reused. */
    var componentMetadataCacheMinutes: Int
        get() = state.componentMetadataCacheMinutes
        set(value) {
            state.componentMetadataCacheMinutes = value.coerceAtLeast(0)
        }

    /**
     * Glob patterns (one per line, `#` starts a comment) of `.md` files and directories that are never Comark, whatever [mode] says;
     * see [cz.xprees.comark.lang.ComarkExclusions] for how they match. `.mdc` files are always Comark.
     */
    var excludedPatterns: String
        get() = state.excludedPatterns
        set(value) {
            state.excludedPatterns = value.trim()
        }

    /**
     * In [Mode.AUTO]: the Nuxt Content configuration (`content.config.ts`, or a `nuxt.config.ts` with Nuxt Content 2 sources) to use
     * instead of the ones found next to `package.json` files; absolute, or relative to the project directory. Empty: discover them.
     */
    var contentConfigPath: String
        get() = state.contentConfigPath
        set(value) {
            state.contentConfigPath = value.trim()
        }

    /** Image paths (`/threats/x.webp`) resolve against the Nuxt public and Nuxt Image directories. */
    var imagesEnabled: Boolean
        get() = state.imagesEnabled
        set(value) {
            state.imagesEnabled = value
        }

    /**
     * Directories (one per line, `#` starts a comment, in priority order) that image paths resolve against instead of the detected
     * ones; absolute, or relative to each project's directory (the setting applies to every project). Empty: detect them.
     */
    var imageDirectories: String
        get() = state.imageDirectories
        set(value) {
            state.imageDirectories = value.trim()
        }

    /** The CDN URL image paths may be prefixed with; replaces `app.cdnURL` of the Nuxt configuration when set. */
    var imageCdnUrl: String
        get() = state.imageCdnUrl
        set(value) {
            state.imageCdnUrl = value.trim()
        }

    /** The preview loads images of public directories from the CDN URL instead of the local files. */
    var previewImagesFromCdn: Boolean
        get() = state.previewImagesFromCdn
        set(value) {
            state.previewImagesFromCdn = value
        }

    /** The entries of [imageDirectories], comments and blank lines dropped. */
    fun imageDirectoryList(): List<String> =
        imageDirectories.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ComarkSettings = service()
    }
}
