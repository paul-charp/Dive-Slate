package io.github.paulcharp.diveslate.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * The tonal one: a header row and every figure in its own filled container.
 *
 * The only style that rearranges the page rather than just redrawing it. The
 * figures sit in filled containers instead of standing bare on the card, which
 * changes what the layout budget buys — a chip is legible at a smaller share of
 * the width than a naked numeral is, because its container carries the grouping
 * that white space would otherwise have to.
 *
 * **The colours are seeded, not sampled.** Material's other half is dynamic
 * colour, where the scheme is regenerated from the user's wallpaper, and that
 * must not arrive here: a hue taken off someone's home screen has cleared none
 * of the gates in `tools/palette.py`, and letting one in would void the
 * argument behind every other palette in the app. The scheme is computed at
 * design time from a fixed seed, in OKLCH rather than HCT, and then measured
 * like everything else — including the one role Material would have placed by
 * rule, the tertiary, which at 60° off an ocean seed collides with the primary
 * under deuteranopia and is searched for instead.
 *
 * The chip roles follow Material's semantics, and one of them is load-bearing
 * rather than decorative: deco gets the error container. It is the figure that
 * describes a constraint the dive was under, and it is the one an error role
 * exists for.
 */
object MaterialStyle : SlateStyle {

    override val id: String = "material"

    override val label: String = "Material"

    override val description: String = "A tonal card: header row, figures in chips."

    override val themes: List<SlateTheme> = MATERIAL_THEMES

