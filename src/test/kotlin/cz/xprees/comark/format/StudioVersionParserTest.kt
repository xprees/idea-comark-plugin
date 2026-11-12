package cz.xprees.comark.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [StudioVersionParser]: `package.json`, every lockfile format, and the version comparison. */
class StudioVersionParserTest {
    @Test
    fun packageJson() {
        val text = """{ "dependencies": { "@comark/vue": "^1.0.0", "comark-x": "2.0.0", "nuxt-studio": "^1.9.0" }, "devDependencies": { "comark": "0.7.1" } }"""
        assertEquals("^1.9.0", StudioVersionParser.packageJsonRange(text, "nuxt-studio"))
        assertEquals("0.7.1", StudioVersionParser.packageJsonRange(text, "comark"))
        assertNull(StudioVersionParser.packageJsonRange("""{ "dependencies": { "@nuxt/content": "^3" } }""", "nuxt-studio"))
    }

    @Test
    fun packageLockV3() {
        val text = """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "dependencies": { "nuxt-studio": "^1.8.0" } },
                "node_modules/@comark/vue": { "version": "1.2.0" },
                "node_modules/comark": { "version": "0.8.1", "resolved": "x" },
                "node_modules/nuxt-studio": {
                  "version": "1.9.2",
                  "dependencies": { "comark": "0.7.0" }
                },
                "node_modules/nuxt-studio/node_modules/comark": { "version": "0.7.0" }
              }
            }
        """.trimIndent()
        assertEquals(listOf("1.9.2"), StudioVersionParser.lockfileVersions("package-lock.json", text, "nuxt-studio"))
        assertEquals(listOf("0.8.1", "0.7.0"), StudioVersionParser.lockfileVersions("package-lock.json", text, "comark"))
    }

    @Test
    fun packageLockV1() {
        val text = """{ "lockfileVersion": 1, "dependencies": { "nuxt-studio": { "version": "1.8.0", "requires": { "comark": "0.7.0" } }, "comark": { "version": "0.7.0" } } }"""
        assertEquals(listOf("1.8.0"), StudioVersionParser.lockfileVersions("npm-shrinkwrap.json", text, "nuxt-studio"))
        assertEquals(listOf("0.7.0"), StudioVersionParser.lockfileVersions("package-lock.json", text, "comark"))
    }

    @Test
    fun pnpmLock() {
        val v9 = """
            lockfileVersion: '9.0'
            importers:
              .:
                dependencies:
                  nuxt-studio:
                    specifier: ^1.8.0
                    version: 1.10.0(vue@3.5.0)
            packages:
              '@comark/vue@1.2.0':
                resolution: {integrity: sha512-x}
              comark@0.7.0:
                resolution: {integrity: sha512-y}
              nuxt-studio@1.10.0:
                resolution: {integrity: sha512-z}
            snapshots:
              nuxt-studio@1.10.0(vue@3.5.0):
                dependencies:
                  comark: 0.7.0
        """.trimIndent()
        assertEquals(listOf("1.10.0"), StudioVersionParser.lockfileVersions("pnpm-lock.yaml", v9, "nuxt-studio"))
        assertEquals(listOf("0.7.0"), StudioVersionParser.lockfileVersions("pnpm-lock.yaml", v9, "comark"))
        val v6 = "lockfileVersion: '6.0'\npackages:\n  /nuxt-studio@1.8.0(vue@3.5.0):\n    resolution: {}\n"
        assertEquals(listOf("1.8.0"), StudioVersionParser.lockfileVersions("pnpm-lock.yaml", v6, "nuxt-studio"))
        val v5 = "lockfileVersion: 5.4\npackages:\n  /nuxt-studio/1.7.3:\n    resolution: {}\n"
        assertEquals(listOf("1.7.3"), StudioVersionParser.lockfileVersions("pnpm-lock.yaml", v5, "nuxt-studio"))
    }

    @Test
    fun yarnClassic() {
        val text = """
            # yarn lockfile v1


            "@comark/vue@^1.0.0":
              version "1.2.0"

            comark@0.7.0, comark@^0.7.0:
              version "0.7.0"
              resolved "https://registry.yarnpkg.com/comark/-/comark-0.7.0.tgz"

            "nuxt-studio@^1.8.0":
              version "1.8.4"
              dependencies:
                comark "0.7.0"
        """.trimIndent()
        assertEquals(listOf("1.8.4"), StudioVersionParser.lockfileVersions("yarn.lock", text, "nuxt-studio"))
        assertEquals(listOf("0.7.0"), StudioVersionParser.lockfileVersions("yarn.lock", text, "comark"))
    }

