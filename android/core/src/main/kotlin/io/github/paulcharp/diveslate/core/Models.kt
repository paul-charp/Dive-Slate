package io.github.paulcharp.diveslate.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/**
 * The format-neutral dive model every parser produces and the renderer consumes.
 *
 * Nothing here knows about XML. Parsers reconcile their format's quirks into
 * these types, so adding a log format never touches the renderer.
 *
 * All quantities are canonical units (see [Units.kt]): metres, seconds, bar,
 * Celsius, litres. Every field a log may omit is nullable rather than a
 * sentinel zero — a dive with no recorded temperature must not render as a dive
 * at 0 °C. Kotlin makes that convention structural rather than remembered.
 *
 * Ported from the retired Python implementation; `conformance/logs/` pins the
 * parsed shape and every derived figure.
 */

/**
 * How far below the ceiling a diver still counts as "at" their stop, in metres.
 *
 * Stops are held by eye against a fluctuating computer reading, so an exact
 * match would only ever register on a simulated profile.
 */
const val DECO_STOP_TOLERANCE_M = 3.0

/** A breathing mix, stored as fractions of 1. */
data class GasMix(val o2: Double = 0.21, val he: Double = 0.0) {

    val n2: Double get() = 1.0 - o2 - he

    /**
     * Dive computers round-trip 21% through decimal strings, so this compares
     * with a tolerance rather than testing equality against 0.21.
     */
    val isAir: Boolean get() = abs(o2 - 0.21) < 0.005 && he < 0.005

    /** Short label divers actually use: `Air`, `EAN32`, `Tx18/45`, `O2`. */
    val name: String
        get() {
            val o2Pct = round(o2 * 100).toInt()
            val hePct = round(he * 100).toInt()
            if (hePct > 0) return "Tx$o2Pct/$hePct"
            if (o2Pct >= 99) return "O2"
            if (isAir) return "Air"
            return "EAN$o2Pct"
        }

    /** Maximum operating depth in metres at [ppo2Max], in salt water. */
    fun modMetres(ppo2Max: Double = 1.4): Double = (ppo2Max / o2 - 1.0) * 10.0
}

val AIR = GasMix()

/** A cylinder carried on the dive. */
data class Cylinder(
    val gas: GasMix = AIR,
    val description: String? = null,
    val sizeLitres: Double? = null,
    val workPressureBar: Double? = null,
    val startBar: Double? = null,
    val endBar: Double? = null,
) {
    val usedBar: Double?
        get() = if (startBar == null || endBar == null) null else startBar - endBar

    /** Free-gas volume consumed, litres at surface pressure. */
    val usedLitres: Double?
        get() {
            val used = usedBar ?: return null
            val size = sizeLitres ?: return null
            return used * size
        }

    val label: String get() = description ?: gas.name
}

/**
 * One instant on the profile.
 *
 * Most fields are optional: dive computers record different subsets, and
 * Subsurface only writes an attribute when its value *changes*. Parsers carry
 * values forward so that consumers here see a fully populated series.
 */
data class Sample(
    val timeSeconds: Double,
    val depthMetres: Double,
    val tempCelsius: Double? = null,
    val ndlSeconds: Double? = null,
    val ttsSeconds: Double? = null,
    val inDeco: Boolean = false,
    val stopDepthMetres: Double? = null,
    val stopTimeSeconds: Double? = null,
    val cns: Double? = null,
    val pressureBar: Double? = null,
)

/** A change of breathing gas at a point in time. */
data class GasSwitch(
    val timeSeconds: Double,
    val gas: GasMix,
    val cylinderIndex: Int? = null,
)

/** A contiguous stretch of the dive spent in decompression obligation. */
data class DecoSpan(val startSeconds: Double, val endSeconds: Double) {
    val durationSeconds: Double get() = endSeconds - startSeconds
}

