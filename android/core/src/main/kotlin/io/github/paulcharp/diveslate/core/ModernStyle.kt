package io.github.paulcharp.diveslate.core

import java.time.format.DateTimeFormatter
import java.util.Locale
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
 *   deficiency. The label used to be set beside the marker here; it is now a
 *   tab on the surface line, shared with every other style — see [gasOps].
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

    /**
     * Opens at 85%, above every palette's nominal.
     *
     * The nominal is the contrast maths answering its own question — the least
     * panel that still clears the ratio over the worst possible backdrop. That
     * is a floor with the margin shaved off, and this style's marks are set
     * over footage rather than over the worst case: video moves, and a panel
     * sized to just barely pass on a still frame reads as a smear on a busy
     * one. Starting high and letting the slider come down puts the legible
     * version in front of the user first.
     */
    override val defaultScrimAlpha: Float = 0.85f

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

        // One figure's worth of vertical room, and then however many rows of it
        // this layout asks for.
        val figureBlock = m.valueSize + m.labelSize + m.px(10f)
        val rows = if (m.figuresStacked) max(stats.size, 1) else 1
        val statsBlock = figureBlock * rows + m.gap * (rows - 1)
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
        val inner = m.width - m.pad * 2
        var y = m.pad
        if (showSite) {
            y += m.siteSize * 0.78f
            val site = dive.site!!.uppercase()
            ops.add(
                SlateOp.Text(
                    text = site,
                    x = m.pad, baselineY = y,
                    // Fitted for the same reason the figures are: a badge 400px
                    // wide holds about fourteen characters at full size, and real
                    // site names run to `SS Thistlegorm, Sha'ab Ali`. The heading
                    // keeps its nominal height either way, so a long name shrinks
                    // without moving everything under it.
                    sizePx = fittedTextSize(site, m.siteSize, inner),
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
        // Where the two body blocks land. The layout decides which is read
        // first; the marks are the same either way, so this is placement rather
        // than a second art direction, and the overall height does not move.
        val bodyTop = m.pad + headingBlock
        val plotTop = if (m.figuresLead) bodyTop + statsBlock + m.gap else bodyTop
        val statsTop = if (m.figuresLead) bodyTop else bodyTop + m.curveHeight + m.gap

        // ---- profile -------------------------------------------------------
        val plotLeft = m.pad
        val plotRight = m.width - m.pad
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

        // Coarsened when smoothing, for the same reason every other style
        // coarsens: a spline through points a pixel apart is a polyline with
        // extra arithmetic. This style builds its own series rather than going
        // through profileTrace, because it maps the samples with closures of
        // its own — so the reduction is applied here by hand, at the shared
        // SMOOTH_STEP_PX rather than at a number of its own. A second copy of
        // that constant is how the default style would end up smoothing
        // differently from the other seven.
        val traced = envelope(
            dive.samples.map { Pt(sx(it.timeSeconds), sy(it.depthMetres)) },
            plotWidth,
        )
        val points =
            if (options.smoothProfile) coarsened(traced, m.px(SMOOTH_STEP_PX)) else traced
        val area = buildList {
            add(Pt(points.first().x, plotTop))
            addAll(points)
            add(Pt(points.last().x, plotTop))
        }
        ops.add(
            SlateOp.Path(
                points = area, closed = true,
                fill = SlateFill.Vertical(theme.curveFillTop, theme.curveFillBottom),
                smooth = options.smoothProfile,
            )
        )
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(4f),
                smooth = options.smoothProfile,
            )
        )

        if (options.showGas) {
            // The one piece of this style that came from the newer ones rather
            // than the other way round. A frame is built here purely to hand
            // the shared marker the same geometry the closures above use — it
            // computes the identical numbers, so nothing else about this style
            // moves, and the gas switch stops being the single place where
            // Modern draws a label somewhere the others do not.
            ops.addAll(
                gasOps(
                    dive = dive,
                    frame = SlateFrame.of(dive, options, stats, headingBlock),
                    theme = theme,
                    font = SlateFont.SANS,
                )
            )
        }

        // ---- stats ---------------------------------------------------------
        // Two arrangements, one set of marks. Stacking hands each figure the
        // whole width instead of a share of it, which is what lets a badge a
        // third the size carry larger numerals than the full-width layouts do.
        fun figureOps(stat: SlateStat, left: Float, top: Float, available: Float): List<SlateOp> {
            val figure = mutableListOf<SlateOp>()
            val valueSize = fittedValueSize(stat, m, available)
            // Baselines come off the layout's nominal size rather than the fitted
            // one, so a shrunk figure still sits on the line its neighbours do.
            val baseline = top + m.valueSize * 0.8f
            figure.add(
                SlateOp.Text(
                    text = stat.value, x = left, baselineY = baseline, sizePx = valueSize,
                    fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(6f),
                    bold = true,
                )
            )
            if (stat.unit.isNotEmpty()) {
                figure.add(
                    SlateOp.Text(
                        text = stat.unit,
                        x = left + valueAdvance(stat.value, valueSize) + m.px(8f),
                        baselineY = baseline, sizePx = unitSize(m),
                        fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                        haloWidth = m.px(4f),
                    )
                )
            }
            figure.add(
                SlateOp.Text(
                    text = stat.label.uppercase(),
                    x = left, baselineY = baseline + m.labelSize + m.px(8f), sizePx = m.labelSize,
                    fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = m.px(4f),
                    bold = true, letterSpacingEm = 0.10f,
                )
            )
            return figure
        }

        if (m.figuresStacked) {
            for ((index, stat) in stats.withIndex()) {
                ops.addAll(
                    figureOps(stat, m.pad, statsTop + index * (figureBlock + m.gap), inner)
                )
            }
        } else {
            val slot = inner / max(stats.size, 1)
            for ((index, stat) in stats.withIndex()) {
                ops.addAll(figureOps(stat, m.pad + index * slot, statsTop, slot))
            }
        }

        return Slate(width = m.width, height = height, ops = ops)
    }
}

