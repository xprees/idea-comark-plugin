package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sanity checks on the Nuxt Studio oracle (`src/test/testData/studio/oracle.json`), recorded by
 * `src/test/node/studio-oracle/record.mjs` (Gradle: `recordStudioOracle`) from the real nuxt-studio 1.8.0 save pipeline.
 *
 * Every case holds the input, Studio's database-path output `db` = D(x), its text-path output `text` = T(x), `textOfDb` =
 * T(D(x)), whether each path is stable on its own output, and the comark trees and front matter the renderer receives on the
 * text path (`comarkTree`) and the database path (`dbTree`). A path that throws is recorded as `{"error": "..."}`. This test
 * checks that the file is well-formed, that it was recorded from the published nuxt-studio, comark and js-yaml files, and
 * that both recorded trees reproduce Studio's own outputs; the formatter tests compare against its contents.
 */
class StudioOracleCorpusTest {
    private val oracleFile = File("src/test/testData/studio/oracle.json")

    /** sha256 of the nuxt-studio 1.8.0 files the recorder loads, as published on npm. */
    private val expectedStudioFiles = mapOf(
        "nuxt-studio/dist/module/runtime/utils/document/generate.js" to "f39e653b48bfbfd70fa437f43a199be6b0b247cc8f01f75548458750868ae478",
        "nuxt-studio/dist/module/runtime/utils/document/legacy.js" to "c9fddc2e2705b2007dd2f4b98b3777075dd5b18de928dfbf062330a794e41691",
        "nuxt-studio/dist/module/runtime/utils/document/schema.js" to "2e58813177953511054acd7146e67375ea130a0c3d59f99b513d96db8a246054",
        "nuxt-studio/dist/module/runtime/utils/document/utils.js" to "d91a0b6da5d753197755f74dc273aa5f56eb2ace44a8748ec3d89926b93c9ef0",
        "nuxt-studio/dist/module/runtime/utils/document/tree.js" to "c148ed566a39e6e9b39a126fd0260dc7b5f9bdece6b99e3efaf88acbeb1d9fea",
        "nuxt-studio/dist/module/runtime/utils/collection.js" to "eb1b5bdfa3ab5b35f1367fba028ca48f3d4fae2caf8e70a254194529b0c07f27",
        "nuxt-studio/dist/module/runtime/utils/object.js" to "cb36d73c8fdb15b52162bdfa7cb2acd9ef56a3d4e8292529204d6a440eb9c3b1",
        "nuxt-studio/dist/module/runtime/utils/source.js" to "82f9e7a745c5982991cf9f0d4be6ae2d4b6f9ed3ccda134cfb2432fd6372d557",
        "nuxt-studio/dist/module/runtime/utils/url.js" to "406129b863d449f17fb1b6da395e8b3ff8ce55cc61688f0d0be383c4ad26499c",
        "nuxt-studio/dist/module/runtime/types/content.js" to "8d539d8a200b4a00232e7d4d82cbd2f0e0167e23a24ae50f342ec1352a6bb586",
    )

