package io.github.paulcharp.diveslate.core

import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the real logs and compares the whole structure against Python.
 *
 * This is the test the port stands or falls on. Every sample field is compared
 * individually, which is the only way to catch the failure that matters:
 * Subsurface writes an attribute only when it changes, so `reference.ssrf`
 * states `in_deco` exactly twice across 1930 samples while 1503 of them carry
 * an obligation. A parser that treats an absent attribute as unknown produces a
 * plausible-looking dive with the deco almost entirely missing, and no summary
 * figure would necessarily reveal it.
 */
class ParserConformanceTest {

    private companion object {
        const val TOLERANCE = 1e-6
    }

    private fun JsonElement?.orNull(): JsonElement? =
        if (this == null || this is JsonNull) null else this

    private fun JsonObject.double(key: String): Double? =
        this[key].orNull()?.jsonPrimitive?.content?.toDouble()

    private fun JsonObject.int(key: String): Int? =
        this[key].orNull()?.jsonPrimitive?.content?.toInt()

    private fun JsonObject.string(key: String): String? =
        this[key].orNull()?.jsonPrimitive?.content

    private fun JsonObject.bool(key: String): Boolean =
        this[key].orNull()?.jsonPrimitive?.content?.toBoolean() ?: false

    private fun assertClose(expected: Double?, actual: Double?, what: String) {
        if (expected == null) {
            assertNull(actual, "$what: expected null, got $actual")
            return
        }
        assertNotNull(actual, "$what: expected $expected, got null")
        assertTrue(abs(expected - actual) < TOLERANCE, "$what: expected $expected, got $actual")
    }

    private fun sourceFor(fixture: JsonObject): File =
        File(Fixtures.repoRoot, "tests/data/${fixture.getValue("source").jsonPrimitive.content}")

    @Test
    fun `parsed logs match the oracle field for field`() {
        assertTrue(Fixtures.logs.isNotEmpty(), "no log fixtures")

        for (fixtureFile in Fixtures.logs) {
            val fixture = Fixtures.read(fixtureFile)
            val source = sourceFor(fixture)
            assertTrue(source.isFile, "fixture references a missing log: $source")

            val label = source.name
            val log = parseText(
                source.readText(Charsets.UTF_8),
                hint = source.name,
                source = source.path,
            )

            assertEquals(fixture.string("program"), log.program, "$label program")
            assertEquals(fixture.int("dive_count"), log.size, "$label dive count")

            val expectedSites = fixture.getValue("sites").jsonObject
            assertEquals(expectedSites.size, log.sites.size, "$label site count")
            for ((uuid, name) in expectedSites) {
                assertEquals(name.jsonPrimitive.content, log.sites[uuid], "$label site $uuid")
            }

            for ((index, element) in fixture.getValue("dives").jsonArray.withIndex()) {
                compareDive("$label[$index]", element.jsonObject, log[index])
            }
        }
    }

    private fun compareDive(label: String, expected: JsonObject, dive: Dive) {
        assertEquals(expected.int("number"), dive.number, "$label number")
        // Compared as values, not strings: LocalDateTime.toString() omits the
        // seconds field when it is zero, where Python's isoformat keeps it.
        assertEquals(
            expected.string("when")?.let { LocalDateTime.parse(it) },
            dive.whenLogged,
            "$label when",
        )
        assertEquals(expected.string("site"), dive.site, "$label site")
        assertEquals(expected.string("buddy"), dive.buddy, "$label buddy")
        assertEquals(expected.string("notes"), dive.notes, "$label notes")
        assertEquals(expected.int("rating"), dive.rating, "$label rating")
        assertEquals(expected.string("computer"), dive.computer, "$label computer")
        assertEquals(expected.string("deco_model"), dive.decoModel, "$label deco model")
        assertEquals(
            expected.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
            dive.tags,
            "$label tags",
        )

        assertClose(expected.double("duration_s"), dive.durationSeconds, "$label duration")
        assertClose(expected.double("max_depth_m"), dive.maxDepthMetres, "$label max depth")
        assertClose(expected.double("mean_depth_m"), dive.meanDepthMetres, "$label mean depth")
        assertClose(expected.double("water_temp_c"), dive.waterTempCelsius, "$label water temp")
        assertClose(
            expected.double("surface_pressure_bar"),
            dive.surfacePressureBar,
            "$label surface pressure",
        )
        assertClose(
            expected.double("salinity_g_l"),
            dive.salinityGramsPerLitre,
            "$label salinity",
        )
        assertClose(expected.double("sac_l_min"), dive.sacLitresPerMin, "$label sac")
        assertClose(expected.double("otu"), dive.otu, "$label otu")
        assertClose(expected.double("cns"), dive.cns, "$label cns")

        compareCylinders(label, expected.getValue("cylinders").jsonArray, dive.cylinders)
        compareSwitches(label, expected.getValue("gas_switches").jsonArray, dive.gasSwitches)

        assertEquals(expected.int("sample_count"), dive.samples.size, "$label sample count")
        compareSamples(label, expected.getValue("samples").jsonArray, dive.samples)
    }

