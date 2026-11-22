package cz.xprees.comark.format

/**
 * The nuxt-studio releases the Nuxt Studio style can reproduce, each with the comark release it renders with. A release is
 * [available] once the engine's port of it passes that release's recorded save-pipeline oracle; only available releases are
 * offered in the settings and picked automatically.
 */
enum class StudioEngine(val studio: String, val comark: String, val available: Boolean) {
    V1_8_0("1.8.0", "0.7.0", true),

    /** comark 0.9.2 (oracle and vectors in `src/test/testData/studio/1.8.2`). */
    V1_8_2("1.8.2", "0.9.2", true),
    ;

    companion object {
        /** The value of the *Nuxt Studio version* setting that picks the release automatically. */
        const val AUTO = "auto"

        val supported: List<StudioEngine> get() = entries.filter { it.available }

        /** The newest available release. */
        val newest: StudioEngine get() = supported.maxBy { version(it.studio) }

        /** The available release whose nuxt-studio version is [studio], or null. */
        fun of(studio: String): StudioEngine? = supported.firstOrNull { it.studio == studio }

        /**
         * The release to reproduce for a package with [dependencies] when the setting is [setting] ([AUTO] or a nuxt-studio
         * version):
         * - an available release named by the setting;
         * - automatically, without a nuxt-studio dependency, the [newest];
         * - automatically, for the versions a lockfile resolves, the newest available release not newer than the newest of them
         *   (the oldest available one when all are older), so a project keeps the output of the release it uses;
         * - automatically, for a `package.json` range without a lockfile, the newest available release the range allows, else the
         *   one closest to the range's base version.
         */
        fun resolve(setting: String, dependencies: StudioDependencies?): StudioEngine = resolve(setting, dependencies, supported)

        /** [resolve] choosing among [candidates] (tests check the choice between releases that aren't available yet). */
        internal fun resolve(setting: String, dependencies: StudioDependencies?, candidates: List<StudioEngine>): StudioEngine {
            val newest = candidates.maxBy { version(it.studio) }
            if (setting != AUTO) candidates.firstOrNull { it.studio == setting }?.let { return it }
            if (dependencies == null) return newest
            val resolved = dependencies.studioVersions.mapNotNull { version(it) }.maxOrNull()
            if (resolved != null) return closestTo(resolved, candidates)
            val range = dependencies.studioRange
            candidates.filter { StudioVersionParser.satisfies(range, it.studio) }.maxByOrNull { version(it.studio) }?.let { return it }
            return StudioVersionParser.baseVersion(range)?.let(::version)?.let { closestTo(it, candidates) } ?: newest
        }

        private fun closestTo(target: Version, candidates: List<StudioEngine>): StudioEngine =
            candidates.filter { version(it.studio) <= target }.maxByOrNull { version(it.studio) } ?: candidates.minBy { version(it.studio) }

        private fun version(text: String): Version = version(text as CharSequence)!!

        internal fun version(text: CharSequence): Version? =
            Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(text)?.let { Version(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toIntOrNull() ?: 0) }
    }

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }
}
