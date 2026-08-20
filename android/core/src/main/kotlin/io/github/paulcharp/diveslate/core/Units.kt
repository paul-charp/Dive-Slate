package io.github.paulcharp.diveslate.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Parsers for the quantity strings dive logs write into XML attributes.
 *
 * Subsurface stores quantities as human-readable strings with a trailing unit
 * (`depth='44.4 m'`, `time='62:18 min'`, `start='200.0 bar'`) rather than as
 * bare numbers, and the unit varies with the settings of the machine that
 * exported the file — the same field can arrive as `ft`, `psi` or `F`. Every
 * parser here normalises to one canonical unit and refuses input it does not
 * recognise, rather than silently assuming metric.
 *
 * Canonical units: metres, seconds, bar, degrees Celsius, litres.
 *
 * Ported from the Python implementation this project started as, which has
 * since been removed. `conformance/specs.json` is what survives of it, and it
 * pins the behaviour of every function here, rejected input included.
 */

/** Raised when a quantity string cannot be understood. */
class UnitException(message: String) : IllegalArgumentException(message)

/** A number followed by an optional unit word: `44.4 m`, `-1.2`, `206.843 bar`. */
private val QUANTITY = Regex("""^\s*([+-]?(?:\d+\.?\d*|\.\d+))\s*([^\s\d]*)\s*$""")

/** `62:18 min`, `1:02:03`, `44:20 min`. */
private val CLOCK = Regex("""^\s*(\d+(?::\d{1,2})+)\s*([a-z]*)\s*$""", RegexOption.IGNORE_CASE)

private const val FEET_PER_METRE = 3.280839895013123
private const val PSI_PER_BAR = 14.503773800721814
private const val CUFT_PER_LITRE = 0.035314666721488586

private fun split(raw: String, what: String): Pair<Double, String> {
    val match = QUANTITY.matchEntire(raw)
        ?: throw UnitException("cannot parse $what from '$raw'")
    return match.groupValues[1].toDouble() to match.groupValues[2].lowercase()
}

/** Parse a depth/length into metres. Accepts `m`, `ft`, or no unit. */
fun parseDepthMetres(raw: String): Double {
    val (value, unit) = split(raw, "depth")
    return when (unit) {
        "", "m", "meter", "meters", "metre", "metres" -> value
        "ft", "feet", "foot" -> value / FEET_PER_METRE
        else -> throw UnitException("unknown length unit '$unit' in '$raw'")
    }
}

/**
 * Parse a duration into seconds.
 *
 * Handles both the colon form Subsurface uses for sample and event times
 * (`'44:20 min'` — that is 44 minutes 20 seconds, *not* 44.2 minutes) and a
 * plain scalar with a unit (`'90 s'`, `'3 min'`, `'1.5 h'`).
 */
fun parseDurationSeconds(raw: String): Double {
    CLOCK.matchEntire(raw)?.let { clock ->
        val parts = clock.groupValues[1].split(":").map { it.toInt() }
        // Two parts are mm:ss, three are hh:mm:ss — the trailing 'min' label
        // Subsurface writes refers to the leading field, so it is not a scale.
        return when (parts.size) {
            2 -> parts[0] * 60.0 + parts[1]
            3 -> parts[0] * 3600.0 + parts[1] * 60.0 + parts[2]
            else -> throw UnitException("cannot parse duration from '$raw'")
        }
    }

    val (value, unit) = split(raw, "duration")
    return when (unit) {
        "s", "sec", "secs", "second", "seconds" -> value
        "", "min", "mins", "minute", "minutes" -> value * 60.0
        "h", "hr", "hrs", "hour", "hours" -> value * 3600.0
        else -> throw UnitException("unknown time unit '$unit' in '$raw'")
    }
}

/** Parse a gas pressure into bar. Accepts `bar`, `psi`, or no unit. */
fun parsePressureBar(raw: String): Double {
    val (value, unit) = split(raw, "pressure")
    return when (unit) {
        "", "bar", "bars" -> value
        "psi" -> value / PSI_PER_BAR
        else -> throw UnitException("unknown pressure unit '$unit' in '$raw'")
    }
}

/** Parse a temperature into degrees Celsius. Accepts `C`, `F`, `K`. */
fun parseTemperatureCelsius(raw: String): Double {
    val (value, unit) = split(raw, "temperature")
    return when (unit) {
        "", "c", "°c", "celsius" -> value
        "f", "°f", "fahrenheit" -> (value - 32.0) * 5.0 / 9.0
        "k", "kelvin" -> value - 273.15
        else -> throw UnitException("unknown temperature unit '$unit' in '$raw'")
    }
}

/** Parse a cylinder volume into litres. Accepts `l`, `cuft`, or none. */
fun parseVolumeLitres(raw: String): Double {
    val (value, unit) = split(raw, "volume")
    return when (unit) {
        "", "l", "ℓ", "liter", "liters", "litre", "litres" -> value
        "cuft", "cf", "ft3" -> value / CUFT_PER_LITRE
        else -> throw UnitException("unknown volume unit '$unit' in '$raw'")
    }
}

/** Parse a percentage into a fraction of 1. `'31%'` becomes `0.31`. */
fun parsePercent(raw: String): Double {
    val (value, unit) = split(raw, "percentage")
    if (unit != "" && unit != "%") {
        throw UnitException("unknown percentage unit '$unit' in '$raw'")
    }
    return value / 100.0
}

/**
 * Whole minutes, always rounded up.
 *
 * Rounding up rather than to nearest is the diving convention — a 64:20 dive is
 * a 65-minute dive, never a 64-minute one — and it keeps a badge from ever
 * understating a figure.
 *
 * The epsilon absorbs float noise so an exactly-round value does not tip into
 * the next minute: 3600 s must be 60 min, not 61.
 */