    override val defaultScrimAlpha: Float = 1f

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }
        val date = if (options.showDate) dateLabel(dive) else null
        val iconSize = m.siteSize * 1.5f
        val headerHeight = if (site != null || date != null) iconSize else 0f

        // No divider. M3 Expressive's wavy rule was drawn here, and directly
        // above the plot it did not read as a divider at all — it read as a
        // second, wobbling surface line a few pixels above the real one, on the
        // one style whose water is a flat block with a crisp top edge. A rule
        // that competes with an axis is worse than no rule.
        var headingHeight = headerHeight
        if (headingHeight > 0f) headingHeight += m.gap

        // A chip is a container, and on a stacked badge two containers at the
        // layout's nominal size are two panels with a sparkline wedged under
        // them — the layout sized those figures for bare numerals with nothing
        // around them. The padding comes out of the figure rather than being
        // added to the badge; see SlateFrame.of.
        val figureScale = if (m.figuresStacked) 0.58f else 1f
        val frame = SlateFrame.of(
            dive, options, stats, headingHeight, figureScale = figureScale,
        )
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = m.px(54f),
                    fill = SlateFill.Solid(
                        withAlpha(theme.scrim, options.resolvedScrimAlpha)
                    ),
                )
            )
        }

        // ---- header --------------------------------------------------------
        var y = frame.headingTop
        if (headerHeight > 0f) {
            val centre = Pt(frame.left + iconSize / 2f, y + iconSize / 2f)
            ops.add(
                SlateOp.Circle(
                    centre = centre, radius = iconSize / 2f,
                    fillArgb = theme.containerPrimary,
                )
            )
            ops.add(
                SlateOp.Path(
                    points = wave(
                        centre.x - iconSize * 0.28f, centre.y,
                        iconSize * 0.56f, iconSize * 0.09f,
                    ),
                    closed = false,
                    strokeArgb = theme.onContainerPrimary, strokeWidth = m.px(3.5f),
                )
            )

            // The chip carries the date, so its width has to be known before
            // the title is fitted — otherwise a long site name is typeset into
            // room the chip is about to take.
            var chipRoom = 0f
            if (date != null) {
                val size = m.dateSize
                val width = boxedAdvance(date, size, SlateFont.SANS) + m.px(26f)
                val height = size + m.px(22f)
                chipRoom = width + m.px(16f)
                ops.add(
                    SlateOp.Rect(
                        x = frame.right - width, y = y + (iconSize - height) / 2f,
                        width = width, height = height,
                        cornerRadius = m.px(16f),
                        strokeArgb = theme.surfaceEdge, strokeWidth = m.px(2f),
                    )
                )
                ops.add(
                    SlateOp.Text(
                        text = date, x = frame.right - width / 2f,
                        baselineY = y + iconSize / 2f + size * 0.36f,
                        sizePx = size, fillArgb = theme.inkSecondary,
                        haloArgb = theme.halo, haloWidth = 0f,
                        anchor = TextAnchor.MIDDLE,
                    )
                )
            }
            if (site != null) {
                val left = frame.left + iconSize + m.px(18f)
                val room = frame.right - left - chipRoom
                ops.add(
                    SlateOp.Text(
                        text = site, x = left,
                        baselineY = y + iconSize / 2f + m.siteSize * 0.34f,
                        sizePx = fittedSize(site, m.siteSize, room, SlateFont.MEDIUM),
                        fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 0f,
                        font = SlateFont.MEDIUM,
                    )
                )
            }
            y += iconSize + m.gap * 0.6f
        }

        // ---- profile -------------------------------------------------------
        ops.add(
            SlateOp.Line(
                start = Pt(frame.left, frame.plotTop), end = Pt(frame.right, frame.plotTop),
                argb = theme.axis, strokeWidth = m.px(2f),
            )
        )
        // Much coarser than the other styles draw it, because this one draws a
        // curve: a spline through points a pixel apart is a polyline with extra
        // arithmetic, and the M3 chart idiom is a line that flows. Roughly one
        // bucket per 34 reference pixels is where the reference dive stops
        // reading as a jagged line someone rounded off and starts reading as a
        // drawn curve. The bucket still keeps its shallowest and deepest sample,
        // so the reduction cannot make the dive look shallower than the figure
        // beside it says it was.
        val points = coarsened(profilePoints(dive, frame), m.px(34f))
        ops.add(
            SlateOp.Path(
                points = closedToSurface(points, frame.plotTop),
                closed = true,
                // A flat tonal block, not a fade: Material fills a chart region
                // with the primary container, and a gradient there would be the
                // one place on the card where a colour means nothing.
                fill = SlateFill.Solid(theme.containerPrimary),
                smooth = true,
            )
        )
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2.6f), dash = Dash(m.px(9f), m.px(7f)),
                    hatchSpacing = m.px(12f), hatchWidth = m.px(1.8f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(6f),
                smooth = true,
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.MEDIUM))

        // ---- chips ---------------------------------------------------------
        // Each figure gets a filled container and the ink that was measured
        // against *that* fill, rather than the card's ink at an alpha. The
        // difference is not subtle in the dark scheme, where a chip is a pale
        // tone and the card's ink is pale too.
        val chipPadX = m.px(22f) * figureScale
        // Vertical padding is capped against the layout's own row gap when the
        // chips are stacked, because the padding eats that gap from both sides:
        // at nominal it left five pixels between two 92px containers, which
        // reads as one shape with a seam rather than as two chips.
        val chipPadding =
            if (m.figuresStacked) minOf(m.px(26f) * figureScale, m.gap * 0.55f)
            else m.px(26f)
        val chipPadTop = chipPadding * 0.62f
        // Split unevenly on purpose: the figure block ends just past its
        // label's baseline, so a chip needs more room above the number than
        // under the word to look evenly packed.
        val chipPadBottom = chipPadding * 0.38f
        val chipGap = if (m.figuresStacked) 0f else m.px(12f)
        for ((index, slot) in frame.figureSlots().withIndex()) {
            val (origin, available) = slot
            val stat = stats[index]
            val (fill, on) = container(stat, theme)
            val ink = chipInk(theme, on, figureScale)
            // Full width when stacked, equal columns when in a row — the
            // layout's own arrangement either way. Sizing a stacked chip to its
            // content was tried and abandoned: it fixed nothing the figure scale
            // had not already fixed, and left a narrow column of pills against
            // half a badge of empty card, which reads worse than a row that is
            // simply too roomy.
            val chipWidth = available - chipGap
            ops.add(
                SlateOp.Rect(
                    x = origin.x, y = origin.y - chipPadTop,
                    width = chipWidth,
                    height = frame.figureHeight + chipPadTop + chipPadBottom,
                    cornerRadius = m.px(34f),
                    fill = SlateFill.Solid(fill),
                )
            )
            ops.addAll(
                figureOps(
                    stat = stat,
                    origin = Pt(origin.x + chipPadX, origin.y),
                    available = chipWidth - chipPadX * 2f,
                    m = m,
                    ink = ink,
                )
            )
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }

    /**
     * A figure's typography inside a chip.
     *
     * M3 sets a chip's supporting text in the same on-container colour at
     * reduced opacity rather than in a separate muted grey — a grey would be a
     * colour read against the card, and the label is not on the card.
     */
    private fun chipInk(theme: SlateTheme, on: Long, scale: Float): FigureInk = FigureInk(
        valueFont = SlateFont.MEDIUM,
        labelFont = SlateFont.SANS,
        valueArgb = on,
        unitArgb = withAlpha(on, 0.86f),
        labelArgb = withAlpha(on, 0.82f),
        haloArgb = theme.halo,
        haloWidth = 0f,
        labelSpacing = 0.02f,
        uppercaseLabel = false,
        valueScale = scale,
    )

    /**
     * The container a figure sits in, and the ink that reads on it.
     *
     * Matched on the figure's own label rather than on position, so a slate
     * showing three figures does not colour the third one "deco" because deco
     * happens to be third in the default order. The error container is reserved
     * for the deco figure and is the only role here carrying meaning rather than
     * variety: it is the figure that describes a constraint the dive was under,
     * which is what an error role is for.
     */
    private fun container(stat: SlateStat, theme: SlateTheme): Pair<Long, Long> =
        when (stat.label) {
            "Deco" -> theme.containerHazard to theme.onContainerHazard
            "Max depth" -> theme.containerPrimary to theme.onContainerPrimary
            "Runtime" -> theme.containerNeutral to theme.onContainerNeutral
            else -> theme.containerAccent to theme.onContainerAccent
        }

    /** One period-and-a-bit of a sine, sampled finely enough to read as smooth. */
    private fun wave(x: Float, y: Float, width: Float, amplitude: Float): List<Pt> {
        val steps = 48
        val periods = 4.5
        return (0..steps).map { index ->
            val t = index.toFloat() / steps
            Pt(
                x + width * t,
                y + amplitude * sin(2.0 * PI * periods * t).toFloat(),
            )
        }
    }
}
