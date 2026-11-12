package cz.xprees.comark.format.studio

/** A line diff for the style's own checks (the engine does not depend on the IntelliJ platform). */
internal object StudioLineDiff {
    /** One change: [deleted] lines of the old text from [start] are replaced by [inserted] lines of the new one. */
    data class Hunk(val start: Int, val deleted: Int, val inserted: Int)

    /** Above this many cells, the middle part (after the common prefix and suffix) is one hunk instead of an LCS diff. */
    private const val MAX_CELLS = 1_000_000L

    /** The hunks turning [a] into [b], in order: a longest common subsequence diff of the lines between their common ends. */
    fun hunks(a: List<String>, b: List<String>): List<Hunk> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val n = a.size - prefix - suffix
        val m = b.size - prefix - suffix
        if (n == 0 && m == 0) return emptyList()
        if (n == 0 || m == 0 || n.toLong() * m > MAX_CELLS) return listOf(Hunk(prefix, n, m))
        // lcs[i][j]: the LCS length of a[prefix + i ..] and b[prefix + j ..] (middle parts only)
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[prefix + i] == b[prefix + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val hunks = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var start = -1
        var deleted = 0
        var inserted = 0
        fun flush() {
            if (start >= 0) hunks += Hunk(prefix + start, deleted, inserted)
            start = -1
            deleted = 0
            inserted = 0
        }
        while (i < n || j < m) {
            when {
                i < n && j < m && a[prefix + i] == b[prefix + j] -> {
                    flush()
                    i++
                    j++
                }
                j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    if (start < 0) start = i
                    inserted++
                    j++
                }
                else -> {
                    if (start < 0) start = i
                    deleted++
                    i++
                }
            }
        }
        flush()
        return hunks
    }
}
