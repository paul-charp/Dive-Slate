package io.github.paulcharp.diveslate.core

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The original look, and the default: flat, geometric, unornamented.
 *
 * A soft gradient wash under a clean depth curve, a hatched ceiling, set in a
 * plain sans on a rounded scrim panel. It reads as a piece of instrumentation
 * rather than as a souvenir, which is deliberate — the numbers are the point
 * and nothing decorative competes with them.
 *
 * Two decisions here are not stylistic and must survive into any style that
 * follows:
 *
 * * **The ceiling is hatched, not washed.** It is a region the diver must not
 *   enter, and a hatch reads as a barrier where a flat fill reads as just
 *   another series.
 * * **A gas switch always prints its mix name.** The accent sits below 3:1
 *   contrast in some palettes, which is permitted *only* because the text
 *   carries the identity. Drop the label and the mark becomes one that colour
 *   alone has to distinguish, which it cannot do under colour-vision
 *   deficiency.
 *
 * Ported from the retired Python implementation.
 */
object ModernStyle : SlateStyle {

    override val id: String = "modern"

    override val label: String = "Modern"

    override val description: String = "Flat and geometric. Reads as instrumentation."

    /**
     * Every palette in [SLATE_THEMES].
     *
     * The nine were validated against exactly these marks — a solid curve over
     * a two-stop wash, a hatched red ceiling, a filled accent dot — so they are
     * this style's to offer. A style that draws the profile differently enough
     * to move those measurements needs its own generated list, not a share of
     * this one.
     */
    override val themes: List<SlateTheme> = SLATE_THEMES

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val showSite = options.showSite && !dive.site.isNullOrEmpty()
        val showDate = options.showDate && dive.whenLogged != null
        val hasHeading = showSite || showDate

        var headingBlock = 0f
        if (hasHeading) {
            if (showSite) headingBlock += m.siteSize
            if (showDate) headingBlock += m.dateSize + m.px(8f)
            headingBlock += m.gap
        }

        val statsBlock = m.valueSize + m.labelSize + m.px(10f)
        val height = m.pad + headingBlock + m.curveHeight + m.gap + statsBlock + m.pad

        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            // The slider moves this panel and nothing else, and cannot take it
            // below the opacity at which ink stops clearing 4.5:1 on the worst
            // backdrop — see OverlayOptions.resolvedScrimAlpha.
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = m.width, height = height,
                    cornerRadius = m.px(options.cornerRadius),
                    fill = SlateFill.Solid(withAlpha(theme.scrim, options.resolvedScrimAlpha)),
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = m.pad
        if (showSite) {
            y += m.siteSize * 0.78f
            ops.add(
                SlateOp.Text(
                    text = dive.site!!.uppercase(),
                    x = m.pad, baselineY = y, sizePx = m.siteSize,
                    fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(5f),
                    bold = true, letterSpacingEm = 0.02f,
                )
            )
            y += m.siteSize * 0.32f
        }
        if (showDate) {
            y += m.dateSize * 0.9f
            ops.add(
                SlateOp.Text(
                    text = dive.whenLogged!!.format(DATE_LABEL),
                    x = m.pad, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo, haloWidth = m.px(4f),
                )
            )
        }
        if (hasHeading) y = m.pad + headingBlock

        // ---- profile -------------------------------------------------------
        val plotLeft = m.pad
        val plotRight = m.width - m.pad
        val plotTop = y
        val plotWidth = plotRight - plotLeft

        val duration = max(dive.computedDurationSeconds, 1.0)
        // Headroom so the deepest point does not touch the baseline.
        val depthMax = max(dive.computedMaxDepthMetres, 1.0) * 1.06

        fun sx(t: Double): Float = plotLeft + (t / duration).toFloat() * plotWidth
        fun sy(d: Double): Float = plotTop + (d / depthMax).toFloat() * m.curveHeight

        // Surface line: the reference the silhouette is read against, and the
        // only piece of chrome that survives into the badge. The depth axis
        // always starts at the surface, so this is where zero is.
        ops.add(
            SlateOp.Line(
                start = Pt(plotLeft, plotTop), end = Pt(plotRight, plotTop),
                argb = theme.axis, strokeWidth = m.px(2f),
            )
        )

        if (options.showCeiling) {
            ops.addAll(ceilingOps(dive, ::sx, ::sy, plotTop, theme, m))
        }

        val points = envelope(
            dive.samples.map { Pt(sx(it.timeSeconds), sy(it.depthMetres)) },
            plotWidth,
        )
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
                strokeArgb = theme.curve, strokeWidth = m.px(4f),
            )
        )

        if (options.showGas) {
            ops.addAll(gasOps(dive, ::sx, ::sy, theme, m))
        }

        // ---- stats ---------------------------------------------------------
        val statsY = plotTop + m.curveHeight + m.gap + m.valueSize * 0.8f
        val slot = (m.width - m.pad * 2) / max(stats.size, 1)
        for ((index, stat) in stats.withIndex()) {
            val left = m.pad + index * slot
            ops.add(
                SlateOp.Text(
                    text = stat.value, x = left, baselineY = statsY, sizePx = m.valueSize,
                    fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(6f),
                    bold = true,
                )
            )
            if (stat.unit.isNotEmpty()) {
                // Advance estimated from character count, as the Python does.
                // The SVG writer could not measure text at all; a real
                // measurement pass belongs here later, and would only tighten
                // this.
                ops.add(
                    SlateOp.Text(
                        text = stat.unit,
                        x = left + stat.value.length * m.valueSize * 0.56f + m.px(8f),
                        baselineY = statsY, sizePx = m.labelSize * 1.35f,
                        fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                        haloWidth = m.px(4f),
                    )
                )
            }
            ops.add(
                SlateOp.Text(
                    text = stat.label.uppercase(),
                    x = left, baselineY = statsY + m.labelSize + m.px(8f), sizePx = m.labelSize,
                    fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = m.px(4f),
                    bold = true, letterSpacingEm = 0.10f,
                )
            )
        }

        return Slate(width = m.width, height = height, ops = ops)
    }
}

private val DATE_LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/** Stepped deco ceiling, hatched — the same reading as the full chart's. */
private fun ceilingOps(
    dive: Dive,
    sx: (Double) -> Float,
    sy: (Double) -> Float,
    plotTop: Float,
    theme: SlateTheme,
    m: LayoutMetrics,
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
        argb = theme.ceiling, spacing = m.px(9f), strokeWidth = m.px(1.8f),
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
                strokeArgb = theme.ceiling, strokeWidth = m.px(2.5f),
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
    m: LayoutMetrics,
): List<SlateOp> {
    if (dive.samples.isEmpty()) return emptyList()
    val ops = mutableListOf<SlateOp>()

    for (switch in dive.gasSwitches) {
        val nearest = dive.samples.minBy { abs(it.timeSeconds - switch.timeSeconds) }
        val x = sx(switch.timeSeconds)
        val y = sy(nearest.depthMetres)

        ops.add(
            SlateOp.Circle(
                centre = Pt(x, y), radius = m.px(8f),
                strokeArgb = theme.halo, strokeWidth = m.px(4f),
            )
        )
        ops.add(SlateOp.Circle(centre = Pt(x, y), radius = m.px(8f), fillArgb = theme.accent))
        ops.add(
            SlateOp.Text(
                text = switch.gas.name,
                x = x + m.px(12f), baselineY = y - m.px(12f), sizePx = m.px(20f),
                fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(4f),
                bold = true,
            )
        )
    }
    return ops
}
