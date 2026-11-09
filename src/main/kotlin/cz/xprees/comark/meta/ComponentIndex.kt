package cz.xprees.comark.meta

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Immutable lookup structure over all discovered components.
 *
 * Names are matched by [ComponentNaming.lookupKey] (so `prose-alert`, `proseAlert` and `ProseAlert` are the same name) with
 * [ComponentNaming.looseKey] as a fallback. When several components share a name (two Nuxt apps in a monorepo each with an
 * `Alert`), the one whose app contains the Markdown file wins, then the richer metadata source.
 */
class ComponentIndex(val components: List<ComarkComponent>) {
    private val byKey: Map<String, List<ComarkComponent>>
    private val byLooseKey: Map<String, List<ComarkComponent>>

    init {
        val keys = HashMap<String, MutableList<ComarkComponent>>()
        val loose = HashMap<String, MutableList<ComarkComponent>>()
        for (component in components) {
            for (name in component.allNames) {
                keys.getOrPut(ComponentNaming.lookupKey(name)) { ArrayList() }.addIfAbsent(component)
                loose.getOrPut(ComponentNaming.looseKey(name)) { ArrayList() }.addIfAbsent(component)
            }
        }
        byKey = keys
        byLooseKey = loose
    }

    val isEmpty: Boolean get() = components.isEmpty()

    /** The component [name] (as written in Markdown) refers to, as seen from [context] (the Markdown file), or null. */
    fun find(name: String, context: VirtualFile? = null): ComarkComponent? {
        if (name.isEmpty()) return null
        val candidates = byKey[ComponentNaming.lookupKey(name)] ?: byLooseKey[ComponentNaming.looseKey(name)] ?: return null
        return best(candidates, context)
    }

    /** One component per primary name (the best one for [context]), sorted by name: what completion offers. */
    fun visible(context: VirtualFile? = null): List<ComarkComponent> =
        components.groupBy { ComponentNaming.lookupKey(it.name) }.values
            .map { best(it, context) }
            .sortedBy { it.kebabName }

    private fun best(candidates: List<ComarkComponent>, context: VirtualFile?): ComarkComponent =
        if (candidates.size == 1) candidates[0] else candidates.maxWith(compareBy({ contextScore(it, context) }, { it.source.ordinal }))

    /** Length of the component's app root path when it contains [context] (the closer the app, the higher), else -1. */
    private fun contextScore(component: ComarkComponent, context: VirtualFile?): Int {
        val root = component.root ?: return -1
        if (context == null || !VfsUtilCore.isAncestor(root, context, false)) return -1
        return root.path.length
    }

    private fun <T> MutableList<T>.addIfAbsent(item: T) {
        if (none { it === item }) add(item)
    }

    companion object {
        val EMPTY = ComponentIndex(emptyList())
    }
}
