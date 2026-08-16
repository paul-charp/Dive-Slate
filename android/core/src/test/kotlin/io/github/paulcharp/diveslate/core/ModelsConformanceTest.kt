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
 * Holds the model's derived figures to the Python implementation.
 *
 * The dives are rebuilt from the fixture's own sample series rather than by
 * parsing the source log, so this exercises the model in isolation — a failure
 * here is the model's, not a parser's. The parsers get their own conformance
 * test once they are ported, comparing the whole parsed structure.
 *
 * `reference.ssrf` is what makes this worth running: 1930 samples off a real
 * Shearwater, with an obligation owed for 50:06 against a hang of 23:20. The
 * gap between those two numbers is the bug this project has already shipped
 * once.
 */
class ModelsConformanceTest {

    private companion object {
        const val TOLERANCE = 1e-6
    }

    // ---- JSON helpers ------------------------------------------------------

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
        assertTrue(
            abs(expected - actual) < TOLERANCE,
            "$what: expected $expected, got $actual",
        )
    }

    // ---- rebuilding a Dive from its fixture --------------------------------

    private fun gasOf(obj: JsonObject) = GasMix(
        o2 = obj.double("o2") ?: 0.21,
        he = obj.double("he") ?: 0.0,
    )

    private fun diveOf(obj: JsonObject): Dive = Dive(
        samples = obj.getValue("samples").jsonArray.map { element ->
            val s = element.jsonObject
            Sample(
                timeSeconds = s.double("time_s")!!,
                depthMetres = s.double("depth_m")!!,
                tempCelsius = s.double("temp_c"),
                ndlSeconds = s.double("ndl_s"),
                ttsSeconds = s.double("tts_s"),
                inDeco = s.bool("in_deco"),
                stopDepthMetres = s.double("stop_depth_m"),
                stopTimeSeconds = s.double("stop_time_s"),
                cns = s.double("cns"),
                pressureBar = s.double("pressure_bar"),
            )
        },
        cylinders = obj.getValue("cylinders").jsonArray.map { element ->
            val c = element.jsonObject
            Cylinder(
                gas = gasOf(c.getValue("gas").jsonObject),
                description = c.string("description"),
                sizeLitres = c.double("size_l"),
                workPressureBar = c.double("work_pressure_bar"),
                startBar = c.double("start_bar"),
                endBar = c.double("end_bar"),
            )
        },
        gasSwitches = obj.getValue("gas_switches").jsonArray.map { element ->
            val g = element.jsonObject
            GasSwitch(
                timeSeconds = g.double("time_s")!!,
                gas = gasOf(g.getValue("gas").jsonObject),
                cylinderIndex = g.int("cylinder_index"),
            )
        },
        number = obj.int("number"),
        whenLogged = obj.string("when")?.let { LocalDateTime.parse(it) },
        site = obj.string("site"),
        buddy = obj.string("buddy"),
        notes = obj.string("notes"),
        rating = obj.int("rating"),
        durationSeconds = obj.double("duration_s"),
        maxDepthMetres = obj.double("max_depth_m"),
        meanDepthMetres = obj.double("mean_depth_m"),
        waterTempCelsius = obj.double("water_temp_c"),
        surfacePressureBar = obj.double("surface_pressure_bar"),
        salinityGramsPerLitre = obj.double("salinity_g_l"),
        sacLitresPerMin = obj.double("sac_l_min"),
        otu = obj.double("otu"),
        cns = obj.double("cns"),
        computer = obj.string("computer"),
        decoModel = obj.string("deco_model"),
        tags = obj.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
    )

    private fun eachDive(check: (label: String, dive: Dive, derived: JsonObject) -> Unit) {
        assertTrue(Fixtures.logs.isNotEmpty(), "no log fixtures found")
        for (file in Fixtures.logs) {
            val payload = Fixtures.read(file)
            for ((index, element) in payload.getValue("dives").jsonArray.withIndex()) {
                val obj = element.jsonObject
                check("${file.name}[$index]", diveOf(obj), obj.getValue("derived").jsonObject)
            }
        }
    }

    // ---- the checks --------------------------------------------------------

    @Test
    fun `depth and duration match the oracle`() = eachDive { label, dive, derived ->
        assertClose(derived.double("computed_max_depth_m"), dive.computedMaxDepthMetres, "$label max depth")
        assertClose(derived.double("computed_duration_s"), dive.computedDurationSeconds, "$label duration")
        assertClose(derived.double("computed_mean_depth_m"), dive.computedMeanDepthMetres, "$label mean depth")
    }

    @Test
    fun `temperature range matches the oracle`() = eachDive { label, dive, derived ->
        val expected = derived["temperature_range_c"].orNull()?.jsonArray
        val actual = dive.temperatureRangeCelsius
        if (expected == null) {
            assertNull(actual, "$label temperature range")
        } else {
            assertNotNull(actual, "$label temperature range")
            assertClose(expected[0].jsonPrimitive.content.toDouble(), actual.first, "$label temp min")
            assertClose(expected[1].jsonPrimitive.content.toDouble(), actual.second, "$label temp max")
        }
    }

    @Test
    fun `deco spans match the oracle`() = eachDive { label, dive, derived ->
        val expected = derived.getValue("deco_spans").jsonArray
        val actual = dive.decoSpans()
        assertEquals(expected.size, actual.size, "$label span count")
        for ((i, element) in expected.withIndex()) {
            val span = element.jsonObject
            assertClose(span.double("start_s"), actual[i].startSeconds, "$label span $i start")
            assertClose(span.double("end_s"), actual[i].endSeconds, "$label span $i end")
            assertClose(span.double("duration_s"), actual[i].durationSeconds, "$label span $i duration")
        }
    }

    /**
     * The distinction this project got wrong once: deco time is the hang, not
     * the obligation. On the reference dive those differ by nearly 27 minutes.
     */
    @Test
    fun `deco time is the hang not the obligation`() = eachDive { label, dive, derived ->
        assertClose(derived.double("deco_time_s"), dive.decoTimeSeconds(), "$label deco time")
        assertClose(
            derived.double("deco_time_s_tolerance_0"),
            dive.decoTimeSeconds(toleranceMetres = 0.0),
            "$label deco time at zero tolerance",
        )
    }

    @Test
    fun `gradient factors match the oracle`() = eachDive { label, dive, derived ->
        val expected = derived["gradient_factors"].orNull()?.jsonArray
        val actual = dive.gradientFactors
        if (expected == null) {
            assertNull(actual, "$label gradient factors")
        } else {
            assertNotNull(actual, "$label gradient factors")
            assertEquals(expected[0].jsonPrimitive.content.toInt(), actual.first, "$label GF low")
            assertEquals(expected[1].jsonPrimitive.content.toInt(), actual.second, "$label GF high")
        }
    }

    @Test
    fun `gas consumption matches the oracle`() = eachDive { label, dive, derived ->
        assertClose(derived.double("gas_used_l"), dive.gasUsedLitres, "$label gas used")

        val expected = derived.getValue("gas_used_by_cylinder").jsonArray
        val actual = dive.gasUsedByCylinder
        assertEquals(expected.size, actual.size, "$label per-cylinder count")
        for ((i, element) in expected.withIndex()) {
            val entry = element.jsonObject
            assertEquals(entry.string("label"), actual[i].first, "$label cylinder $i label")
            assertClose(entry.double("litres"), actual[i].second, "$label cylinder $i litres")
        }
    }

    @Test
    fun `breathed mix at a given time matches the oracle`() = eachDive { label, dive, derived ->
        for (element in derived.getValue("gas_at").jsonArray) {
            val probe = element.jsonObject
            val time = probe.double("time_s")!!
            assertEquals(probe.string("mix"), dive.gasAt(time)?.name, "$label gas at $time")
        }
    }

    @Test
    fun `title matches the oracle`() = eachDive { label, dive, derived ->
        assertEquals(derived.string("title"), dive.title, "$label title")
    }

    /** The rounded headline figures a reader actually sees on the slate. */
    @Test
    fun `slate figures round up`() = eachDive { label, dive, derived ->
        val slate = derived.getValue("slate").jsonObject
        val duration = dive.computedDurationSeconds

        assertEquals(
            slate.int("max_depth_ceil_m"),
            ceilMetres(dive.computedMaxDepthMetres),
            "$label ceil metres",
        )
        assertEquals(slate.int("duration_ceil_min"), ceilMinutes(duration), "$label ceil minutes")
        assertEquals(
            slate.getValue("duration_formatted").jsonArray.map { it.jsonPrimitive.content },
            formatMinutes(duration).toList(),
            "$label formatted minutes",
        )
        assertEquals(slate.string("duration_clock"), formatDuration(duration), "$label clock")
    }

    /**
     * The synthetic deco profiles, which the real logs cannot stand in for.
     *
     * Every log in `tests/data` carries a single deco span, so none of them can
     * tell a correct implementation from one that pairs the first ceiling
     * arrival with the *last* span's end — the defect this project carried until
     * recently. `reincurred` is the case that separates them: two hangs of ten
     * minutes each, with a cleared interval between that must not be counted.
     */
    @Test
    fun `synthetic deco profiles match the oracle`() {
        val cases = Fixtures.specs.getValue("deco_cases").jsonArray
        assertTrue(cases.isNotEmpty(), "no deco cases in specs.json")

        for (element in cases) {
            val case = element.jsonObject
            val name = case.string("name")
            val dive = Dive(
                samples = case.getValue("samples").jsonArray.map {
                    val s = it.jsonObject
                    Sample(
                        timeSeconds = s.double("time_s")!!,
                        depthMetres = s.double("depth_m")!!,
                        inDeco = s.bool("in_deco"),
                        stopDepthMetres = s.double("stop_depth_m"),
                    )
                },
            )

            val expectedSpans = case.getValue("deco_spans").jsonArray
            val spans = dive.decoSpans()
            assertEquals(expectedSpans.size, spans.size, "$name span count")
            for ((i, spanElement) in expectedSpans.withIndex()) {
                val span = spanElement.jsonObject
                assertClose(span.double("start_s"), spans[i].startSeconds, "$name span $i start")
                assertClose(span.double("end_s"), spans[i].endSeconds, "$name span $i end")
            }

            assertClose(case.double("deco_time_s"), dive.decoTimeSeconds(), "$name deco time")
            assertClose(
                case.double("deco_time_s_tolerance_0"),
                dive.decoTimeSeconds(toleranceMetres = 0.0),
                "$name deco time at zero tolerance",
            )
        }
    }

    @Test
    fun `gas names match the oracle`() {
        for (element in Fixtures.specs.getValue("gas_names").jsonArray) {
            val case = element.jsonObject
            val mix = GasMix(o2 = case.double("o2")!!, he = case.double("he")!!)
            assertEquals(case.string("name"), mix.name, "GasMix(${mix.o2}, ${mix.he}).name")
        }
    }

    /**
     * Recovery from a free-text label, including everything that must *not* be
     * mistaken for a pair of percentages — a VPM-B dive, a version string, a date.
     */
    @Test
    fun `gradient factor recovery matches the oracle`() {
        for (element in Fixtures.specs.getValue("gradient_factors").jsonArray) {
            val case = element.jsonObject
            val model = case.string("deco_model")
            val dive = Dive(decoModel = if (model.isNullOrEmpty()) null else model)
            val expected = case["out"].orNull()?.jsonArray
            val actual = dive.gradientFactors
            if (expected == null) {
                assertNull(actual, "gradientFactors('$model')")
            } else {
                assertNotNull(actual, "gradientFactors('$model')")
                assertEquals(
                    listOf(expected[0].jsonPrimitive.content.toInt(), expected[1].jsonPrimitive.content.toInt()),
                    listOf(actual.first, actual.second),
                    "gradientFactors('$model')",
                )
            }
        }
    }
}
