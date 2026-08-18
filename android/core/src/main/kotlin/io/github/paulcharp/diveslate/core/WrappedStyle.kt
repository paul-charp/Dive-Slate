package io.github.paulcharp.diveslate.core

/**
 * Flat, loud, feed-post energy: one saturated card, two colours, no shading.
 *
 * The opposite of [ModernStyle] on purpose. Modern reads as instrumentation and
 * recedes behind the numbers; this one is the thing in the frame, and the dive
 * profile is a shape rather than a chart. Both draw the same figures from the
 * same log — what changes is who is talking.
 *
 * Two decisions here are worth keeping if the style is ever revised:
 *
 * * **The card is opaque, and that is what buys the colour.** A lime line on a
 *   violet ground is legible because the ground is known. The same lime over
 *   footage would be a gamble taken on the user's behalf, which is why the
 *   expressive palette profile only applies to styles that paint their own
 *   background — see `tools/palette.py`.
 * * **The ceiling is white here, not red.** Measured, not assumed: on this card
 *   the hazard red loses to the surface, and the ceiling still arrives as a
 *   dashed step over a hatch, which is where the meaning actually lives. The
 *   yellow card's ceiling went back to a red for the same reason in reverse —
 *   white measured 1.43:1 there.
 */
object WrappedStyle : SlateStyle {

    override val id: String = "wrapped"

    override val label: String = "Wrapped"

    override val description: String = "Flat and loud on a solid card. Made to be posted."

    override val themes: List<SlateTheme> = WRAPPED_THEMES

    /** Opaque by design; the slider can still take the card down over footage. */
    override val defaultScrimAlpha: Float = 1f

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }?.uppercase()
        val date = if (options.showDate) dateLabel(dive) else null
        var headingHeight = 0f
        if (site != null) headingHeight += m.siteSize
        if (date != null) headingHeight += m.dateSize + m.px(8f)
        if (headingHeight > 0f) headingHeight += m.gap

        val frame = SlateFrame.of(dive, options, stats, headingHeight)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = m.px(38f),
                    fill = SlateFill.Solid(
                        withAlpha(theme.scrim, options.resolvedScrimAlpha)
                    ),
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = frame.headingTop
        if (site != null) {
            y += m.siteSize * 0.78f
            ops.add(
                SlateOp.Text(
                    text = site,
                    x = frame.left, baselineY = y,
                    sizePx = fittedSize(site, m.siteSize, frame.inner, SlateFont.BLACK, 0.14f),
                    fillArgb = theme.ink,
                    haloArgb = theme.halo, haloWidth = 0f,
                    bold = true, letterSpacingEm = 0.14f, font = SlateFont.BLACK,
                )
            )
            y += m.siteSize * 0.32f
        }
        if (date != null) {
            y += m.dateSize * 0.9f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo, haloWidth = 0f,
                    bold = true, font = SlateFont.SANS,
                )
            )
        }

        // ---- profile -------------------------------------------------------
        ops.add(
            SlateOp.Line(
                start = Pt(frame.left, frame.plotTop), end = Pt(frame.right, frame.plotTop),
                argb = theme.axis, strokeWidth = m.px(3f),
            )
        )

        val points = profilePoints(dive, frame)
        ops.add(
            SlateOp.Path(
                points = closedToSurface(points, frame.plotTop),
                closed = true,
                // Flat, not a wash. The fill is a shape here rather than a fade
                // under the line, which is the whole difference between this
                // style and a chart.
                fill = SlateFill.Solid(theme.curveFillTop),
            )
        )
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(4f), dash = Dash(m.px(16f), m.px(9f)),
                    hatchSpacing = m.px(11f), hatchWidth = m.px(2.4f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(7f),
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.BLACK))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.BLACK,
            labelFont = SlateFont.SANS,
            valueArgb = theme.ink,
            unitArgb = theme.ink,
            labelArgb = theme.inkSecondary,
            haloArgb = theme.halo,
            haloWidth = 0f,
            labelSpacing = 0.12f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        // ---- sparkles ------------------------------------------------------
        // Drawn rather than typed. The mockup set them as a glyph, which is a
        // font dependency for a decoration: the star is missing from several
        // Android system fonts and renders as a tofu box, and a box in the
        // corner of a badge looks like a bug in the export.
        ops.addAll(sparkles(frame, theme, m))

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }

    private fun sparkles(
        frame: SlateFrame,
        theme: SlateTheme,
        m: LayoutMetrics,
    ): List<SlateOp> {
        // Kept inside the padding box, not hung off the corners. The slate's
        // bounds are the exported image, so a sparkle placed half a radius past
        // the edge is not cropped softly — it is a clipped semicircle, and only
        // on whichever layout happened to be narrow.
        val big = minOf(m.px(26f), frame.inner * 0.06f)
        return listOf(
            sparkle(Pt(frame.right - big, frame.headingTop + big), big, theme),
            sparkle(Pt(frame.right - big * 3f, frame.headingTop + big * 2.6f), big * 0.5f, theme),
            sparkle(Pt(big * 0.9f, frame.height - big * 0.9f), big * 0.6f, theme),
        )
    }

    /** A four-pointed star: long points, waisted sides. */
    private fun sparkle(centre: Pt, radius: Float, theme: SlateTheme): SlateOp {
        val waist = radius * 0.42f
        val diagonal = waist * 0.7071f
        return SlateOp.Path(
            points = listOf(
                Pt(centre.x, centre.y - radius),
                Pt(centre.x + diagonal, centre.y - diagonal),
                Pt(centre.x + radius, centre.y),
                Pt(centre.x + diagonal, centre.y + diagonal),
                Pt(centre.x, centre.y + radius),
                Pt(centre.x - diagonal, centre.y + diagonal),
                Pt(centre.x - radius, centre.y),
                Pt(centre.x - diagonal, centre.y - diagonal),
            ),
            closed = true,
            fill = SlateFill.Solid(theme.ornament),
        )
    }
}
