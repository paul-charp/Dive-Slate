package io.github.paulcharp.diveslate.core

/**
 * A masthead and a hairline: editorial type sitting straight on the footage.
 *
 * The one style with no card of its own to hide behind, which makes it the one
 * where the legibility rules are load-bearing rather than belt-and-braces. Two
 * consequences, both deliberate:
 *
 * * **The panel is full-bleed and square, not a rounded badge.** The design
 *   this comes from had no panel at all, and relied on soft shadows under every
 *   element. Over video that is a bet on the frame behind the type, and the
 *   mockup's own notes admit it: over bright water the shadows are the only
 *   separation left. So the panel stays — a wash rather than a card, edge to
 *   edge, with nothing rounded to read as a sticker — and the user can still
 *   turn it off, which is the shape the original design wants and the risk it
 *   carries, taken knowingly.
 * * **The palette is one ink.** Nothing here is told apart by hue: the ceiling
 *   is the same white as the profile, separated by a dash and half the stroke
 *   width. That is why these palettes are validated under the monochrome
 *   profile and why the dash is not decoration — drop it and the two marks
 *   become one.
 */
object MagazineStyle : SlateStyle {

    override val id: String = "magazine"

    override val label: String = "Magazine"

    override val description: String = "Editorial type, straight on the footage."

    override val themes: List<SlateTheme> = MAGAZINE_THEMES

    override val defaultScrimAlpha: Float = 0.72f

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }?.uppercase()
        val date = if (options.showDate) dateLabel(dive)?.uppercase() else null
        val ruleWidth = m.px(4f)
        val rulePad = m.px(14f)

        // The masthead is the site between two rules; the rules are part of the
        // heading's height, so nothing below moves when the site is hidden.
        var headingHeight = 0f
        if (site != null) headingHeight += m.siteSize + (ruleWidth + rulePad) * 2f
        if (date != null) headingHeight += m.dateSize + m.px(10f)
        if (headingHeight > 0f) headingHeight += m.gap

        val frame = SlateFrame.of(dive, options, stats, headingHeight)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = 0f,
                    fill = SlateFill.Solid(
                        withAlpha(theme.scrim, options.resolvedScrimAlpha)
                    ),
                )
            )
        }

        // ---- masthead ------------------------------------------------------
        var y = frame.headingTop
        if (site != null) {
            ops.add(
                SlateOp.Rect(
                    x = frame.left, y = y, width = frame.inner, height = ruleWidth,
                    cornerRadius = 0f, fill = SlateFill.Solid(theme.surfaceEdge),
                )
            )
            y += ruleWidth + rulePad + m.siteSize * 0.78f
            ops.add(
                SlateOp.Text(
                    text = site, x = frame.left, baselineY = y,
                    // The masthead is tracked out to .42em, which nearly
                    // doubles its width — fittedSize is told, rather than the
                    // available width being fudged down to compensate.
                    sizePx = fittedSize(site, m.siteSize, frame.inner, SlateFont.SANS, 0.42f),
                    fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = m.px(5f),
                    bold = true, letterSpacingEm = 0.42f,
                )
            )
            y += m.siteSize * 0.22f + rulePad
            ops.add(
                SlateOp.Rect(
                    x = frame.left, y = y, width = frame.inner, height = ruleWidth,
                    cornerRadius = 0f, fill = SlateFill.Solid(theme.surfaceEdge),
                )
            )
            y += ruleWidth
        }
        if (date != null) {
            y += m.px(10f) + m.dateSize * 0.85f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                    haloWidth = m.px(4f), letterSpacingEm = 0.24f,
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
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2.4f), dash = Dash(m.px(12f), m.px(9f)),
                    hatchSpacing = m.px(12f), hatchWidth = m.px(1.6f),
                )
            )
        }
        // No area fill: the whole idiom is line and type, and a wash would be
        // the one grey tone in a design that has none.
        val points = profilePoints(dive, frame)
        ops.add(
            SlateOp.Path(
                points = points, closed = false,
                strokeArgb = theme.curve, strokeWidth = m.px(6f),
            )
        )
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.CONDENSED))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.CONDENSED,
            labelFont = SlateFont.SANS,
            valueArgb = theme.ink,
            unitArgb = theme.inkSecondary,
            labelArgb = theme.inkSecondary,
            haloArgb = theme.halo,
            haloWidth = m.px(6f),
            labelSpacing = 0.17f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }
}
