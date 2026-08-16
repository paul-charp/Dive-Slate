package io.github.paulcharp.diveslate.core

import java.time.LocalDateTime
import org.w3c.dom.Element

/**
 * Reader for UDDF (Universal Dive Data Format) 3.x logs.
 *
 * Two things differ sharply from the Subsurface reader:
 *
 * * **Units are strict SI and unlabelled.** Depths are metres, times are
 *   *seconds* (not the mm:ss Subsurface uses), temperatures are *Kelvin*,
 *   pressures are *Pascal*. There are no unit suffixes to check against, so a
 *   misread here is silent — hence the explicit conversions below rather than
 *   reuse of the unit parsers.
 * * **Namespaces vary by minor version** and some exporters emit none at all.
 *   Tags are matched on local name only.
 *
 * Unlike Subsurface, UDDF waypoints are self-contained: a value absent from a
 * waypoint is genuinely absent, not inherited. The one exception is the
 * breathing mix, set by a `<switchmix>` and holding until the next one.
 *
 * Ported from `src/diveslate/parsers/uddf.py`.
 */
object UddfParser {

    val extensions = listOf(".uddf", ".xml")
    const val FORMAT_NAME = "UDDF"

    private const val KELVIN_OFFSET = 273.15
    private const val PASCAL_PER_BAR = 100_000.0

    fun sniff(text: String): Boolean {
        val head = text.take(SNIFF_CHARS)
        return head.contains("<uddf") || (head.contains("uddf") && head.contains("<profiledata"))
    }

    fun parse(text: String, source: String? = null): DiveLog {
        val root = Xml.parse(text)
        if (root.local != "uddf") {
            throw ParseException("expected a <uddf> root, found <${root.local}>")
        }

        val mixes = parseMixes(root)
        val program = root.child("generator")?.textOf("name")

        val dives = root.findAll("dive")
            // <dive> also appears under <gasdefinitions> in some files; only
            // those carrying waypoints are profiles.
            .filter { it.child("samples") != null }
            .map { parseDive(it, mixes) }

        return DiveLog(dives = dives, program = program, source = source)
    }

    // ---- helpers -----------------------------------------------------------

    /** The trimmed text of a nested child, parsed as a number. */
    private fun Element?.numberAt(vararg path: String): Double? {
        if (this == null) return null
        val target = if (path.isEmpty()) this else descend(*path) ?: return null
        return target.text?.toDoubleOrNull()
    }

    /** Normalise a gas fraction that may have been written as a percentage. */
    private fun fraction(value: Double?): Double? =
        if (value == null) null else if (value > 1.0) value / 100.0 else value

    /** Build the `id` -> mix table that `<switchmix ref=...>` points into. */
    private fun parseMixes(root: Element): Map<String, GasMix> = buildMap {
        for (mixEl in root.findAll("mix")) {
            val id = mixEl.attr("id") ?: continue
            val he = fraction(mixEl.numberAt("he")) ?: 0.0
            val o2 = fraction(mixEl.numberAt("o2"))
                // Some writers give only n2/he and leave o2 implied by the rest.
                ?: fraction(mixEl.numberAt("n2"))?.let { maxOf(0.0, 1.0 - it - he) }
                ?: 0.21
            put(id, GasMix(o2 = o2, he = he))
        }
    }

    private fun parseWaypoints(
        samplesEl: Element,
        mixes: Map<String, GasMix>,
    ): Pair<List<Sample>, List<GasSwitch>> {
        val samples = mutableListOf<Sample>()
        val switches = mutableListOf<GasSwitch>()
        var currentGas: GasMix? = null

        for (wp in samplesEl.children("waypoint")) {
            val timeSeconds = wp.numberAt("divetime")
            val depthMetres = wp.numberAt("depth")
            if (timeSeconds == null || depthMetres == null) continue

            // A mix switch is recorded on the waypoint where it happens.
            wp.child("switchmix")?.attr("ref")?.let { ref ->
                val gas = mixes[ref]
                if (gas != null && gas != currentGas) {
                    switches.add(GasSwitch(timeSeconds = timeSeconds, gas = gas))
                    currentGas = gas
                }
            }

            val tempKelvin = wp.numberAt("temperature")
            val pressurePascal = wp.numberAt("tankpressure")

            // <decostop kind="mandatory"> is an obligation; kind="safety" is
            // not, and treating a safety stop as deco would shade half the
            // recreational dives ever logged.
            var stopDepthMetres: Double? = null
            var stopTimeSeconds: Double? = null
            var inDeco = false
            for (stopEl in wp.children("decostop")) {
                if ((stopEl.attr("kind") ?: "mandatory") != "mandatory") continue
                inDeco = true
                // Absent attributes stay null. The Python original produces NaN
                // here, which survives its own zero-check because NaN is truthy
                // and then silently poisons every ceiling comparison downstream.
                stopDepthMetres = stopEl.attr("decodepth")?.toDoubleOrNull()
                stopTimeSeconds = stopEl.attr("duration")?.toDoubleOrNull()
                break
            }

            // Some exporters signal the obligation with an alarm instead.
            if (!inDeco) {
                inDeco = wp.children("alarm").any { it.text == "deco" }
            }

            samples.add(
                Sample(
                    timeSeconds = timeSeconds,
                    depthMetres = depthMetres,
                    tempCelsius = tempKelvin?.let { it - KELVIN_OFFSET },
                    inDeco = inDeco,
                    stopDepthMetres = stopDepthMetres?.takeIf { it != 0.0 },
                    stopTimeSeconds = stopTimeSeconds,
                    pressureBar = pressurePascal?.let { it / PASCAL_PER_BAR },
                )
            )
        }

        return samples to switches
    }

    private fun parseWhen(diveEl: Element): LocalDateTime? {
        val raw = diveEl.descend("informationbeforedive", "datetime")?.text ?: return null
        return try {
            LocalDateTime.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDive(diveEl: Element, mixes: Map<String, GasMix>): Dive {
        var samples: List<Sample> = emptyList()
        var switches: List<GasSwitch> = emptyList()
        diveEl.child("samples")?.let { parseWaypoints(it, mixes).let { (s, w) -> samples = s; switches = w } }

        val before = diveEl.child("informationbeforedive")
        val after = diveEl.child("informationafterdive")
        val lowestKelvin = after.numberAt("lowesttemperature")

        // Cylinders are described per-dive by <tankdata>.
        val cylinders = diveEl.children("tankdata").map { tank ->
            val ref = tank.child("link")?.attr("ref")
            Cylinder(
                gas = mixes[ref ?: ""] ?: GasMix(),
                sizeLitres = tank.numberAt("tankvolume"),
                startBar = tank.numberAt("tankpressurebegin")?.let { it / PASCAL_PER_BAR },
                endBar = tank.numberAt("tankpressureend")?.let { it / PASCAL_PER_BAR },
            )
        }

        return Dive(
            samples = samples,
            cylinders = cylinders,
            gasSwitches = switches,
            number = before.numberAt("divenumber")?.toInt(),
            whenLogged = parseWhen(diveEl),
            durationSeconds = after.numberAt("diveduration"),
            maxDepthMetres = after.numberAt("greatestdepth"),
            meanDepthMetres = after.numberAt("averagedepth"),
            waterTempCelsius = lowestKelvin?.let { it - KELVIN_OFFSET },
        )
    }
}
