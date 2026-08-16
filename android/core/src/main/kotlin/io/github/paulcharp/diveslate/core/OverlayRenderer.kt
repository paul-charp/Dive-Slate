package io.github.paulcharp.diveslate.core

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The compact slate: a badge to drop over a photo or a video frame.
 *
 * This is a different object from a chart, not a smaller version of one. A
 * chart is something you read axis values off; this is a badge — the profile
 * silhouette as a recognisable shape, three big numbers, nothing else. At a
 * third of frame width on a phone, axis ticks and a legend are unreadable
 * noise, so they are gone rather than shrunk.
 *
 * Three constraints from the medium drive the layout:
 *
 * * **The backdrop is arbitrary and moving.** Halos handle a still photo; they
 *   are not enough over video where the frame behind a label changes every few
 *   frames. Hence the scrim panel, on by default.
 * * **It gets scaled down.** Everything is sized generously so the slate
 *   survives being dropped in at 40% and re-encoded.
 * * **Its own size is the deliverable.** The slate renders at its natural
 *   compact size so it can be dragged around in an editor.
 *
 * Ported from `src/diveslate/render/overlay.py`.
 */

enum class SlateLayout { WIDE, TALL }

/** Shape and content of the compact slate. */
data class OverlayOptions(
    /**
     * Slate width in pixels. 1080 matches Instagram's native width, so a slate
     * dropped in at full width stays pixel-crisp.
     */
    val width: Float = 1080f,
    val theme: SlateTheme = SLATE,
    val showScrim: Boolean = true,
    val showSite: Boolean = true,
    val showDate: Boolean = false,
    val showCeiling: Boolean = true,
    /** Gas-switch markers and mix labels on the curve itself. */
    val showGas: Boolean = false,
    /**
     * Whether the derived deco time may take an automatic stat slot. It is an
     * inference from the ceiling rather than a figure the log states, so it is
     * worth being able to drop without hand-listing every other stat.
     */
    val showDeco: Boolean = true,
    /** Which summary values to show, in order. `null` picks automatically. */
    val stats: List<String>? = null,
    val maxStats: Int = 3,
    val layout: SlateLayout = SlateLayout.WIDE,
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
)

private val DATE_LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/** `(label, value, unit)` for one summary figure. */
data class SlateStat(val label: String, val value: String, val unit: String)

/**
 * Builders for every stat the slate can show.
 *
 * A candidate the log cannot answer returns null and is skipped, never shown
 * blank — the slate states what the dive recorded, and an empty slot inviting
 * the reader to assume a zero is worse than one fewer number.
 */
private val STAT_BUILDERS: Map<String, (Dive) -> SlateStat?> = mapOf(
    "depth" to { d -> SlateStat("Max depth", ceilMetres(d.computedMaxDepthMetres).toString(), "m") },
    "time" to { d ->
        val (value, unit) = formatMinutes(d.computedDurationSeconds)
        SlateStat("Runtime", value, unit)
    },
    "avg" to { d ->
        d.computedMeanDepthMetres?.let { SlateStat("Avg depth", ceilMetres(it).toString(), "m") }
    },
    "temp" to { d ->
        val range = d.temperatureRangeCelsius
        when {
            range != null -> SlateStat("Temp", range.first.roundToInt().toString(), "°C")
            d.waterTempCelsius != null && d.waterTempCelsius != 0.0 ->
                SlateStat("Temp", d.waterTempCelsius.roundToInt().toString(), "°C")
            else -> null
        }
    },
    "deco" to { d ->
        d.decoTimeSeconds()?.let {
            val (value, unit) = formatMinutes(it)
            SlateStat("Deco", value, unit)
        }
    },
    "gf" to { d -> d.gradientFactors?.let { SlateStat("GF", "${it.first}/${it.second}", "") } },
    "used" to { d -> d.gasUsedLitres?.let { SlateStat("Gas used", it.roundToInt().toString(), "L") } },
    "sac" to { d ->
        d.sacLitresPerMin?.let { SlateStat("SAC", String.format(Locale.ENGLISH, "%.1f", it), "L/min") }
    },
    "cns" to { d -> d.cns?.let { SlateStat("CNS", (it * 100).roundToInt().toString(), "%") } },
    // "Gases", comma-separated. Diverges from the Python, which labels this
    // "Gas" and joins with "/" — a slash reads as a ratio next to figures like
    // GF 70/80, and Tx18/45 already contains one, so "Tx18/45/O2" parses wrong
    // at a glance. Do not "restore" parity here.
    "gas" to { d ->
        if (d.gasSwitches.isEmpty()) null
        else SlateStat("Gases", d.gasSwitches.map { it.gas.name }.distinct().joinToString(", "), "")
    },
)

val STAT_KEYS: List<String> = STAT_BUILDERS.keys.toList()

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
private fun autoStats(dive: Dive, limit: Int, allowDeco: Boolean): List<SlateStat> {
    val (runtime, runtimeUnit) = formatMinutes(dive.computedDurationSeconds)
    val chosen = mutableListOf(
        SlateStat("Max depth", ceilMetres(dive.computedMaxDepthMetres).toString(), "m"),
        SlateStat("Runtime", runtime, runtimeUnit),
    )

    val order = listOfNotNull(if (allowDeco) "deco" else null, "gf", "used", "temp", "gas")
    for (key in order) {
        if (chosen.size >= limit) break
        STAT_BUILDERS.getValue(key)(dive)?.let { chosen.add(it) }
    }
    return chosen.take(limit)
}

