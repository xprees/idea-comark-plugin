package cz.xprees.comark.images

/**
 * Which component props and front matter keys hold image paths. Shared by references, completion, the missing-image inspection and
 * the preview so they agree on what an image value is.
 *
 * A key is image-like when its last word (camelCase, kebab-case or snake_case) is one of [WORDS]: `src`, `image`, `heroImage`,
 * `logo-src`, `thumbnail_src`. `icon` counts too; icon names like `i-lucide-x` are never site-absolute paths, so only `/icons/x.svg`
 * style values end up treated as images.
 */
object ImageKeys {
    private val WORDS = setOf("src", "image", "img", "icon", "cover", "poster", "logo", "avatar", "thumbnail")

    /** Keys that hold an image path when nested under an image-like key (`image: { url: … }`). */
    private val NESTED = setOf("src", "url", "path")

    /** True for an image-like prop name. Bound props (`:src`, `v-bind:src`) hold expressions, not paths, and never count. */
    fun isImageProp(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(":") || name.startsWith("v-bind:") || name.startsWith("#") || name.startsWith("@")) return false
        val words = words(name)
        return words.isNotEmpty() && words.last() in WORDS
    }

    /**
     * True for the key path of a front matter value that holds an image: the last key is image-like (`image`, `cover`,
     * `seo.image`), its part after the last `:` is (`og:image`), or it is `src`/`url`/`path` under an image-like key (`image.src`).
     */
    fun isImageKeyPath(path: List<String>): Boolean {
        val last = path.lastOrNull() ?: return false
        if (isImageProp(last) || isImageProp(last.substringAfterLast(':'))) return true
        val parent = path.getOrNull(path.size - 2) ?: return false
        return last.lowercase() in NESTED && (isImageProp(parent) || isImageProp(parent.substringAfterLast(':')))
    }

    private fun words(name: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for ((index, c) in name.withIndex()) {
            val boundary = c == '-' || c == '_' || c == '.' || c == ':'
            if (boundary || c.isUpperCase() && index > 0 && !name[index - 1].isUpperCase()) {
                if (current.isNotEmpty()) words += current.toString().lowercase()
                current.setLength(0)
                if (boundary) continue
            }
            current.append(c)
        }
        if (current.isNotEmpty()) words += current.toString().lowercase()
        return words
    }
}
