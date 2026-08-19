package io.github.paulcharp.diveslate.core

/**
 * A pane of glass: translucent, edge-lit, with the footage showing through.
 *
 * The design this comes from leans on `backdrop-filter: blur()`, and that is
 * the one effect a transparent PNG cannot carry — a bitmap cannot blur what is
 * behind it, because it never sees it. Faking the blur was not an option
 * either; what reads as frost without one is the *sheen*: a two-stop wash
 * across the panel, a bright hairline edge, and marks light enough to look lit
 * from behind. That is what is drawn here.
 *
 * The palettes are two panes rather than one pane in two colours. Smoked glass
 * carries white marks and belongs over footage; misted glass carries dark ones
 * and belongs on a pale background. Inverting one to make the other would give
 * a panel that is bright where it should be dim, and the contrast floor moves
 * with it — which is why both were measured separately.
 */
object FrostedStyle : SlateStyle {

    override val id: String = "frosted"

    override val label: String = "Frosted"

    override val description: String = "Translucent glass with a lit edge."

    override val themes: List<SlateTheme> = FROSTED_THEMES

    override val defaultScrimAlpha: Float = 0.8f

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
        val radius = m.px(42f)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            val alpha = options.resolvedScrimAlpha
            // Inset by half the stroke, so the lit edge is a whole hairline
            // rather than one cropped down the middle by the image bounds.
            val edge = m.px(2.5f)
            ops.add(
                SlateOp.Rect(
                    x = edge / 2f, y = edge / 2f,
                    width = frame.width - edge, height = frame.height - edge,
                    cornerRadius = radius,
                    // 160°, as the design specifies: a diagonal wash reads as a
                    // pane catching light from off to one side, where a vertical
                    // one reads as a gradient somebody applied.
                    fill = SlateFill.Linear(
                        startArgb = withAlpha(theme.surfaceTint, alpha),
                        endArgb = withAlpha(theme.scrim, alpha),
                        angleDegrees = 160f,
                    ),
                    strokeArgb = theme.surfaceEdge,
                    strokeWidth = edge,
                )
            )
            // Glass thickness, as a second rim just inside the first.
            //
            // The first attempt at this was a bright line ruled across the top
            // of the panel, which is how the effect gets described and not how
            // it reads: a straight highlight stopping short of two rounded
            // corners looks like a stray rule, because nothing about it follows
            // the shape. An inset rim does follow it, and a pane seen edge-on is
            // two concentric edges anyway — the outer catching the light, an
            // inner one a shade dimmer.
            val rim = m.px(7f)
            ops.add(
                SlateOp.Rect(
                    x = rim, y = rim,
                    width = frame.width - rim * 2f, height = frame.height - rim * 2f,
                    cornerRadius = radius - rim,
                    strokeArgb = withAlpha(theme.surfaceEdge, 0.3f),
                    strokeWidth = m.px(1.5f),
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
                    sizePx = fittedSize(site, m.siteSize, frame.inner, SlateFont.MEDIUM, 0.32f),
                    fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(5f),
                    letterSpacingEm = 0.32f, font = SlateFont.MEDIUM,
                )
            )
            y += m.siteSize * 0.32f
        }
        if (date != null) {
            y += m.dateSize * 0.9f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                    haloWidth = m.px(4f), font = SlateFont.MEDIUM,
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
                    strokeWidth = m.px(2f), dash = Dash(m.px(10f), m.px(8f)),
                    hatchSpacing = m.px(13f), hatchWidth = m.px(1.6f),
                )
            )
        }
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(4.5f),
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
            labelArgb = theme.inkMuted,
            haloArgb = theme.halo,
            haloWidth = m.px(5f),
            valueBold = false,
            labelSpacing = 0.14f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }
}
