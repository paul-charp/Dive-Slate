package io.github.paulcharp.diveslate.core

import kotlin.math.roundToInt

/**
 * What every style is asked for, and what every style is given.
 *
 * The slate is a badge to drop over a photo or a video frame. It is a different
 * object from a chart, not a smaller version of one: a chart is something you
 * read axis values off; this is the profile silhouette as a recognisable shape,
 * three big numbers, nothing else. At a third of frame width on a phone, axis
 * ticks and a legend are unreadable noise, so they are gone rather than shrunk.
 *
 * Three constraints from the medium bind every style, however it draws:
 *
 * * **The backdrop is arbitrary and moving.** Halos handle a still photo; they
 *   are not enough over video where the frame behind a label changes every few
 *   frames. Hence the scrim panel, on by default.
 * * **It gets scaled down.** Everything is sized generously so the slate
 *   survives being dropped in at 40% and re-encoded.
 * * **Its own size is the deliverable.** The slate renders at its natural
 *   compact size so it can be dragged around in an editor.
 *
 * This file holds what the styles share — the options, the summary figures and
 * the series reduction. The drawing lives in the style; see [SlateStyle].
 */

/** Shape and content of the compact slate. */
data class OverlayOptions(
    /**
     * Slate width in pixels. 1080 matches Instagram's native width, so a slate
     * dropped in at full width stays pixel-crisp.
     */
    val width: Float = SlateLayout.REFERENCE_WIDTH,
    /** The art direction: how the marks are drawn. */
    val style: SlateStyle = ModernStyle,
    /** The proportions: where things go and how big they are. */
    val layout: SlateLayout = SlateLayout.WIDE,
    /** The palette. Must be one [style] offers — see [SlateStyle.themes]. */
    val theme: SlateTheme = SLATE,
    /**
     * Which units the figures are printed in.
     *
     * A property of the reader, not of the log: the parsers normalise feet and
     * Fahrenheit to metres and Celsius on the way in whatever the exporting
     * computer was set to, so this is the only place the choice is made. See
     * [SlateUnits].
     */
    val units: SlateUnits = SlateUnits.METRIC,
    val showScrim: Boolean = true,
    val showSite: Boolean = true,
    val showDate: Boolean = false,
    val showCeiling: Boolean = true,
    /** Gas-switch markers and mix labels on the curve itself. */
    val showGas: Boolean = false,
    /**
     * Draw the profile as a flowing curve rather than as a polyline.
     *
     * On by default. A dive profile is a curve in the water and the polyline is
     * the sampling artefact, so the curve is the truer picture of the two — but
     * it stays a choice, because a reader looking for the shape of a sawtooth
     * bottom wants every tooth and not a line through them.
     *
     * Smoothing is allowed to change how the line *travels* between two depths.
     * It is not allowed to invent a third: the tangents are flat, so a segment
     * leaves one sample horizontally and arrives at the next horizontally and
     * stays inside the two depths it joins. A nicer-looking spline overshoots,
     * which here would draw the dive deeper than its deepest sample and put the
     * picture at odds with the figure printed beside it.
     *
     * The coarsening a curve wants is the other half, and it is the half that
     * could lie: see [SMOOTH_STEP_PX]. Every bucket keeps its shallowest *and*
     * deepest sample, so however coarse this gets, the deepest point of the dive
     * is still on the drawing.
     *
     * Not every style can honour it. One that resamples the profile for its own
     * reasons has already answered the question; see [SlateStyle.supportsSmooth],
     * which is what stops the UI offering a control that would do nothing.
     */
    val smoothProfile: Boolean = true,
    /**
     * Whether the derived deco time may take an automatic stat slot. It is an
     * inference from the ceiling rather than a figure the log states, so it is
     * worth being able to drop without hand-listing every other stat.
     */
    val showDeco: Boolean = true,
    /** Which summary values to show, in order. `null` picks automatically. */
    val stats: List<String>? = null,
    /**
     * A ceiling on the number of figures, over and above the layout's own.
     *
     * Unlimited by default, because the layout is the authority — see
     * [SlateLayout.maxFigures], which knows how many columns it has room for.
     * This is only here for a caller that wants fewer than the layout allows.
     * It used to default to three, which quietly held every layout to three no
     * matter what its own geometry could carry.
     */
    val maxStats: Int = Int.MAX_VALUE,
    val cornerRadius: Float = 30f,
    /**
     * Scrim opacity, or `null` to use the theme's own.
     *
     * This is the only opacity the user may change, and it moves the panel
     * alone — never the ink. Fading the marks would void the contrast the
     * palette gates enforce and turn the deliberately-unthemed hazard red into
     * a pink suggestion. Clamped to the theme's computed floor, below which the
     * panel has stopped doing its job.
     */
    val scrimAlpha: Float? = null,
) {
    /** This slate's proportions in pixels. */
    val metrics: LayoutMetrics get() = layout.metrics(width)

    /**
     * The scrim opacity to paint, clamped to the theme's legibility floor.
     *
     * Resolved here rather than in each style so that no style can ship a panel
     * fainter than the value at which ink stops clearing 4.5:1 on the worst
     * possible backdrop.
     */
    val resolvedScrimAlpha: Float
        get() = (scrimAlpha ?: theme.scrimAlphaNominal).coerceIn(theme.scrimAlphaMin, 1f)
}

