package io.github.paulcharp.diveslate.core

import java.time.LocalDateTime
import org.w3c.dom.Element

/**
 * Reader for Subsurface's native log format (`.ssrf`, sometimes `.xml`).
 *
 * The one thing worth knowing before reading this file: **Subsurface writes a
 * sample attribute only when its value changes.** A sample line carrying just a
 * time and a depth does not mean the temperature or the ceiling are unknown
 * there — it means they are whatever the previous sample said. Subsurface's own
 * reader copies the previous sample wholesale and then applies the attributes
 * present on the current line, and [parseSamples] reproduces that exactly. Get
 * this wrong and a 50-minute deco dive parses as one deco sample followed by
 * nothing.
 *
 * `tests/data/reference.ssrf` is the proof: 1930 samples, `in_deco` written
 * twice, 1503 samples carrying an obligation.
 *
 * Ported from `src/diveslate/parsers/subsurface.py`.
 */
object SubsurfaceParser {

    val extensions = listOf(".ssrf", ".xml")
    const val FORMAT_NAME = "Subsurface XML"

    fun sniff(text: String): Boolean = text.take(SNIFF_CHARS).contains("<divelog")

    fun parse(text: String, source: String? = null): DiveLog {
        val root = Xml.parse(text)
        if (root.local != "divelog") {
            throw ParseException("expected a <divelog> root, found <${root.local}>")
        }

        val sites = buildMap {
            root.child("divesites")?.children("site")?.forEach { site ->
                val uuid = site.attr("uuid")
                val name = site.attr("name")
                if (uuid != null && name != null) put(uuid, name)
            }
        }

        // Dives sit either directly under <dives> or inside a <trip>, and a log
        // can mix both. Matching only the direct children finds nothing at all
        // in a logbook where every dive belongs to a trip, which produces a log
        // that parses fine and contains no dives.
        val dives = root.child("dives")?.findAll("dive").orEmpty().map { parseDive(it, sites) }
        return DiveLog(dives = dives, program = root.attr("program"), source = source, sites = sites)
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Apply [convert] to an attribute, or return null if it is absent.
     *
     * Unparseable values are dropped rather than fatal: a stray unit from an
     * unusual computer should cost one field, not the whole dive.
     */
    private fun Element.optDouble(name: String, convert: (String) -> Double): Double? {
        val raw = attr(name) ?: return null
        return try {
            convert(raw)
        } catch (_: UnitException) {
            null
        }
    }

    private fun Element.optInt(name: String): Int? {
        val raw = attr(name) ?: return null
        return raw.trim().toDoubleOrNull()?.toInt()
    }

    /**
     * Read `o2`/`he` percentage attributes, defaulting to air.
     *
     * An absent or zero `o2` means air: Subsurface omits the attribute for air
     * cylinders, and some computers write `o2='0.0%'` to mean the same thing.
     */
    private fun gasFrom(element: Element): GasMix {
        val o2 = element.optDouble("o2", ::parsePercent)
        val he = element.optDouble("he", ::parsePercent) ?: 0.0
        if (o2 == null || o2 <= 0.0) {
            return if (he != 0.0) GasMix(o2 = AIR.o2, he = he) else AIR
        }
        return GasMix(o2 = o2, he = he)
    }

    private fun parseCylinders(diveEl: Element): List<Cylinder> =
        diveEl.children("cylinder").map { el ->
            Cylinder(
                gas = gasFrom(el),
                description = el.attr("description"),
                sizeLitres = el.optDouble("size", ::parseVolumeLitres),
                workPressureBar = el.optDouble("workpressure", ::parsePressureBar),
                startBar = el.optDouble("start", ::parsePressureBar),
                endBar = el.optDouble("end", ::parsePressureBar),
            )
        }

    /**
     * Collect `gaschange` events into an ordered switch list.
     *
     * The mix comes from the event's own `o2`/`he` when present and from the
     * referenced cylinder otherwise — computers are inconsistent about which
     * they write, and the cylinder index is the more reliable of the two.
     */
    private fun parseGasSwitches(computerEl: Element, cylinders: List<Cylinder>): List<GasSwitch> {
        val switches = mutableListOf<GasSwitch>()
        for (el in computerEl.children("event")) {
            if (el.attr("name") != "gaschange") continue
            val timeSeconds = el.optDouble("time", ::parseDurationSeconds) ?: continue

            val index = el.optInt("cylinder")
            val gas = when {
                el.attr("o2") != null -> gasFrom(el)
                index != null && index in cylinders.indices -> cylinders[index].gas
                else -> null
            } ?: continue

            switches.add(GasSwitch(timeSeconds = timeSeconds, gas = gas, cylinderIndex = index))
        }

        // Collapse repeats: a computer may re-announce the current mix (on
        // ascent, or after a bookmark) and each would otherwise draw a marker.
        val deduped = mutableListOf<GasSwitch>()
        for (switch in switches.sortedBy { it.timeSeconds }) {
            if (deduped.isNotEmpty() && deduped.last().gas == switch.gas) continue
            deduped.add(switch)
        }
        return deduped
    }

    /** Expand Subsurface's sparse sample lines into a fully populated series. */
    private fun parseSamples(computerEl: Element): List<Sample> {
        val samples = mutableListOf<Sample>()

        // Carried state, updated in place as attributes appear.
        var tempCelsius: Double? = null
        var ndlSeconds: Double? = null
        var ttsSeconds: Double? = null
        var inDeco = false
        var stopDepthMetres: Double? = null
        var stopTimeSeconds: Double? = null
        var cns: Double? = null
        var pressureBar: Double? = null

        for (el in computerEl.children("sample")) {
            val timeSeconds = el.optDouble("time", ::parseDurationSeconds)
            val depthMetres = el.optDouble("depth", ::parseDepthMetres)
            // A sample without a time or a depth places no point on the curve.
            if (timeSeconds == null || depthMetres == null) continue

            el.optDouble("temp", ::parseTemperatureCelsius)?.let { tempCelsius = it }
            el.optDouble("ndl", ::parseDurationSeconds)?.let { ndlSeconds = it }
            el.optDouble("tts", ::parseDurationSeconds)?.let { ttsSeconds = it }
            el.optDouble("stopdepth", ::parseDepthMetres)?.let { stopDepthMetres = it }
            el.optDouble("stoptime", ::parseDurationSeconds)?.let { stopTimeSeconds = it }
            el.optDouble("cns", ::parsePercent)?.let { cns = it }
            el.optDouble("pressure", ::parsePressureBar)?.let { pressureBar = it }
            el.attr("in_deco")?.let { inDeco = it.trim() == "1" }

            samples.add(
                Sample(
                    timeSeconds = timeSeconds,
                    depthMetres = depthMetres,
                    tempCelsius = tempCelsius,
                    ndlSeconds = ndlSeconds,
                    ttsSeconds = ttsSeconds,
                    inDeco = inDeco,
                    // A zero ceiling is "no ceiling", which reads better as null
                    // than as a stop at the surface. Note the carried value keeps
                    // the zero, so a later line that omits stopdepth still emits
                    // null rather than resurrecting an older ceiling.
                    stopDepthMetres = stopDepthMetres?.takeIf { it != 0.0 },
                    stopTimeSeconds = stopTimeSeconds,
                    cns = cns,
                    pressureBar = pressureBar,
                )
            )
        }
        return samples
    }

    private fun parseWhen(diveEl: Element): LocalDateTime? {
        val date = diveEl.attr("date") ?: return null
        val time = diveEl.attr("time") ?: "00:00:00"
        return try {
            LocalDateTime.parse("${date}T$time")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Choose the divecomputer to plot.
     *
     * A dive may carry several (a primary plus a backup, or an imported
     * duplicate). Prefer the one with the most samples, which is the richest
     * profile; fall back to the first so a dive logged without samples still
     * yields metadata.
     */
    private fun pickComputer(diveEl: Element): Element? =
        diveEl.children("divecomputer").maxByOrNull { it.children("sample").size }

    private fun parseDive(diveEl: Element, sites: Map<String, String>): Dive {
        val cylinders = parseCylinders(diveEl)
        val computerEl = pickComputer(diveEl)

        var samples: List<Sample> = emptyList()
        var switches: List<GasSwitch> = emptyList()
        var computer: String? = null
        var decoModel: String? = null
        var maxDepth: Double? = null
        var meanDepth: Double? = null
        var waterTemp: Double? = null
        var surfacePressure: Double? = null
        var salinity: Double? = null

        if (computerEl != null) {
            samples = parseSamples(computerEl)
            switches = parseGasSwitches(computerEl, cylinders)
            computer = computerEl.attr("model")

            computerEl.child("depth")?.let {
                maxDepth = it.optDouble("max", ::parseDepthMetres)
                meanDepth = it.optDouble("mean", ::parseDepthMetres)
            }
            computerEl.child("temperature")?.let {
                waterTemp = it.optDouble("water", ::parseTemperatureCelsius)
            }
            computerEl.child("surface")?.let {
                surfacePressure = it.optDouble("pressure", ::parsePressureBar)
            }
            computerEl.child("water")?.let { el ->
                salinity = el.optDouble("salinity") { it.replace("g/l", "").trim().toDouble() }
            }

            for (extra in computerEl.children("extradata")) {
                if (extra.attr("key") == "Deco model") decoModel = extra.attr("value")
            }
        }

        val tags = diveEl.attr("tags").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return Dive(
            samples = samples,
            cylinders = cylinders,
            gasSwitches = switches,
            number = diveEl.optInt("number"),
            whenLogged = parseWhen(diveEl),
            site = sites[diveEl.attr("divesiteid") ?: ""],
            buddy = diveEl.textOf("buddy"),
            notes = diveEl.textOf("notes"),
            rating = diveEl.optInt("rating"),
            durationSeconds = diveEl.optDouble("duration", ::parseDurationSeconds),
            maxDepthMetres = maxDepth,
            meanDepthMetres = meanDepth,
            waterTempCelsius = waterTemp,
            surfacePressureBar = surfacePressure,
            salinityGramsPerLitre = salinity,
            sacLitresPerMin = diveEl.optDouble("sac") {
                it.replace("l/min", "").trim().toDouble()
            },
            otu = diveEl.optDouble("otu") { it.trim().toDouble() },
            cns = diveEl.optDouble("cns", ::parsePercent),
            computer = computer,
            decoModel = decoModel,
            tags = tags,
        )
    }
}

/** How much of a file's head is examined when sniffing its format. */
internal const val SNIFF_CHARS = 4096