    private fun compareCylinders(
        label: String,
        expected: kotlinx.serialization.json.JsonArray,
        actual: List<Cylinder>,
    ) {
        assertEquals(expected.size, actual.size, "$label cylinder count")
        for ((i, element) in expected.withIndex()) {
            val c = element.jsonObject
            val got = actual[i]
            val gas = c.getValue("gas").jsonObject
            assertClose(gas.double("o2"), got.gas.o2, "$label cyl $i o2")
            assertClose(gas.double("he"), got.gas.he, "$label cyl $i he")
            assertEquals(gas.string("name"), got.gas.name, "$label cyl $i mix name")
            assertEquals(c.string("description"), got.description, "$label cyl $i description")
            assertClose(c.double("size_l"), got.sizeLitres, "$label cyl $i size")
            assertClose(
                c.double("work_pressure_bar"),
                got.workPressureBar,
                "$label cyl $i work pressure",
            )
            assertClose(c.double("start_bar"), got.startBar, "$label cyl $i start")
            assertClose(c.double("end_bar"), got.endBar, "$label cyl $i end")
            assertEquals(c.string("label"), got.label, "$label cyl $i label")
        }
    }

    private fun compareSwitches(
        label: String,
        expected: kotlinx.serialization.json.JsonArray,
        actual: List<GasSwitch>,
    ) {
        assertEquals(expected.size, actual.size, "$label gas switch count")
        for ((i, element) in expected.withIndex()) {
            val s = element.jsonObject
            val got = actual[i]
            assertClose(s.double("time_s"), got.timeSeconds, "$label switch $i time")
            assertEquals(
                s.getValue("gas").jsonObject.string("name"),
                got.gas.name,
                "$label switch $i mix",
            )
            assertEquals(s.int("cylinder_index"), got.cylinderIndex, "$label switch $i cylinder")
        }
    }

    /**
     * Compare every field of every sample.
     *
     * Deliberately not a summary check. Carry-forward failures show up as a
     * scattering of nulls partway through a long series, which any aggregate
     * would hide.
     */
    private fun compareSamples(
        label: String,
        expected: kotlinx.serialization.json.JsonArray,
        actual: List<Sample>,
    ) {
        for ((i, element) in expected.withIndex()) {
            val s = element.jsonObject
            val got = actual[i]
            val at = "$label sample $i"
            assertClose(s.double("time_s"), got.timeSeconds, "$at time")
            assertClose(s.double("depth_m"), got.depthMetres, "$at depth")
            assertClose(s.double("temp_c"), got.tempCelsius, "$at temp")
            assertClose(s.double("ndl_s"), got.ndlSeconds, "$at ndl")
            assertClose(s.double("tts_s"), got.ttsSeconds, "$at tts")
            assertEquals(s.bool("in_deco"), got.inDeco, "$at in_deco")
            assertClose(s.double("stop_depth_m"), got.stopDepthMetres, "$at stop depth")
            assertClose(s.double("stop_time_s"), got.stopTimeSeconds, "$at stop time")
            assertClose(s.double("cns"), got.cns, "$at cns")
            assertClose(s.double("pressure_bar"), got.pressureBar, "$at pressure")
        }
    }

    /**
     * Carry-forward, stated as a property rather than inferred from equality.
     *
     * If this passes while the field-by-field comparison above fails, the bug is
     * in a specific attribute; if both fail together, carry-forward itself is
     * broken. Worth separating, because the two have very different fixes.
     */
    @Test
    fun `sparse attributes are carried forward across the series`() {
        val source = File(Fixtures.repoRoot, "tests/data/reference.ssrf")
        if (!source.isFile) return

        val text = source.readText(Charsets.UTF_8)
        val dive = parseText(text, hint = source.name).only()

        val statedInDeco = Regex("in_deco").findAll(text).count()
        val samplesInDeco = dive.samples.count { it.inDeco }

        assertTrue(
            statedInDeco < 10,
            "expected in_deco to be written a handful of times, saw $statedInDeco",
        )
        assertTrue(
            samplesInDeco > 1000,
            "expected the obligation to carry across the series, only $samplesInDeco samples had it",
        )
        assertEquals(
            dive.samples.size,
            dive.samples.count { it.tempCelsius != null },
            "temperature should be populated on every sample once carried forward",
        )
    }

    @Test
    fun `format detection is by content not extension`() {
        val ssrf = File(Fixtures.repoRoot, "tests/data/sample.ssrf").readText(Charsets.UTF_8)
        val uddf = File(Fixtures.repoRoot, "tests/data/sample.uddf").readText(Charsets.UTF_8)

        // Renamed, misnamed, or nameless — the content decides.
        assertEquals(SubsurfaceParser.FORMAT_NAME, sniff(ssrf).formatName, "ssrf with no hint")
        assertEquals(UddfParser.FORMAT_NAME, sniff(uddf).formatName, "uddf with no hint")
        assertEquals(
            SubsurfaceParser.FORMAT_NAME,
            sniff(ssrf, hint = "export.uddf").formatName,
            "a misleading extension must not win over content",
        )
        assertEquals(
            UddfParser.FORMAT_NAME,
            sniff(uddf, hint = "log.xml").formatName,
            "the generic .xml both formats claim",
        )
    }

    @Test
    fun `an unrecognised document is refused rather than guessed at`() {
        val notADiveLog = "<html><body>nope</body></html>"
        val failed = try {
            sniff(notADiveLog)
            false
        } catch (_: ParseException) {
            true
        }
        assertTrue(failed, "expected an unrecognised document to be refused")
    }

    /**
     * The reader must refuse a doctype, so a crafted log cannot read the
     * device's filesystem. This has no Python counterpart — the desktop tool
     * reads files the user picked, while the app is handed them by other apps.
     */
    @Test
    fun `external entities are refused`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE divelog [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <divelog program='subsurface'><dives><dive number='1'>
            <notes>&xxe;</notes></dive></dives></divelog>
        """.trimIndent()

        val refused = try {
            SubsurfaceParser.parse(xxe)
            false
        } catch (_: ParseException) {
            true
        }
        assertTrue(refused, "a document declaring a doctype must be refused outright")
    }
}