/** `(label, value, unit)` for one summary figure. */
data class SlateStat(val label: String, val value: String, val unit: String)

/**
 * Builders for every stat the slate can show.
 *
 * A candidate the log cannot answer returns null and is skipped, never shown
 * blank — the slate states what the dive recorded, and an empty slot inviting
 * the reader to assume a zero is worse than one fewer number.
 *
 * Every builder takes the units rather than converting after the fact, because
 * a figure is a value *and* the unit it is labelled with, and the rounding
 * belongs to whichever of the two is being printed — see [ceilFeet]. Which
 * figures exist does not depend on the units, which is why [availableStats] can
 * ask in metric and answer for both.
 */
private val STAT_BUILDERS: Map<String, (Dive, SlateUnits) -> SlateStat?> = mapOf(
    "depth" to { d, u ->
        val (value, unit) = depthFigure(d.computedMaxDepthMetres, u)
        SlateStat("Max depth", value, unit)
    },
    "time" to { d, _ ->
        val (value, unit) = formatMinutes(d.computedDurationSeconds)
        SlateStat("Runtime", value, unit)
    },
    "avg" to { d, u ->
        d.computedMeanDepthMetres?.let {
            val (value, unit) = depthFigure(it, u)
            SlateStat("Avg depth", value, unit)
        }
    },
    "temp" to { d, u ->
        val celsius = d.temperatureRangeCelsius?.first
            ?: d.waterTempCelsius?.takeIf { it != 0.0 }
        celsius?.let {
            val (value, unit) = temperatureFigure(it, u)
            SlateStat("Temp", value, unit)
        }
    },
    "deco" to { d, _ ->
        d.decoTimeSeconds()?.let {
            val (value, unit) = formatMinutes(it)
            SlateStat("Deco", value, unit)
        }
    },
    "gf" to { d, _ -> d.gradientFactors?.let { SlateStat("GF", "${it.first}/${it.second}", "") } },
    "used" to { d, u ->
        d.gasUsedLitres?.let {
            val (value, unit) = volumeFigure(it, u)
            SlateStat("Gas used", value, unit)
        }
    },
    "sac" to { d, u ->
        d.sacLitresPerMin?.let {
            val (value, unit) = consumptionFigure(it, u)
            SlateStat("SAC", value, unit)
        }
    },
    "cns" to { d, _ -> d.cns?.let { SlateStat("CNS", (it * 100).roundToInt().toString(), "%") } },
    // "Gases", comma-separated. Diverges from the Python, which labels this
    // "Gas" and joins with "/" — a slash reads as a ratio next to figures like
    // GF 70/80, and Tx18/45 already contains one, so "Tx18/45/O2" parses wrong
    // at a glance. Do not "restore" parity here.
    "gas" to { d, _ ->
        if (d.gasSwitches.isEmpty()) null
        else SlateStat("Gases", d.gasSwitches.map { it.gas.name }.distinct().joinToString(", "), "")
    },
)

val STAT_KEYS: List<String> = STAT_BUILDERS.keys.toList()

/**
 * Which figures this dive can actually supply.
 *
 * The same question [resolveStats] answers by dropping what it cannot build,
 * asked ahead of the render so a caller can say so before drawing. It exists
 * for the batch: settings chosen while previewing one dive are applied to
 * every selected dive, and a figure the others never recorded is skipped
 * silently — which looks exactly like the log not recording it, the thing this
 * project refuses to let happen unwatched. Answering here lets the picker warn
 * instead.
 *
 * Every builder is run, so this costs a pass over the samples for the derived
 * figures. Fine once per selection; not something to call per frame.
 */
fun availableStats(dive: Dive): Set<String> =
    // In metric, and the answer holds for both: the units decide how a figure
    // is printed, never whether the log can supply it.
    STAT_BUILDERS.filterValues { it(dive, SlateUnits.METRIC) != null }.keys

/**
 * The figures this slate shows, in order.
 *
 * Shared across styles on purpose: which numbers are worth printing is a fact
 * about the dive log, not about the art direction. How they are typeset is the
 * style's business.
 *
 * The layout's budget binds a hand-picked list as firmly as an automatic one.
 * It has to: the columns are the layout's geometry, and a list that overruns
 * them would be typeset too small to read rather than refused. The excess is
 * taken off the end, so what survives is the front of the order the caller
 * asked for — but the UI is expected to enforce the budget in the picker, where
 * the user can see it, rather than leave it to be discovered here.
 */