/** A single dive: metadata, gas, and the sampled profile. */
data class Dive(
    val samples: List<Sample> = emptyList(),
    val cylinders: List<Cylinder> = emptyList(),
    val gasSwitches: List<GasSwitch> = emptyList(),

    val number: Int? = null,
    val whenLogged: LocalDateTime? = null,
    val site: String? = null,
    val buddy: String? = null,
    val notes: String? = null,
    val rating: Int? = null,

    val durationSeconds: Double? = null,
    val maxDepthMetres: Double? = null,
    val meanDepthMetres: Double? = null,
    val waterTempCelsius: Double? = null,
    val surfacePressureBar: Double? = null,
    val salinityGramsPerLitre: Double? = null,

    val sacLitresPerMin: Double? = null,
    val otu: Double? = null,
    val cns: Double? = null,

    val computer: String? = null,
    val decoModel: String? = null,
    val tags: List<String> = emptyList(),
) {

    // ---- derived views over the sample series --------------------------------

    /** Deepest sampled depth, falling back to the logged summary value. */
    val computedMaxDepthMetres: Double
        get() = samples.maxOfOrNull { it.depthMetres } ?: maxDepthMetres ?: 0.0

    /** Profile length, falling back to the logged summary value. */
    val computedDurationSeconds: Double
        get() = samples.lastOrNull()?.timeSeconds ?: durationSeconds ?: 0.0

    /**
     * Time-weighted average depth over the sampled profile.
     *
     * Prefers the logged value: a computer averages over its own raw series,
     * which is usually finer than what it exports.
     */
    val computedMeanDepthMetres: Double?
        get() {
            meanDepthMetres?.let { return it }
            if (samples.size < 2) return null
            var area = 0.0
            for (i in 0 until samples.size - 1) {
                val previous = samples[i]
                val current = samples[i + 1]
                area += (previous.depthMetres + current.depthMetres) / 2.0 *
                    (current.timeSeconds - previous.timeSeconds)
            }
            val total = samples.last().timeSeconds - samples.first().timeSeconds
            return if (total > 0) area / total else null
        }

    val temperatureRangeCelsius: Pair<Double, Double>?
        get() {
            val temps = samples.mapNotNull { it.tempCelsius }
            if (temps.isEmpty()) return null
            return temps.min() to temps.max()
        }

    /**
     * Contiguous stretches where the computer showed a deco obligation.
     *
     * A span is closed at the first sample that clears the obligation, so a dive
     * that surfaces still in deco yields a span ending at the last sample.
     */
    fun decoSpans(): List<DecoSpan> {
        val spans = mutableListOf<DecoSpan>()
        var start: Double? = null
        for (sample in samples) {
            if (sample.inDeco && start == null) {
                start = sample.timeSeconds
            } else if (!sample.inDeco && start != null) {
                spans.add(DecoSpan(start, sample.timeSeconds))
                start = null
            }
        }
        if (start != null && samples.isNotEmpty()) {
            spans.add(DecoSpan(start, samples.last().timeSeconds))
        }
        return spans
    }

    /**
     * Time actually spent decompressing, or `null` on a no-stop dive.
     *
     * This is **not** the total length of [decoSpans], and the difference is
     * large enough to matter. An obligation appears the moment the computer's
     * ceiling leaves the surface — typically while the diver is still on the
     * bottom — so the obligation span covers most of the dive. On the reference
     * dive those are 50:06 and 23:20; reporting the first as "deco" claims fifty
     * minutes of stops that never happened.
     *
     * What a diver means by deco time is the hang: from first reaching the
     * ceiling on the way up until the obligation clears. [toleranceMetres] is
     * the slack allowed in "reaching" it, since nobody holds a stop to the
     * centimetre.
     *
     * Each span is measured against the end of *its own* obligation and the
     * hangs summed, so a dive that clears deco and re-incurs it served two
     * hangs and the cleared interval between them is not counted. Spans whose
     * ceiling was never reached contribute nothing rather than voiding the
     * hangs that were served.
     */
    fun decoTimeSeconds(toleranceMetres: Double = DECO_STOP_TOLERANCE_M): Double? {
        val spans = decoSpans()
        if (spans.isEmpty()) return null

        var total = 0.0
        var served = false
        for (span in spans) {
            for (sample in samples) {
                if (sample.timeSeconds < span.startSeconds) continue
                if (sample.timeSeconds >= span.endSeconds) break
                val ceiling = sample.stopDepthMetres
                if (sample.inDeco &&
                    ceiling != null && ceiling != 0.0 &&
                    sample.depthMetres <= ceiling + toleranceMetres
                ) {
                    total += span.endSeconds - sample.timeSeconds
                    served = true
                    break
                }
            }
        }
        // An obligation that never got served — the dive ended still in deco.
        return if (served) total else null
    }

    /**
     * `(low, high)` gradient factors, read out of the deco model string.
     *
     * There is no dedicated field for these. Computers write them into a free
     * text label — Shearwater logs `"GF 70/80"`, others `"ZHL16C GF30/85"` — so
     * they are recovered by pattern rather than parsed. Anything that does not
     * look like a pair of percentages returns `null` instead of a guess; a
     * VPM-B dive has no gradient factors at all and must not be made to appear
     * as though it does.
     */
    val gradientFactors: Pair<Int, Int>?
        get() {
            val model = decoModel ?: return null
            val match = GRADIENT_FACTOR.find(model) ?: return null
            val low = match.groupValues[1].toInt()
            val high = match.groupValues[2].toInt()
            // Gradient factors are percentages, and low never exceeds high.
            if (low !in 1..100 || high !in 1..100 || low > high) return null
            return low to high
        }

    /**
     * Total free gas consumed across every cylinder, litres at the surface.
     *
     * `null` unless at least one cylinder records a size *and* both start and
     * end pressures. Cylinders that came back fuller than they went in are
     * dropped rather than subtracted: that is a mistyped pressure, and letting
     * it cancel out real consumption from another tank would quietly understate
     * the total.
     */
    val gasUsedLitres: Double?
        get() {
            val volumes = cylinders.mapNotNull { it.usedLitres }.filter { it > 0 }
            return if (volumes.isEmpty()) null else volumes.sum()
        }

    /** `(label, litres)` per cylinder that recorded enough to compute it. */
    val gasUsedByCylinder: List<Pair<String, Double>>
        get() = cylinders.mapNotNull { cylinder ->
            cylinder.usedLitres?.takeIf { it > 0 }?.let { cylinder.label to it }
        }

    /** The mix being breathed at [timeSeconds], or `null` before the first switch. */
    fun gasAt(timeSeconds: Double): GasMix? {
        var current: GasMix? = null
        for (switch in gasSwitches) {
            if (switch.timeSeconds > timeSeconds) break
            current = switch.gas
        }
        return current
    }

    /** A human label for the dive, best-effort from whatever the log carries. */
    val title: String
        get() {
            val parts = mutableListOf<String>()
            number?.let { parts.add("#$it") }
            if (!site.isNullOrEmpty()) {
                parts.add(site)
            } else if (whenLogged != null) {
                parts.add(whenLogged.format(DATE_ONLY))
            }
            return if (parts.isEmpty()) "Dive" else parts.joinToString(" · ")
        }

    private companion object {
        val GRADIENT_FACTOR = Regex("""(\d{1,3})\s*/\s*(\d{1,3})""")
        val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

/** A parsed log file: one or more dives plus the program that wrote it. */
data class DiveLog(
    val dives: List<Dive> = emptyList(),
    val program: String? = null,
    val source: String? = null,
    val sites: Map<String, String> = emptyMap(),
) : Iterable<Dive> {

    override fun iterator(): Iterator<Dive> = dives.iterator()

    val size: Int get() = dives.size

    operator fun get(index: Int): Dive = dives[index]

    /** The single dive in this log, erroring if the count is not exactly one. */
    fun only(): Dive {
        require(dives.size == 1) {
            "expected exactly one dive, found ${dives.size}; select one explicitly"
        }
        return dives[0]
    }
}
