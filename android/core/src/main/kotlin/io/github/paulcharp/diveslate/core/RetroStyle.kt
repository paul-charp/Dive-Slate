package io.github.paulcharp.diveslate.core

/**
 * A segment display behind a bezel: the dive computer on the wrist, not the app.
 *
 * The profile is redrawn as square steps, because that is what the idiom *is* —
 * a screen with a coarse grid and no anti-aliasing. That makes it the one style
 * where art direction touches the data, so the step size is a decision rather
 * than a taste:
 *
 * **One minute and one metre, not two minutes and three.** The design this
 * comes from snapped to 2 min / 3 m, and on the reference dive that flattened
 * the sawtooth bottom into a staircase and moved the deepest drawn point by
 * nearly two metres — while the figure beside it still read 45 m. Nothing was
 * misstated, but the picture had stopped agreeing with the number, which is the
 * same failure as a figure that degrades to a guess. At one minute and one
 * metre the steps still read as a display and the silhouette is still this
 * dive's.
 *
 * The palettes are three screens rather than three tints: a green LCD read by
 * reflected light, an amber backlight and a blue electroluminescent panel. All
 * three are one ink, so the ceiling is separated from the profile by dash and
 * stroke width alone — see the monochrome profile in `tools/palette.py`.
 */
object RetroStyle : SlateStyle {

    override val id: String = "retro"

    /**
     * The one style whose profile is not smoothed.
     *
     * The segment screen resamples to one minute and one metre and draws its
     * own steps, so it never goes through `profileTrace` at all — that
     * quantisation *is* the style, and a curve through a staircase is a
     * staircase with rounded corners, which reads as neither.
     *
     * Said out loud rather than left implicit, which it was until a test asked.
     * Ignoring the flag is not the same as declaring it: the UI reads this to
     * decide whether to offer the control, so while this was silently true the
     * chip was drawn here and did nothing when pressed — the exact failure the
     * flag exists to prevent, and the same rule the dive list applies to a row
     * it cannot draw.
     */
    override val supportsSmooth: Boolean = false

    override val label: String = "Dive computer"

    override val description: String = "A stepped trace on a segment screen."

    override val themes: List<SlateTheme> = RETRO_THEMES

    override val defaultScrimAlpha: Float = 1f

    /** Resampling interval for the stepped trace. See the class note. */
    private const val STEP_SECONDS: Double = 60.0
    private const val STEP_METRES: Double = 1.0