fun ceilMinutes(seconds: Double): Int = ceil(seconds / 60.0 - 1e-9).toInt()

/** Whole metres, always rounded up. 44.4 m becomes 45 m. */
fun ceilMetres(metres: Double): Int = ceil(metres - 1e-9).toInt()

/**
 * `(value, unit)` for a duration rounded up to the minute.
 *
 * Past an hour the value switches to `h:mm` and the unit goes empty, because
 * "1:05" already reads as a time whereas "65 min" makes the reader do the
 * division themselves.
 */
fun formatMinutes(seconds: Double): Pair<String, String> {
    val minutes = ceilMinutes(seconds)
    if (minutes >= 60) {
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}" to ""
    }
    return minutes.toString() to "min"
}

/**
 * Render seconds as `m:ss`, or `h:mm:ss` past an hour — for labels.
 *
 * Note on ties: Python's `round` and Kotlin's [kotlin.math.round] both round
 * halves to even, so a sample landing on an exact half-second agrees across the
 * two implementations. `Math.round` would not — it rounds halves up — so do not
 * "simplify" this to that.
 */
fun formatDuration(seconds: Double): String {
    val total = kotlin.math.round(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    val paddedSecs = secs.toString().padStart(2, '0')
    if (hours > 0L) {
        return "$hours:${minutes.toString().padStart(2, '0')}:$paddedSecs"
    }
    return "$minutes:$paddedSecs"
}

// ---------------------------------------------------------------------------
// what the slate prints

/**
 * Which system the figures are *printed* in.
 *
 * Nothing above this line is affected: parsing still normalises everything to
 * metres, seconds, bar, Celsius and litres, whatever the exporting computer was
 * set to, and the models stay canonical. This is a presentation choice made at
 * the far end, so a log written in feet and one written in metres produce the
 * same slate — the diver reading it decides which units that slate speaks, not
 * whichever machine happened to write the file.
 *
 * Two systems and not a per-figure grid. A slate mixing feet with litres would
 * be a set of numbers nobody's training pairs, and the point of the badge is to
 * be read at a glance.
 */
enum class SlateUnits(val id: String, val label: String) {
    /** Metres, degrees Celsius, litres. */
    METRIC("metric", "Metric"),

    /** Feet, degrees Fahrenheit, cubic feet. */
    IMPERIAL("imperial", "Imperial");

    /** What a depth is labelled with. */
    val depthLabel: String get() = if (this == METRIC) "m" else "ft"

    companion object {
        fun byId(id: String): SlateUnits? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Whole feet, always rounded up — the same convention as [ceilMetres].
 *
 * Rounding is applied to the converted value rather than to the metres, because
 * 44.4 m is 145.7 ft and a diver reading feet expects 146, not the 148 that
 * converting an already-rounded 45 would give. Round once, at the end, in the
 * units being printed.
 */
fun ceilFeet(metres: Double): Int = ceil(metres * FEET_PER_METRE - 1e-9).toInt()

/** Whole depth units, rounded up, in the system being printed. */
fun ceilDepth(metres: Double, units: SlateUnits): Int =
    if (units == SlateUnits.METRIC) ceilMetres(metres) else ceilFeet(metres)

/** `(value, unit)` for a depth. */
fun depthFigure(metres: Double, units: SlateUnits): Pair<String, String> =
    ceilDepth(metres, units).toString() to units.depthLabel

/**
 * `(value, unit)` for a temperature, rounded to the nearest degree.
 *
 * To nearest rather than up: water temperature is a reading rather than a
 * figure anyone's limits are computed against, and the ceiling convention is
 * there so a badge never understates depth or runtime.
 */
fun temperatureFigure(celsius: Double, units: SlateUnits): Pair<String, String> =
    if (units == SlateUnits.METRIC) {
        celsius.roundToInt().toString() to "°C"
    } else {
        (celsius * 9.0 / 5.0 + 32.0).roundToInt().toString() to "°F"
    }

/**
 * `(value, unit)` for a volume of gas breathed.
 *
 * `cf` rather than `cu ft` or `ft³`. It is what a shop means by an 80, it is
 * two characters wide in a column a corner badge only gives 130px of, and the
 * superscript form would be the one glyph on the slate whose width the
 * character-count estimate in [SlateFont] cannot be relied on for.
 */
fun volumeFigure(litres: Double, units: SlateUnits): Pair<String, String> =
    if (units == SlateUnits.METRIC) {
        litres.roundToInt().toString() to "L"
    } else {
        (litres * CUFT_PER_LITRE).roundToInt().toString() to "cf"
    }

/**
 * `(value, unit)` for a consumption rate.
 *
 * Two decimals in cubic feet against one in litres, because the imperial figure
 * is around 0.6 and one decimal would round several real dives to the same
 * number.
 */
fun consumptionFigure(litresPerMin: Double, units: SlateUnits): Pair<String, String> =
    if (units == SlateUnits.METRIC) {
        String.format(Locale.ENGLISH, "%.1f", litresPerMin) to "L/min"
    } else {
        String.format(Locale.ENGLISH, "%.2f", litresPerMin * CUFT_PER_LITRE) to "cf/min"
    }

/** A depth in metres, as the number the slate would print — unrounded. */
fun depthInUnits(metres: Double, units: SlateUnits): Double =
    if (units == SlateUnits.METRIC) metres else metres * FEET_PER_METRE

/** A depth quoted in display units, back to the metres the geometry works in. */
fun metresOfDepth(value: Double, units: SlateUnits): Double =
    if (units == SlateUnits.METRIC) value else value / FEET_PER_METRE