internal fun resolveStats(dive: Dive, options: OverlayOptions): List<SlateStat> {
    val budget = minOf(options.maxStats, options.layout.maxFigures)
    val chosen = options.stats?.let { namedStats(dive, it, options.units) }
        ?: autoStats(dive, budget, allowDeco = options.showDeco, units = options.units)
    return chosen.take(budget)
}

/**
 * Most headline-worthy first.
 *
 * Depth and runtime lead because they are the two numbers every diver reads
 * first. After that: deco, gradient factors, gas used, temperature, mix. GF sits
 * beside deco because it is the setting that produced that deco time and the
 * two are read together. Consumption ranks above temperature because it varies
 * with how the dive was run, whereas water temperature is a property of the site
 * that day.
 */
private fun autoStats(
    dive: Dive,
    limit: Int,
    allowDeco: Boolean,
    units: SlateUnits,
): List<SlateStat> {
    val (depth, depthUnit) = depthFigure(dive.computedMaxDepthMetres, units)
    val (runtime, runtimeUnit) = formatMinutes(dive.computedDurationSeconds)
    val chosen = mutableListOf(
        SlateStat("Max depth", depth, depthUnit),
        SlateStat("Runtime", runtime, runtimeUnit),
    )

    val order = listOfNotNull(if (allowDeco) "deco" else null, "gf", "used", "temp", "gas")
    for (key in order) {
        if (chosen.size >= limit) break
        STAT_BUILDERS.getValue(key)(dive, units)?.let { chosen.add(it) }
    }
    return chosen.take(limit)
}

private fun namedStats(
    dive: Dive,
    keys: List<String>,
    units: SlateUnits,
): List<SlateStat> = keys.mapNotNull { key ->
    val builder = STAT_BUILDERS[key]
        ?: throw IllegalArgumentException(
            "unknown stat '$key'; available: ${STAT_KEYS.joinToString()}"
        )
    // Silently skip a stat the log cannot supply: asking for temperature on a
    // computer that never recorded it should not blank the whole slate.
    builder(dive, units)
}

/**
 * Reduce the sample series to about two points per horizontal pixel.
 *
 * A 2000-sample dive drawn into a 900px badge puts several samples on every
 * pixel column. Plain every-Nth decimation would drop the deepest point of a
 * column and visibly clip the spikes that give a profile its character, so each
 * column keeps its shallowest and deepest samples, emitted in the order they
 * occur so the line still reads left to right.
 */
internal fun envelope(points: List<Pt>, targetWidth: Float): List<Pt> {
    if (points.size <= targetWidth * 2) return points

    val columns = LinkedHashMap<Int, MutableList<Pt>>()
    for (p in points) columns.getOrPut(p.x.toInt()) { mutableListOf() }.add(p)

    val reduced = mutableListOf<Pt>()
    for (key in columns.keys.sorted()) {
        val bucket = columns.getValue(key)
        if (bucket.size <= 2) {
            reduced.addAll(bucket)
            continue
        }
        val shallowest = bucket.minBy { it.y }
        val deepest = bucket.maxBy { it.y }
        val pair = if (shallowest == deepest) {
            listOf(shallowest)
        } else {
            listOf(shallowest, deepest).sortedBy { bucket.indexOf(it) }
        }
        reduced.addAll(pair)
    }
    return reduced
}

/** Replace a packed colour's alpha, keeping its RGB. */
internal fun withAlpha(argb: Long, alpha: Float): Long {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
    return (a shl 24) or (argb and 0x00FFFFFFL)
}

/**
 * Render the compact slate to a resolved list of drawing operations.
 *
 * The entry point for every style, so that the two things which must hold
 * whatever the art direction hold in exactly one place: a dive with no profile
 * is refused rather than drawn empty, and a style is never handed a palette it
 * does not own.
 */
fun renderOverlay(dive: Dive, options: OverlayOptions = OverlayOptions()): Slate {
    require(dive.samples.isNotEmpty()) {
        "this dive has no depth samples, so there is no profile to draw"
    }
    // Not a formality. A palette is validated against the marks it will be
    // painted as, so one borrowed from another style has cleared the gates for
    // a picture nobody is drawing. Substituting quietly would hide that; the
    // caller reconciles deliberately, with SlateStyle.adopt.
    require(options.theme in options.style.themes) {
        "palette ${options.theme.name} is not offered by the ${options.style.id} style, " +
            "which offers ${options.style.themes.joinToString { it.name }}"
    }
    return options.style.render(dive, options)
}