private fun namedStats(dive: Dive, keys: List<String>): List<SlateStat> = keys.mapNotNull { key ->
    val builder = STAT_BUILDERS[key]
        ?: throw IllegalArgumentException(
            "unknown stat '$key'; available: ${STAT_KEYS.joinToString()}"
        )
    // Silently skip a stat the log cannot supply: asking for temperature on a
    // computer that never recorded it should not blank the whole slate.
    builder(dive)
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

/** Render the compact slate to a resolved list of drawing operations. */
fun renderOverlay(dive: Dive, options: OverlayOptions = OverlayOptions()): Slate {
    require(dive.samples.isNotEmpty()) {
        "this dive has no depth samples, so there is no profile to draw"
    }

    val theme = options.theme
    val stats = options.stats?.let { namedStats(dive, it) }
        ?: autoStats(dive, options.maxStats, allowDeco = options.showDeco)

    // Everything scales off the slate width so the design holds at any size;
    // the layout then chooses the proportions within that.
    val scale = options.width / 1080f
    val tall = options.layout == SlateLayout.TALL

    val pad = (if (tall) 56f else 44f) * scale
    val siteSize = (if (tall) 46f else 34f) * scale
    val dateSize = (if (tall) 28f else 22f) * scale
    val valueSize = (if (tall) 86f else 56f) * scale
    val labelSize = (if (tall) 24f else 18f) * scale
    val curveHeight = (if (tall) 430f else 210f) * scale
    val gap = (if (tall) 34f else 26f) * scale

    val showSite = options.showSite && !dive.site.isNullOrEmpty()
    val showDate = options.showDate && dive.whenLogged != null
    val hasHeading = showSite || showDate

    var headingBlock = 0f
    if (hasHeading) {
        if (showSite) headingBlock += siteSize
        if (showDate) headingBlock += dateSize + 8f * scale
        headingBlock += gap
    }

    val statsBlock = valueSize + labelSize + 10f * scale
    val height = pad + headingBlock + curveHeight + gap + statsBlock + pad

    val ops = mutableListOf<SlateOp>()

    if (options.showScrim) {
        // The slider moves this panel and nothing else, and cannot take it below
        // the opacity at which ink stops clearing 4.5:1 on the worst backdrop.
        val alpha = (options.scrimAlpha ?: theme.scrimAlphaNominal)
            .coerceIn(theme.scrimAlphaMin, 1f)
        ops.add(
            SlateOp.Rect(
                x = 0f, y = 0f, width = options.width, height = height,
                cornerRadius = options.cornerRadius * scale,
                fill = SlateFill.Solid(withAlpha(theme.scrim, alpha)),
            )
        )
    }

    // ---- heading -----------------------------------------------------------
    var y = pad
    if (showSite) {
        y += siteSize * 0.78f
        ops.add(
            SlateOp.Text(
                text = dive.site!!.uppercase(),
                x = pad, baselineY = y, sizePx = siteSize,
                fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 5f * scale,
                bold = true, letterSpacingEm = 0.02f,
            )
        )
        y += siteSize * 0.32f
    }
    if (showDate) {
        y += dateSize * 0.9f
        ops.add(
            SlateOp.Text(
                text = dive.whenLogged!!.format(DATE_LABEL),
                x = pad, baselineY = y, sizePx = dateSize,
                fillArgb = theme.inkSecondary, haloArgb = theme.halo, haloWidth = 4f * scale,
            )
        )
    }
    if (hasHeading) y = pad + headingBlock

    // ---- profile -----------------------------------------------------------
    val plotLeft = pad
    val plotRight = options.width - pad
    val plotTop = y
    val plotWidth = plotRight - plotLeft

    val duration = max(dive.computedDurationSeconds, 1.0)
    // Headroom so the deepest point does not touch the baseline.
    val depthMax = max(dive.computedMaxDepthMetres, 1.0) * 1.06

    fun sx(t: Double): Float = plotLeft + (t / duration).toFloat() * plotWidth
    fun sy(d: Double): Float = plotTop + (d / depthMax).toFloat() * curveHeight

    // Surface line: the reference the silhouette is read against, and the only
    // piece of chrome that survives into the badge. The depth axis always starts
    // at the surface, so this is where zero is.
    ops.add(
        SlateOp.Line(
            start = Pt(plotLeft, plotTop), end = Pt(plotRight, plotTop),
            argb = theme.axis, strokeWidth = 2f * scale,
        )
    )

    if (options.showCeiling) {
        ops.addAll(ceilingOps(dive, ::sx, ::sy, plotTop, theme, scale))
    }

    val points = envelope(dive.samples.map { Pt(sx(it.timeSeconds), sy(it.depthMetres)) }, plotWidth)
    val area = buildList {
        add(Pt(points.first().x, plotTop))
        addAll(points)
        add(Pt(points.last().x, plotTop))
    }
    ops.add(
        SlateOp.Path(
            points = area, closed = true,
            fill = SlateFill.Vertical(theme.curveFillTop, theme.curveFillBottom),
        )
    )
    ops.add(
        SlateOp.Path(
            points = points, closed = false,
            strokeArgb = theme.curve, strokeWidth = 4f * scale,
        )
    )

    if (options.showGas) {
        ops.addAll(gasOps(dive, ::sx, ::sy, theme, scale))
    }

    // ---- stats -------------------------------------------------------------
    val statsY = plotTop + curveHeight + gap + valueSize * 0.8f
    val slot = (options.width - pad * 2) / max(stats.size, 1)
    for ((index, stat) in stats.withIndex()) {
        val left = pad + index * slot
        ops.add(
            SlateOp.Text(
                text = stat.value, x = left, baselineY = statsY, sizePx = valueSize,
                fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 6f * scale,
                bold = true,
            )
        )
        if (stat.unit.isNotEmpty()) {
            // Advance estimated from character count, as the Python does. The
            // SVG writer could not measure text at all; a real measurement pass
            // belongs here later, and would only tighten this.
            ops.add(
                SlateOp.Text(
                    text = stat.unit,
                    x = left + stat.value.length * valueSize * 0.56f + 8f * scale,
                    baselineY = statsY, sizePx = labelSize * 1.35f,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                    haloWidth = 4f * scale,
                )
            )
        }
        ops.add(
            SlateOp.Text(
                text = stat.label.uppercase(),
                x = left, baselineY = statsY + labelSize + 8f * scale, sizePx = labelSize,
                fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = 4f * scale,
                bold = true, letterSpacingEm = 0.10f,
            )
        )
    }

    return Slate(width = options.width, height = height, ops = ops)
}

/** Stepped deco ceiling, hatched — the same reading as the full chart's. */
private fun ceilingOps(
    dive: Dive,
    sx: (Double) -> Float,
    sy: (Double) -> Float,
    plotTop: Float,
    theme: SlateTheme,
    scale: Float,
): List<SlateOp> {
    val runs = mutableListOf<List<Sample>>()
    var current = mutableListOf<Sample>()
    for (sample in dive.samples) {
        val ceiling = sample.stopDepthMetres
        if (ceiling != null && ceiling != 0.0) {
            current.add(sample)
        } else if (current.isNotEmpty()) {
            runs.add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) runs.add(current)
    if (runs.isEmpty()) return emptyList()

    val hatch = SlateFill.Hatch(
        argb = theme.ceiling, spacing = 9f * scale, strokeWidth = 1.8f * scale,
    )

    val ops = mutableListOf<SlateOp>()
    for (run in runs) {
        val edge = mutableListOf<Pt>()
        var previous: Float? = null
        for (sample in run) {
            val x = sx(sample.timeSeconds)
            val y = sy(sample.stopDepthMetres ?: 0.0)
            // Square the corner rather than sloping it: a ceiling steps.
            if (previous != null && y != previous) edge.add(Pt(x, previous))
            edge.add(Pt(x, y))
            previous = y
        }
        if (edge.isEmpty()) continue

        val polygon = buildList {
            add(Pt(edge.first().x, plotTop))
            addAll(edge)
            add(Pt(edge.last().x, plotTop))
        }
        ops.add(SlateOp.Path(points = polygon, closed = true, fill = hatch))
        ops.add(
            SlateOp.Path(
                points = edge, closed = false,
                strokeArgb = theme.ceiling, strokeWidth = 2.5f * scale,
            )
        )
    }
    return ops
}

/**
 * Gas-switch markers, each with the mix name printed beside it.
 *
 * The label is not optional. The accent colour sits below 3:1 contrast in some
 * palettes, which is permitted *only* because the text carries the identity —
 * drop the label to reduce clutter and the mark becomes one that colour alone
 * has to distinguish, which it cannot do under colour-vision deficiency.
 */
private fun gasOps(
    dive: Dive,
    sx: (Double) -> Float,
    sy: (Double) -> Float,
    theme: SlateTheme,
    scale: Float,
): List<SlateOp> {
    if (dive.samples.isEmpty()) return emptyList()
    val ops = mutableListOf<SlateOp>()

    for (switch in dive.gasSwitches) {
        val nearest = dive.samples.minBy { abs(it.timeSeconds - switch.timeSeconds) }
        val x = sx(switch.timeSeconds)
        val y = sy(nearest.depthMetres)

        ops.add(
            SlateOp.Circle(
                centre = Pt(x, y), radius = 8f * scale,
                strokeArgb = theme.halo, strokeWidth = 4f * scale,
            )
        )
        ops.add(SlateOp.Circle(centre = Pt(x, y), radius = 8f * scale, fillArgb = theme.accent))
        ops.add(
            SlateOp.Text(
                text = switch.gas.name,
                x = x + 12f * scale, baselineY = y - 12f * scale, sizePx = 20f * scale,
                fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 4f * scale,
                bold = true,
            )
        )
    }
    return ops
}
