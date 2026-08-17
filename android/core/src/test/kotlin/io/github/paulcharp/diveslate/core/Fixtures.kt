package io.github.paulcharp.diveslate.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Access to the conformance fixtures.
 *
 * They were generated from the Python implementation this project started as,
 * and remain the record of what it did after that code was removed.
 *
 * They are read as untyped JSON on purpose. A typed mirror of the fixture shape
 * would be code in this repo describing what the fixture contains, and would
 * drift alongside the implementation it is supposed to be checking — the point
 * is to compare against something this codebase did not author.
 */
object Fixtures {

    /** The fixtures sit at the repository root, above the Gradle project. */
    val repoRoot: File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile
    ) { it.parentFile }
        .firstOrNull { File(it, "conformance").isDirectory }
        ?: error(
            "could not locate conformance/ above ${System.getProperty("user.dir")}; " +
                "it is committed at the repository root"
        )

    // Explicit UTF-8: dive logs carry accented site names and notes, and a
    // platform-default charset would mangle them into a mismatch that looks
    // like a logic error.
    fun read(file: File): JsonObject =
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject

    private fun required(relative: String): File =
        File(repoRoot, relative).also {
            check(it.isFile) { "missing $it — it is committed under conformance/" }
        }

    val specs: JsonObject by lazy { read(required("conformance/specs.json")) }

    val themes: JsonObject by lazy { read(required("conformance/themes.json")) }

    /** Every parsed-log fixture, in stable order. */
    val logs: List<File> by lazy {
        val dir = File(repoRoot, "conformance/logs")
        check(dir.isDirectory) { "missing $dir — it is committed under conformance/" }
        dir.listFiles { f: File -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.also { check(it.isNotEmpty()) { "no log fixtures in $dir" } }
            ?: error("could not list $dir")
    }
}