    /** sha256 of the comark 0.7.0 and js-yaml 5.5.0 files that render the markdown, as published on npm. */
    private val expectedRendererFiles = mapOf(
        "comark/dist/internal/frontmatter.js" to "fba9759556ce5f5efaa09a5edb7e855543aa11d01a7ad083f32fd8497d17ee7c",
        "comark/dist/internal/props-validation.js" to "f0c791b45709b1948e98ed5ea4ca79fd16f9392ea952448f62a8c347580696ac",
        "comark/dist/internal/stringify/attributes.js" to "2d5b8f97e673ad4fa35b4605e0bc847c479e627bb5cc62fb0586a24b3cb005f6",
        "comark/dist/internal/stringify/fence.js" to "a41e7ac658f7d8c9a6862eaf338ecf6076bef62859c2eed5dd929c8344c9d53a",
        "comark/dist/internal/stringify/handlers/a.js" to "5defe964993e141fda2c342ac3d6f8abd784f6df6fef8f79830cd1043b33f625",
        "comark/dist/internal/stringify/handlers/blockquote.js" to "2d9bed6928d0aebb9f001047abf8a11ea51f28b9c859d5c320cd8fe2861a67b8",
        "comark/dist/internal/stringify/handlers/br.js" to "a16d7be4a2431447d9d2d947d0d0bca7921c13fdbca086d9fafe6637a368d95b",
        "comark/dist/internal/stringify/handlers/code.js" to "b327cda54c2000bbc82a2862629cbffd028c7c29beda71a49538d87a8e0b0217",
        "comark/dist/internal/stringify/handlers/comment.js" to "cf6d5ddd797ca891c26a7338f9dc46dd1b2100d0be8e9e1a55b6d9e65dfb0aa7",
        "comark/dist/internal/stringify/handlers/del.js" to "ab949f9e3505fa8236a9905ae756c0fd7f81e9c2220c7377a0fd8f5f2de4a08a",
        "comark/dist/internal/stringify/handlers/emphesis.js" to "968e5eb4ef3074403fde6ee0e6f5c95b328b1f508c801e68318c2188c5ac1dd5",
        "comark/dist/internal/stringify/handlers/heading.js" to "be862a5b7ede09ee28479e892abc380ad353679b39f8cdfee571602c02a5474b",
        "comark/dist/internal/stringify/handlers/hr.js" to "0818a7a741120d348ce38424fc1785619bdd9146176cf85742bf2c32b30c5ac6",
        "comark/dist/internal/stringify/handlers/html.js" to "0c64be9ef9ae2da994d5639bf888757b2cc30b9a6191f66516626d4e801bef58",
        "comark/dist/internal/stringify/handlers/img.js" to "6b517e833bc73f0f24788eae07da295269f47fc9bcdd361732e25b4196439da0",
        "comark/dist/internal/stringify/handlers/index.js" to "c9623a7212335adda9b177b4bf597613e2729827c2f560743a325b6513cb9da7",
        "comark/dist/internal/stringify/handlers/li.js" to "5f9968788763b905584255d216812db317f2c6c953ced32b10472e7d6acd3bd0",
        "comark/dist/internal/stringify/handlers/math.js" to "0341c2132ced56f90148c2efa97075b1a4e69039e66caef04fc70c6c6e8139c8",
        "comark/dist/internal/stringify/handlers/mdc.js" to "83812f7be1c59d349d8168c0dc709716f696f11bd99fb3074e2b10b987f57ab4",
        "comark/dist/internal/stringify/handlers/mermaid.js" to "f1de42f51d52bc638fc6bdfd0d2247e1db9ac9226e581fd7d834d2bf0da09b1a",
        "comark/dist/internal/stringify/handlers/ol.js" to "f9b4782cf5ae6205d298425269493f92fd651a636f0b028f579a874c4670268e",
        "comark/dist/internal/stringify/handlers/p.js" to "dd6299f4518a65a0e5921f664761c6c809fc44f2d9c28ef617b952b9706a28ee",
        "comark/dist/internal/stringify/handlers/pre.js" to "ef0be33f97e8bc90002af360a9c488149becbaa65ed857d68d79ea7c8f384d53",
        "comark/dist/internal/stringify/handlers/strong.js" to "54f4e7c220cad81c13412e7748a8d42c8df91c7bb4c56cf4ba542d9e86155604",
        "comark/dist/internal/stringify/handlers/table.js" to "d30f5fe25f490f5178cf71d12ddee21491400b5b2c33c651fe5a6b3a8c62bd34",
        "comark/dist/internal/stringify/handlers/template.js" to "d03f0e81adb10222b8e9e9a7e4094d8877251975da4be69b5f4e2f8c5a7e32d5",
        "comark/dist/internal/stringify/handlers/ul.js" to "a0584f6bdd4e7e53bf65f1cd11fb8f63163ae910d8681c65eeebe1baf59c0758",
        "comark/dist/internal/stringify/state.js" to "2bacee2e42e6b97b9e7711068b21083b1a8afd696f0e13be00a2b3c45d7c0b86",
        "comark/dist/internal/yaml.js" to "8a1de28bd480087481d9357690ce3d0b503ad3ef2b23f638b52a50e190be20bb",
        "comark/dist/render.js" to "31488faabc708b5ca8621efc779273c8029d24f7820b7ad9ac1f18d9d8b0fec0",
        "comark/dist/utils/index.js" to "5e3e642fcad6f7a4d38959854df64a4ac23fe5dd00d7eff5e4a31e6d857927db",
        "js-yaml/dist/js-yaml.mjs" to "213470960d9c2ff1d01f9164e5017749bfc383d8a6ebb8683f35c76e8c6d052b",
    )

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** A recorded pipeline output: the markdown text, or `{"error": message}` when Studio threw. */
    private fun isOutput(e: JsonElement?): Boolean = e != null && (
        (e.isJsonPrimitive && e.asJsonPrimitive.isString) ||
            (e.isJsonObject && e.asJsonObject.keySet() == setOf("error") && e.asJsonObject.str("error") != null)
        )

    private fun JsonElement?.isText() = this != null && isJsonPrimitive && asJsonPrimitive.isString

