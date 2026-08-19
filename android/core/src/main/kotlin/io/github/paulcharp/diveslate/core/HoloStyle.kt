package io.github.paulcharp.diveslate.core

/**
 * A cut-cornered glass panel with a lit trace: the read-out idiom.
 *
 * Three things make it, and the third is the one that took the argument:
 *
 * * **The panel is a polygon, not a rectangle.** Two opposite corners are cut,
 *   which is the whole silhouette — rounded corners here would read as a card
 *   again.
 * * **The trace glows.** A few wider, fainter passes under the sharp line, not
 *   a blur mask; see [SlateOp.Path.glowRadius] for why.
 * * **The microcopy says what is true.** The design it comes from prints
 *   `DEPTH TELEMETRY // LIVE` in the corner, and this is a static badge about a
 *   dive that finished hours ago — nothing is live, and no telemetry is
 *   arriving. It is a small lie and it is the same kind as a fabricated battery
 *   gauge or an invented contour: instrument dressing that a reader has no way
 *   to tell from a reading. So the corner carries the dive's own numbers
 *   instead, and where the log does not have them it carries nothing.
 *
 * The amber ceiling is the one place colour moves off the hazard red, and it
 * was measured rather than assumed: against this much cyan the amber separates
 * further under both simulations than the red does. The hatch and the dash are
 * unchanged, which is where the meaning lives.
 */
object HoloStyle : SlateStyle {

    override val id: String = "holo"

    override val label: String = "HUD"

    override val description: String = "A cut-cornered panel with a lit trace."

    override val themes: List<SlateTheme> = HOLO_THEMES

    override val defaultScrimAlpha: Float = 0.88f

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }?.uppercase()
        val date = if (options.showDate) dateLabel(dive)?.uppercase() else null
        val microcopy = microcopy(dive)

        var headingHeight = 0f
        if (site != null || microcopy != null) headingHeight += m.siteSize
        if (date != null) headingHeight += m.dateSize + m.px(8f)
        if (headingHeight > 0f) headingHeight += m.gap

        val frame = SlateFrame.of(dive, options, stats, headingHeight)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            val alpha = options.resolvedScrimAlpha
            val panel = cutCorners(frame.width, frame.height, m.px(34f))
            ops.add(
                SlateOp.Path(
                    points = panel, closed = true,
                    fill = SlateFill.Linear(
                        startArgb = withAlpha(theme.scrim, alpha),
                        endArgb = withAlpha(theme.surfaceTint, alpha),
                        angleDegrees = 160f,
                    ),
                )
            )
            // The dot field: texture, not a grid. Nothing is measured off it,
            // which is why it is a fill rather than a set of axis lines.
            ops.add(
                SlateOp.Path(
                    points = panel, closed = true,
                    fill = SlateFill.Dots(
                        argb = theme.grid, radius = m.px(1.6f), pitch = m.px(22f),
                    ),
                )
            )
            // Inset by half the stroke: the panel outline follows the slate's
            // own edge, and a stroke centred there loses its outer half to the
            // image bounds.
            val edge = m.px(2f)
            ops.add(
                SlateOp.Path(
                    points = cutCorners(
                        frame.width - edge, frame.height - edge, m.px(34f),
                    ).map { Pt(it.x + edge / 2f, it.y + edge / 2f) },
                    closed = true,
                    strokeArgb = theme.surfaceEdge, strokeWidth = edge,
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = frame.headingTop
        if (site != null || microcopy != null) {
            y += m.siteSize * 0.78f
            var x = frame.left
            if (site != null) {
                // A drawn wedge rather than a typed glyph: the marker the design
                // uses is missing from several Android system fonts, and a tofu
                // box in the corner of a badge reads as a broken export.
                val wedge = m.siteSize * 0.5f
                ops.add(
                    SlateOp.Path(
                        points = listOf(
                            Pt(x, y - wedge),
                            Pt(x + wedge, y - wedge),
                            Pt(x, y),
                        ),
                        closed = true,
                        fill = SlateFill.Solid(theme.ornament),
                    )
                )
                x += wedge + m.px(14f)
                // Measured against what the corner line actually takes, not a
                // fraction guessed at: on the watch badge a fraction leaves the
                // site running straight through the microcopy.
                val taken = microcopy?.let {
                    advanceOf(it, m.labelSize * 0.95f, SlateFont.MEDIUM, 0.24f) + m.px(16f)
                } ?: 0f
                val room = frame.right - x - taken
                ops.add(
                    SlateOp.Text(
                        text = site, x = x, baselineY = y,
                        sizePx = fittedSize(site, m.siteSize, room, SlateFont.MEDIUM, 0.34f),
                        fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(4f),
                        letterSpacingEm = 0.34f, font = SlateFont.MEDIUM,
                    )
                )
            }
            if (microcopy != null) {
                ops.add(
                    SlateOp.Text(
                        text = microcopy, x = frame.right, baselineY = y,
                        sizePx = m.labelSize * 0.95f,
                        fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                        haloWidth = m.px(3f), letterSpacingEm = 0.24f,
                        font = SlateFont.MEDIUM, anchor = TextAnchor.END,
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
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                    haloWidth = m.px(3f), letterSpacingEm = 0.2f, font = SlateFont.MEDIUM,
                )
            )
        }

        // ---- profile -------------------------------------------------------
        ops.add(
            SlateOp.Line(
                start = Pt(frame.left, frame.plotTop), end = Pt(frame.right, frame.plotTop),
                argb = theme.axis, strokeWidth = m.px(2f),
            )
        )
        val points = profileTrace(dive, frame, options)
        ops.add(
            SlateOp.Path(
                points = closedToSurface(points, frame.plotTop),
                closed = true,
                fill = SlateFill.Vertical(theme.curveFillTop, theme.curveFillBottom),
                smooth = options.smoothProfile,
            )
        )
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2f), dash = Dash(m.px(9f), m.px(5f)),
                    hatchSpacing = m.px(11f), hatchWidth = m.px(1.6f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(3.5f),
                glowRadius = m.px(9f),
                smooth = options.smoothProfile,
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.MEDIUM))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.MEDIUM,
            labelFont = SlateFont.MEDIUM,
            valueArgb = theme.ink,
            unitArgb = theme.inkSecondary,
            labelArgb = theme.inkSecondary,
            haloArgb = theme.halo,
            haloWidth = m.px(4f),
            valueBold = false,
            labelSpacing = 0.2f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }

    /**
     * The corner line, from the log or not at all.
     *
     * A dive number is a fact the logbook recorded. Everything else the idiom
     * invites — a battery level, a signal strength, a serial number — would be
     * invented, and the rule this project runs on is that a derived figure
     * degrades to nothing rather than to a guess.
     */
    private fun microcopy(dive: Dive): String? =
        dive.number?.let { "DIVE %02d".format(it) }

    /** A rectangle with the top-left and bottom-right corners cut away. */
    private fun cutCorners(width: Float, height: Float, cut: Float): List<Pt> = listOf(
        Pt(cut, 0f),
        Pt(width, 0f),
        Pt(width, height - cut),
        Pt(width - cut, height),
        Pt(0f, height),
        Pt(0f, cut),
    )
}