    override fun render(dive: Dive, options: OverlayOptions): Slate {
        val theme = options.theme
        val m = options.metrics
        val stats = resolveStats(dive, options)

        val site = dive.site?.takeIf { options.showSite && it.isNotEmpty() }?.uppercase()
        val date = if (options.showDate) dateLabel(dive)?.uppercase() else null
        // From the logbook or absent. The design's corner also carried a
        // battery gauge, which is instrument dressing standing in for a reading
        // that does not exist — the same objection as a figure guessed rather
        // than dropped.
        val stamp = dive.number?.let { "DIVE %02d".format(it) }

        var headingHeight = 0f
        if (site != null || stamp != null) headingHeight += m.siteSize + m.px(10f)
        if (date != null) headingHeight += m.dateSize + m.px(8f)
        if (headingHeight > 0f) headingHeight += m.gap

        val frame = SlateFrame.of(dive, options, stats, headingHeight)
        val ops = mutableListOf<SlateOp>()

        if (options.showScrim) {
            val alpha = options.resolvedScrimAlpha
            val bezel = m.px(16f)
            ops.add(
                SlateOp.Rect(
                    x = 0f, y = 0f, width = frame.width, height = frame.height,
                    cornerRadius = m.px(26f),
                    fill = SlateFill.Solid(withAlpha(theme.surfaceEdge, alpha)),
                )
            )
            ops.add(
                SlateOp.Rect(
                    x = bezel, y = bezel,
                    width = frame.width - bezel * 2f, height = frame.height - bezel * 2f,
                    cornerRadius = m.px(10f),
                    fill = SlateFill.Linear(
                        startArgb = withAlpha(theme.scrim, alpha),
                        endArgb = withAlpha(theme.surfaceTint, alpha),
                        angleDegrees = 90f,
                    ),
                )
            )
        }

        // ---- heading -------------------------------------------------------
        var y = frame.headingTop
        if (site != null || stamp != null) {
            y += m.siteSize * 0.78f
            if (site != null) {
                val taken = stamp?.let {
                    advanceOf(it, m.labelSize * 1.15f, SlateFont.MONO) + m.px(16f)
                } ?: 0f
                val room = frame.inner - taken
                ops.add(
                    SlateOp.Text(
                        text = site, x = frame.left, baselineY = y,
                        sizePx = fittedSize(site, m.siteSize, room, SlateFont.MONO, 0.22f),
                        fillArgb = theme.ink, haloArgb = theme.halo, haloWidth = 0f,
                        bold = true, letterSpacingEm = 0.22f, font = SlateFont.MONO,
                    )
                )
            }
            if (stamp != null) {
                ops.add(
                    SlateOp.Text(
                        text = stamp, x = frame.right, baselineY = y,
                        sizePx = m.labelSize * 1.15f,
                        fillArgb = theme.inkSecondary, haloArgb = theme.halo,
                        haloWidth = 0f, bold = true, font = SlateFont.MONO,
                        anchor = TextAnchor.END,
                    )
                )
            }
            y += m.px(10f)
            ops.add(
                SlateOp.Line(
                    start = Pt(frame.left, y), end = Pt(frame.right, y),
                    argb = withAlpha(theme.ink, 0.35f), strokeWidth = m.px(3f),
                )
            )
        }
        if (date != null) {
            y += m.px(8f) + m.dateSize * 0.85f
            ops.add(
                SlateOp.Text(
                    text = date, x = frame.left, baselineY = y, sizePx = m.dateSize,
                    fillArgb = theme.inkSecondary, haloArgb = theme.halo, haloWidth = 0f,
                    bold = true, font = SlateFont.MONO,
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
        val steps = steppedProfile(dive, frame, STEP_SECONDS, STEP_METRES)
        if (steps.isNotEmpty()) {
            ops.add(
                SlateOp.Path(
                    points = closedToSurface(steps, frame.plotTop),
                    closed = true,
                    fill = SlateFill.Solid(theme.curveFillTop),
                    crisp = true,
                )
            )
        }
        if (options.showCeiling) {
            ops.addAll(
                ceilingOps(
                    dive = dive, frame = frame, theme = theme,
                    strokeWidth = m.px(2.6f), dash = Dash(m.px(5f), m.px(5f)),
                    hatchSpacing = m.px(12f), hatchWidth = m.px(1.8f),
                    crisp = true,
                )
            )
        }
        if (steps.isNotEmpty()) {
            ops.add(
                SlateOp.Path(
                    points = steps, closed = false,
                    strokeArgb = theme.curve, strokeWidth = m.px(4.5f),
                    crisp = true,
                )
            )
        }
        if (options.showGas) ops.addAll(gasOps(dive, frame, theme, SlateFont.MONO))

        // ---- figures -------------------------------------------------------
        val ink = FigureInk(
            valueFont = SlateFont.MONO,
            labelFont = SlateFont.MONO,
            valueArgb = theme.ink,
            unitArgb = theme.inkSecondary,
            labelArgb = theme.inkMuted,
            haloArgb = theme.halo,
            haloWidth = 0f,
            // The slant a segment display has, standing in for a seven-segment
            // face this does not ship.
            italic = true,
            labelSpacing = 0.12f,
        )
        for ((index, slot) in frame.figureSlots().withIndex()) {
            ops.addAll(figureOps(stats[index], slot.first, slot.second, m, ink))
        }

        return Slate(width = frame.width, height = frame.height, ops = ops)
    }
}