    @Test
    fun oracleIsWellFormed() {
        assertTrue("missing $oracleFile, run ./gradlew recordStudioOracle", oracleFile.isFile)
        val root = JsonParser.parseString(oracleFile.readText()).asJsonObject

        val versions = root.getAsJsonObject("versions")
        assertEquals("1.8.0", versions.str("nuxt-studio"))
        assertEquals("0.7.0", versions.str("comark"))
        assertEquals("5.5.0", versions.str("js-yaml"))

        fun hashes(key: String) = root.getAsJsonObject(key).entrySet().associate { it.key to it.value.asString }
        assertEquals("studioFiles: recorded from another nuxt-studio build", expectedStudioFiles, hashes("studioFiles"))
        assertEquals("rendererFiles: recorded from another comark or js-yaml build", expectedRendererFiles, hashes("rendererFiles"))

        val cases = root.getAsJsonArray("cases").map { it.asJsonObject }
        val names = cases.map { it.str("name")!! }
        assertEquals("case names must be unique", names.size, names.toSet().size)
        assertTrue("hand-written corpus must have at least 60 documents", cases.count { it.str("source") == "handwritten" } >= 60)

        for (case in cases) {
            val name = case.str("name")
            assertTrue("$name: input", case.str("input") != null)
            assertTrue("$name: collectionType", case.str("collectionType") in setOf("page", "data"))
            assertTrue("$name: db", isOutput(case["db"]))
            assertTrue("$name: text", isOutput(case["text"]))
            assertTrue("$name: dbStable", case["dbStable"].asJsonPrimitive.isBoolean)
            assertTrue("$name: textStable", case["textStable"].asJsonPrimitive.isBoolean)
            if (case["db"].isText()) {
                assertTrue("$name: textOfDb", isOutput(case["textOfDb"]))
                assertTrue("$name: db ends with LF", case.str("db")!!.endsWith("\n"))
            }
            if (case["text"].isText()) {
                assertTrue("$name: comarkTree.nodes", case["comarkTree"].asJsonObject["nodes"].isJsonArray)
                assertTrue("$name: frontmatter", case["frontmatter"].isJsonObject)
                assertTrue("$name: renderedFromTree", isOutput(case["renderedFromTree"]))
                // The tree (with shared YAML objects kept as {"$type": "shared"/"ref"}) must reproduce Studio's own output.
                assertEquals("$name: renderedFromTree must equal text", case.str("text"), case.str("renderedFromTree"))
            }
            if (case["db"].isText()) {
                if (case["dbTree"].isJsonNull) {
                    assertEquals("$name: no database tree means Studio writes a lone LF", "\n", case.str("db"))
                } else {
                    assertTrue("$name: dbTree.nodes", case["dbTree"].asJsonObject["nodes"].isJsonArray)
                    assertTrue("$name: dbFrontmatter", case["dbFrontmatter"].isJsonObject)
                    assertEquals("$name: renderedFromDbTree must equal db", case.str("db"), case.str("renderedFromDbTree"))
                }
            }
        }

        val summary = root.getAsJsonObject("summary")
        fun count(predicate: (JsonObject) -> Boolean) = cases.count(predicate)
        val recount = mapOf(
            "cases" to cases.size,
            "dbStable" to count { it["dbStable"].asBoolean },
            "textStable" to count { it["textStable"].asBoolean },
            "dbErrors" to count { !it["db"].isText() },
            "textErrors" to count { !it["text"].isText() },
            "dbUnchanged" to count { it.str("db") == it.str("input") },
            "textUnchanged" to count { it.str("text") == it.str("input") },
            "textOfDbEqualsDb" to count { it["db"].isText() && it.str("textOfDb") == it.str("db") },
            "renderedFromTreeEqualsText" to count { it["text"].isText() && it.str("renderedFromTree") == it.str("text") },
            "dbTrees" to count { !it["dbTree"].isJsonNull },
            "renderedFromDbTreeEqualsDb" to count { it["db"].isText() && it.str("renderedFromDbTree") == it.str("db") },
        )
        for ((key, value) in recount) assertEquals("summary.$key", value, summary[key].asInt)

        println(
            "Studio oracle: ${recount["cases"]} cases (" +
                summary.getAsJsonObject("bySource").entrySet().joinToString { "${it.key} ${it.value.asInt}" } + "), " +
                "dbStable ${recount["dbStable"]}, textStable ${recount["textStable"]}, " +
                "T(D(x))==D(x) ${recount["textOfDbEqualsDb"]}, unchanged db/text ${recount["dbUnchanged"]}/${recount["textUnchanged"]}, " +
                "errors db/text ${recount["dbErrors"]}/${recount["textErrors"]}, " +
                "tree renders to text ${recount["renderedFromTreeEqualsText"]}",
        )
    }
}
