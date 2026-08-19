package io.github.paulcharp.diveslate.core

/**
 * A survey sheet: grained paper, hairline grid, a drawn profile.
 *
 * The map idiom is the most flattering to a dive profile and the most dangerous
 * to it, because everything on a map looks like a measurement. Two rules keep
 * that honest, and they are the reason this style is longer than it looks:
 *
 * * **The gridlines come from the dive.** The design specified hairlines at 15,
 *   30 and 45 metres, which is a ruler for a dive that happened to reach 45. On
 *   a 12-metre reef it draws nothing; on a 60-metre dive it stops two thirds of
 *   the way down. Both silently. Here the interval is chosen from the depth
 *   actually reached — see [depthGridlines] — and **every line is labelled**,
 *   because an unlabelled hairline on a map is a measurement the reader has to
 *   guess at.
 * * **The contours are labelled as texture by being unlabelled.** They are
 *   echoes of the profile offset downward, not iso-depth lines, and they carry
 *   no numbers, sit at a fraction of the profile's weight, and are drawn in the
 *   paper's own brown rather than in the survey blue the real line uses. On a
 *   map that is the difference between a hachure and a contour. If they ever
 *   start reading as data — if someone asks what interval they are at — the
 *   answer is to delete them, not to invent one.
 */
object TopoStyle : SlateStyle {

    override val id: String = "topo"

    override val label: String = "Survey"

    override val description: String = "Grained paper, a labelled grid, a drawn line."

    override val themes: List<SlateTheme> = TOPO_THEMES

    override val defaultScrimAlpha: Float = 1f

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }?.uppercase()
        val date = if (options.showDate) dateLabel(dive)?.uppercase() else null
        val gridlines = depthGridlines(dive.computedMaxDepthMetres)
        val legend = gridlines.firstOrNull()?.let { "GRID ${trimmed(it)} M" }

        var headingHeight = 0f
        if (site != null || legend != null) headingHeight += m.siteSize
        if (date != null) headingHeight += m.dateSize + m.px(8f)
        if (headingHeight > 0f) headingHeight += m.gap

        val frame = SlateFrame.of(dive, options, stats, headingHeight)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            val alpha = options.resolvedScrimAlpha
            // Inset by half the stroke. A border drawn on the slate's own
            // edge is centred there, so half of it falls outside the image and
            // is cropped — the sheet then has a hairline on three sides and a
            // half-hairline on the fourth, which reads as a rendering fault.
            val edge = m.px(2f)
            ops.add(
                SlateOp.Rect(
                    x = edge / 2f, y = edge / 2f,
                    width = frame.width - edge, height = frame.height - edge,
                    cornerRadius = m.px(8f),
                    fill = SlateFill.Solid(withAlpha(theme.scrim, alpha)),
                    strokeArgb = theme.surfaceEdge,
                    strokeWidth = edge,
                )
            )
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = m.px(8f),
                    // Seeded from nothing that varies: the same slate has to
                    // rasterise identically on every export.
                    fill = SlateFill.Grain(
                        argb = withAlpha(theme.ornament, 0.1f * alpha),
                        cell = m.px(5f),
                        density = 0.26f,
                        seed = 9,
                    ),
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = frame.headingTop
        if (site != null || legend != null) {
            y += m.siteSize * 0.78f
            if (site != null) {
                val taken = legend?.let {
                    advanceOf(it, m.labelSize * 0.95f, SlateFont.SANS, 0.12f) + m.px(40f)
                } ?: 0f
                val room = frame.inner - taken
                ops.add(
                    SlateOp.Text(
                        text = site, x = frame.left, baselineY = y,
                        sizePx = fittedSize(site, m.siteSize, room, SlateFont.SANS, 0.3f),
                        fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 0f,
                        bold = true, letterSpacingEm = 0.3f,
                    )
                )
            }
            if (legend != null) {
                val size = m.labelSize * 0.95f
                val width = boxedAdvance(legend, size, SlateFont.SANS) + m.px(24f)
                val boxHeight = size + m.px(16f)
                ops.add(
                    SlateOp.Rect(
                        x = frame.right - width, y = y - size - m.px(8f),
                        width = width, height = boxHeight,
                        cornerRadius = m.px(3f),
                        fill = SlateFill.Solid(withAlpha(theme.surfaceTint, 0.6f)),
                        strokeArgb = theme.surfaceEdge, strokeWidth = m.px(1.6f),
                    )
                )
                ops.add(
                    SlateOp.Text(
                        text = legend,
                        x = frame.right - width / 2f,
                        baselineY = y - size - m.px(8f) + boxHeight / 2f + size * 0.36f,
                        sizePx = size, fillArgb = theme.inkMuted,
                        haloArgb = theme.halo, haloWidth = 0f,
                        letterSpacingEm = 0.12f, anchor = TextAnchor.MIDDLE,
                    )
                )
            }
            y += m.siteSize * 0.32f
        }
        if (date != null) {
            y += m.dateSize * 0.9f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = 0f,
                    letterSpacingEm = 0.14f,
                )
            )
        }

        // ---- grid ----------------------------------------------------------
        for (depth in gridlines) {
            val lineY = frame.sy(depth)
            ops.add(
                SlateOp.Line(
                    start = Pt(frame.left, lineY), end = Pt(frame.right, lineY),
                    argb = theme.grid, strokeWidth = m.px(1.6f),
                )
            )
            ops.add(
                SlateOp.Text(
                    text = "${trimmed(depth)} m",
                    x = frame.left + m.px(6f), baselineY = lineY - m.px(6f),
                    sizePx = m.labelSize * 0.8f,
                    fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = 0f,
                )
            )
        }
        ops.add(
            SlateOp.Line(
                start = Pt(frame.left, frame.plotTop), end = Pt(frame.right, frame.plotTop),
                argb = theme.axis, strokeWidth = m.px(2f),
            )
        )

        // ---- profile -------------------------------------------------------
        val points = profileTrace(dive, frame, options)
        ops.add(
            SlateOp.Path(
                points = closedToSurface(points, frame.plotTop),
                closed = true,
                fill = SlateFill.Solid(theme.curveFillTop),
                smooth = options.smoothProfile,
            )
        )
        // Hachures: three echoes below the line, fading, in the paper's brown.
        // Deliberately not the survey blue the profile is drawn in — a reader
        // who takes these for readings has been misled by the drawing, so they
        // are kept visibly subordinate.
        for (step in 1..3) {
            val offset = m.px(9f) * step
            val alpha = 0.34f - 0.09f * (step - 1)
            ops.add(
                SlateOp.Path(
                    points = points.map { Pt(it.x, minOf(it.y + offset, frame.plotBottom)) },
                    closed = false,
                    strokeArgb = withAlpha(theme.ornament, alpha),
                    strokeWidth = m.px(1.8f),
                    smooth = options.smoothProfile,
                )
            )
        }
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2f), dash = Dash(m.px(11f), m.px(7f)),
                    hatchSpacing = m.px(12f), hatchWidth = m.px(1.6f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(4f),
                smooth = options.smoothProfile,
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.SANS))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.SANS,
            labelFont = SlateFont.SANS,
            valueArgb = theme.curve,
            unitArgb = theme.inkSecondary,
            labelArgb = theme.inkMuted,
            haloArgb = theme.halo,
            haloWidth = 0f,
            labelSpacing = 0.14f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }

    /** `10` rather than `10.0`; `2.5` kept. */
    private fun trimmed(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