    @Test
    fun yarnBerry() {
        val text = """
            __metadata:
              version: 8

            "comark@npm:0.8.0":
              version: 0.8.0
              resolution: "comark@npm:0.8.0"

            "nuxt-studio@npm:^1.9.0":
              version: 1.9.1
              resolution: "nuxt-studio@npm:1.9.1"
        """.trimIndent()
        assertEquals(listOf("1.9.1"), StudioVersionParser.lockfileVersions("yarn.lock", text, "nuxt-studio"))
        assertEquals(listOf("0.8.0"), StudioVersionParser.lockfileVersions("yarn.lock", text, "comark"))
    }

    @Test
    fun bunLock() {
        val text = """
            {
              "lockfileVersion": 1,
              "workspaces": { "": { "dependencies": { "nuxt-studio": "^1.8.0" } } },
              "packages": {
                "@comark/vue": ["@comark/vue@1.2.0", "", {}, "sha512-a"],
                "comark": ["comark@0.7.0", "", {}, "sha512-b"],
                "nuxt-studio": ["nuxt-studio@1.8.0", "", { "dependencies": { "comark": "0.7.0" } }, "sha512-c"],
              }
            }
        """.trimIndent()
        assertEquals(listOf("1.8.0"), StudioVersionParser.lockfileVersions("bun.lock", text, "nuxt-studio"))
        assertEquals(listOf("0.7.0"), StudioVersionParser.lockfileVersions("bun.lock", text, "comark"))
    }

    @Test
    fun versionComparison() {
        val pinned = "1.8.0"
        assertEquals(true, StudioVersionParser.matches("1.8.0", pinned))
        assertEquals(true, StudioVersionParser.matches("1.8.5", pinned))
        assertEquals(false, StudioVersionParser.matches("1.9.0", pinned))
        assertEquals(false, StudioVersionParser.matches("2.0.0-beta.1", pinned))
        assertEquals(true, StudioVersionParser.matches("^1.7.0", pinned))
        assertEquals(false, StudioVersionParser.matches("^1.9.0", pinned))
        assertEquals(false, StudioVersionParser.matches("~1.7.0", pinned))
        assertEquals(true, StudioVersionParser.matches("~1.8.2", pinned))
        assertEquals(true, StudioVersionParser.matches("1.8.x", pinned))
        assertEquals(true, StudioVersionParser.matches("npm:1.8.0", pinned))
        assertNull(StudioVersionParser.matches("latest", pinned))
        assertNull(StudioVersionParser.matches(">=1.0.0", pinned))
        assertNull(StudioVersionParser.matches("workspace:*", pinned))
        // 0.x: a caret range stays on its minor
        assertEquals(true, StudioVersionParser.matches("^0.7.0", "0.7.0"))
        assertEquals(false, StudioVersionParser.matches("^0.8.0", "0.7.0"))
    }

    @Test
    fun mismatches() {
        assertEquals(emptyList<String>(), StudioDependencies("^1.8.0", listOf("1.8.0"), null, listOf("0.7.0"), "pnpm-lock.yaml").mismatches)
        assertEquals(listOf("nuxt-studio 1.9.2"), StudioDependencies("^1.8.0", listOf("1.9.2"), null, emptyList(), "yarn.lock").mismatches)
        assertEquals(listOf("comark 0.8.1"), StudioDependencies("^1.8.0", listOf("1.8.0"), null, listOf("0.8.1"), "bun.lock").mismatches)
        // one of several resolved comark versions is the pinned one (nuxt-studio's own copy)
        assertEquals(emptyList<String>(), StudioDependencies("1.8.0", listOf("1.8.0"), null, listOf("0.8.1", "0.7.0"), "package-lock.json").mismatches)
        // without a lockfile the package.json range decides; unknown ranges don't warn
        assertEquals(listOf("nuxt-studio ^2.0.0"), StudioDependencies("^2.0.0", emptyList(), null, emptyList(), null).mismatches)
        assertEquals(emptyList<String>(), StudioDependencies("latest", emptyList(), null, emptyList(), null).mismatches)
    }
}
