package io.github.paulcharp.diveslate.core

/**
 * A rounded, ringed card that reads as a sticker somebody stuck on the shot.
 *
 * The tell is the ring — a soft band just inside the card edge, the trace a
 * die-cut sticker leaves. Everything else follows from it: generous corners, a
 * heavy rounded face, and a profile drawn as a warm-to-cool ramp so the line
 * has somewhere to travel.
 *
 * The ramp is the one place this style spends colour on something that is not a
 * distinction, so it is worth being clear about what it does and does not mean.
 * It runs along *time*, not depth, and it is one mark rather than two — nobody
 * should read the far end as a different series. Both ends are still measured
 * against the ceiling and the accent, because a gradient that ends somewhere
 * unchecked is a mark nobody validated.
 */
object StickerStyle : SlateStyle {

    override val id: String = "sticker"

    override val label: String = "Sticker"

    override val description: String = "A rounded, ringed card with a ramped line."

    override val themes: List<SlateTheme> = STICKER_THEMES

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
        val radius = m.px(50f)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            val alpha = options.resolvedScrimAlpha
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = radius,
                    fill = SlateFill.Solid(withAlpha(theme.scrim, alpha)),
                )
            )
            // The die-cut ring, drawn inside the card rather than around it: the
            // slate's bounds are the exported image, so anything outside them is
            // not soft, it is cropped.
            val inset = m.px(7f)
            ops.add(
                SlateOp.Rect(
                    x = inset, y = inset,
                    width = frame.width - inset * 2f, height = frame.height - inset * 2f,
                    cornerRadius = radius - inset,
                    strokeArgb = withAlpha(theme.surfaceEdge, alpha * 0.55f),
                    strokeWidth = m.px(9f),
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = frame.headingTop
        if (site != null) {
            y += m.siteSize * 0.78f
            ops.add(
                SlateOp.Text(
                    text = site, x = frame.left, baselineY = y,
                    sizePx = fittedSize(site, m.siteSize, frame.inner, SlateFont.BLACK, 0.1f),
                    fillArgb = theme.curve,
                    // Gradient ink, on the heading alone. A figure never wears
                    // one: a number whose colour changes across its digits reads
                    // as though the colour meant something.
                    gradientEndArgb = theme.accent,
                    haloArgb = theme.halo, haloWidth = 0f,
                    bold = true, letterSpacingEm = 0.1f, font = SlateFont.BLACK,
                )
            )
            y += m.siteSize * 0.32f
        }
        if (date != null) {
            y += m.dateSize * 0.9f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkMuted, haloArgb = theme.halo, haloWidth = 0f,
                    bold = true,
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
        val points = profilePoints(dive, frame)
        ops.add(
            SlateOp.Path(
                points = closedToSurface(points, frame.plotTop),
                closed = true,
                fill = SlateFill.Vertical(theme.curveFillTop, theme.curveFillBottom),
            )
        )
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2.4f), dash = Dash(m.px(9f), m.px(7f)),
                    hatchSpacing = m.px(12f), hatchWidth = m.px(1.8f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(6f),
                strokeEndArgb = theme.curveEnd,
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.BLACK))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.BLACK,
            labelFont = SlateFont.MEDIUM,
            valueArgb = theme.ink,
            unitArgb = theme.inkSecondary,
            labelArgb = theme.inkMuted,
            haloArgb = theme.halo,
            haloWidth = 0f,
            labelSpacing = 0.08f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }
}
