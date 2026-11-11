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

    class State {
        var mode: Mode = Mode.AUTO
        var formatterEnabled: Boolean = true
        var componentCompletion: Boolean = true
        var componentMetadataUrl: String = ""
        var componentMetadataCacheMinutes: Int = 30
        var excludedPatterns: String = ""
    }

    private var state = State()

    var mode: Mode
        get() = state.mode
        set(value) {
            state.mode = value
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

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ComarkSettings = service()
    }
}