private val DATE_LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/**
 * Text advance estimated from character count, as the retired Python did.
 *
 * The SVG writer it came from could not measure text at all. Core has no font
 * either — it emits a display list and the app paints it — so the estimate is
 * still the only measure available here. It is deliberately generous: the cost
 * of overestimating is a little air in a column, and the cost of
 * underestimating is two figures touching.
 */
private const val ADVANCE = 0.56f

private fun valueAdvance(text: String, sizePx: Float): Float = text.length * sizePx * ADVANCE

/** The largest size at or below [nominal] that keeps [text] within [available]. */
private fun fittedTextSize(text: String, nominal: Float, available: Float): Float {
    if (text.isEmpty() || available <= 0f) return nominal
    return minOf(nominal, available / (text.length * ADVANCE))
}

private fun unitSize(m: LayoutMetrics): Float = m.labelSize * 1.35f

/**
 * The figure size that keeps a value and its unit inside one column.
 *
 * A narrow layout divides a narrower slate into the same number of columns, so
 * a value that is a word rather than a number — `Air, O2`, or SAC with its
 * `L/min` — no longer fits the slot at full size. Shrinking that one figure is
 * the right failure: the alternatives are dropping it, which would make the
 * layout control silently change *what* the slate says, or letting it run into
 * the next column, where two figures overlap and neither can be read.
 *
 * Only a figure that would overrun is touched, so a slate whose figures fit —
 * which, given the layouts' budgets, is nearly all of them — typesets exactly
 * as it did before this existed.
 *
 * There is no floor under the shrinking, and that was a correction rather than
 * an oversight: a floor sounds prudent, but it hands back a figure that still
 * does not fit, and an overlap costs *both* columns where a small figure costs
 * one. It only ever bites the gas list, the single figure whose value is a
 * sentence rather than a number — and a small list of mixes is still a list of
 * mixes, while two figures printed through each other are neither.
 */
private fun fittedValueSize(stat: SlateStat, m: LayoutMetrics, slot: Float): Float {
    val unit = if (stat.unit.isEmpty()) 0f else m.px(8f) + valueAdvance(stat.unit, unitSize(m))
    // A gutter, so columns are separated rather than merely not overlapping.
    return fittedTextSize(stat.value, m.valueSize, slot - m.px(14f) - unit)
}

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
