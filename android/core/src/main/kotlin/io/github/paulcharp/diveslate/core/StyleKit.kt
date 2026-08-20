package io.github.paulcharp.diveslate.core

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * What the styles share, and — just as deliberately — what they do not.
 *
 * A style is the art direction, and art direction is not a set of parameters
 * that can be dialled from outside: a hatched ceiling and a solid band with a
 * torn edge do not differ by a flag. So the drawing stays in the style. What
 * lands here is everything underneath the drawing that is not a choice at all —
 * where the plot's left edge is, which pixel a depth maps to, which samples
 * survive decimation, how wide a string will be in a face core cannot measure.
 *
 * The test for whether something belongs here is whether two styles could
 * reasonably disagree about it. They cannot disagree about where a sample sits
 * on the time axis. They can disagree about everything drawn there.
 *
 * [ModernStyle] predates this file and does not use it. That is on purpose:
 * refactoring the one style whose output is pinned by several hundred lines of
 * assertions, in the same change that adds eight more, would make a regression
 * in the old style look exactly like a bug in the new ones.
 */

// ---------------------------------------------------------------------------
// text, in a module that has no fonts

/**
 * Estimated width of [text], from character count and the face's average.
 *
 * Core emits a display list and never touches a font, so measuring properly is
 * not available at this layer — the app could, but then the geometry would stop
 * being testable on the JVM, which is the whole point of the split. The
 * estimate is deliberately generous: overestimating costs a little air in a
 * column, underestimating puts two figures on top of each other.
 */
internal fun advanceOf(
    text: String,
    sizePx: Float,
    font: SlateFont,
    letterSpacingEm: Float = 0f,
): Float = text.length * sizePx * (perCharacter(text, font) + letterSpacingEm)

/**
 * Width to give a *box* drawn around [text] — a pill, a chip, a legend.
 *
 * Deliberately larger than [advanceOf], and the asymmetry is the point. Core
 * estimates text width from character count and never measures, so the estimate
 * is sometimes short; when it is short for a label standing on its own, the
 * label is a few pixels narrower than the space reserved for it and nobody can
 * tell. When it is short for a label with a background, the text comes out of
 * its own background, which is the one failure that looks like a bug rather
 * than a layout.
 *
 * So a box takes the wider of the face's two averages, with a margin on top,
 * and the text inside it is centred rather than set from one edge — an
 * overestimate then shows as a little air at both ends instead of a word
 * hanging off one of them.
 */
internal fun boxedAdvance(text: String, sizePx: Float, font: SlateFont): Float =
    text.length * sizePx * maxOf(font.advance, font.digitAdvance) * 1.14f

/**
 * Which of the face's two averages applies to this string.
 *
 * Decided from the text rather than by the caller, because the caller often
 * cannot know: a figure's value is `45` on one dive and `Air, O2` on the next,
 * and the same code sets both. Anything mostly made of digits and the
 * punctuation that goes with them — `1:05`, `70/80`, `15.4` — is measured as
 * digits; everything else takes the prose average.
 */
private fun perCharacter(text: String, font: SlateFont): Float {
    if (text.isEmpty()) return font.advance
    val numeric = text.count { it.isDigit() || it in ":/.," }
    return if (numeric * 10 >= text.length * 6) font.digitAdvance else font.advance
}

/**
 * The largest size at or below [nominal] that keeps [text] inside [available].
 *
 * Letter spacing is part of the width, and forgetting it is not a rounding
 * error: the headings here are tracked out to a third of an em, so an estimate
 * that ignores it is short by more than half the string. That was first patched
 * with a fudge factor per style, which is the same mistake written down four
 * times — the spacing is known, so it is measured.
 */
internal fun fittedSize(
    text: String,
    nominal: Float,
    available: Float,
    font: SlateFont,
    letterSpacingEm: Float = 0f,
): Float {
    if (text.isEmpty() || available <= 0f) return nominal
    return minOf(
        nominal,
        available / (text.length * (perCharacter(text, font) + letterSpacingEm)),
    )
}

// ---------------------------------------------------------------------------
// the frame

/**
 * Where the blocks of a slate land, before anything is drawn in them.
 *
 * Every style arranges the same three things — a heading, the profile, the
 * figures — and the layout already says which order they come in and how much
 * room each gets. Computing that once means a style added later cannot get the
 * `figuresLead` case wrong by forgetting it exists, which is the kind of bug
 * that shows up in one of the four layouts and nowhere else.
 */
internal class SlateFrame private constructor(
    val m: LayoutMetrics,
    val stats: List<SlateStat>,
    /** Total slate height, including padding. */
    val height: Float,
    val headingTop: Float,
    val plotTop: Float,
    val statsTop: Float,
    val figureHeight: Float,
    /** What the style asked for; see [SlateFrame.of]. */
    private val durationSeconds: Double,
    private val depthMax: Double,
) {
    val width: Float get() = m.width
    val left: Float get() = m.pad
    val right: Float get() = m.width - m.pad
    val inner: Float get() = m.width - m.pad * 2
    val plotBottom: Float get() = plotTop + m.curveHeight

    /** Seconds to x. */
    fun sx(seconds: Double): Float =
        left + (seconds / durationSeconds).toFloat() * (right - left)

    /** Metres to y. The depth axis always starts at the surface. */
    fun sy(metres: Double): Float = plotTop + (metres / depthMax).toFloat() * m.curveHeight

    /** Each figure's origin and the width it may use, in order. */
    fun figureSlots(): List<Pair<Pt, Float>> {
        if (stats.isEmpty()) return emptyList()
        return if (m.figuresStacked) {
            stats.indices.map { index ->
                Pt(left, statsTop + index * (figureHeight + m.gap)) to inner
            }
        } else {
            val slot = inner / stats.size
            stats.indices.map { index -> Pt(left + index * slot, statsTop) to slot }
        }
    }

    companion object {
        /**
         * @param headingHeight room the style wants above the body, 0 for none.
         * @param extraBelowPlot room for anything drawn under the profile but
         *   above the figures — a divider, a rule, a legend strip.
         *
         * There used to be a `figureScale` here, so a style drawing each figure
         * in a container could charge the padding to the figure rather than to
         * the badge. It is gone with the style that used it, and the reason it
         * is not being kept for the next one is that it was the wrong lever:
         * the layout's sizes are a promise — the watch badge sets 88px numerals
         * *because* it stacks them — and a style that scales them down to pay
         * for its own ornament breaks that promise silently, which is exactly
         * what happened. Ornament that does not fit shrinks the ornament.
         */
        fun of(
            dive: Dive,
            options: OverlayOptions,
            stats: List<SlateStat>,
            headingHeight: Float,
            extraBelowPlot: Float = 0f,
        ): SlateFrame {
            val m = options.metrics
            val figureHeight = m.valueSize + m.labelSize + m.px(10f)
            val rows = if (m.figuresStacked) max(stats.size, 1) else 1
            val statsBlock = figureHeight * rows + m.gap * (rows - 1)

            val height = m.pad + headingHeight + m.curveHeight + extraBelowPlot +
                m.gap + statsBlock + m.pad
            val bodyTop = m.pad + headingHeight
            val leads = m.figuresLead
            val plotTop = if (leads) bodyTop + statsBlock + m.gap else bodyTop
            val statsTop =
                if (leads) bodyTop else bodyTop + m.curveHeight + extraBelowPlot + m.gap

            return SlateFrame(
                m = m,
                stats = stats,
                height = height,
                headingTop = m.pad,
                plotTop = plotTop,
                statsTop = statsTop,
                figureHeight = figureHeight,
                durationSeconds = max(dive.computedDurationSeconds, 1.0),
                // Headroom, so the deepest point does not sit on the baseline.
                depthMax = max(dive.computedMaxDepthMetres, 1.0) * 1.06,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// the profile

/** The depth profile, decimated to about two points per horizontal pixel. */
internal fun profilePoints(dive: Dive, frame: SlateFrame): List<Pt> = envelope(
    dive.samples.map { Pt(frame.sx(it.timeSeconds), frame.sy(it.depthMetres)) },
    frame.right - frame.left,
)

/** A profile closed back along the surface, ready to fill. */
internal fun closedToSurface(points: List<Pt>, surfaceY: Float): List<Pt> = buildList {
    add(Pt(points.first().x, surfaceY))
    addAll(points)
    add(Pt(points.last().x, surfaceY))
}

/**
 * The profile redrawn as square steps, the way a segment display would show it.
 *
 * Resampled in *data* space rather than by rounding pixels, so the steps are
 * the same size whatever the slate's own size — a screen that quantised more
 * finely on a bigger badge would be quantising the picture rather than
 * modelling an instrument.
 *
 * The step is fine on purpose. The mockup this came from snapped to 2 minutes
 * and 3 metres, which turned the reference dive's sawtooth bottom into a
 * staircase and moved its deepest drawn point by nearly two metres while the
 * figure beside it still read 45. Nothing was misstated, but the picture had
 * stopped matching the number. At one minute and one metre the silhouette is
 * still the dive's and the steps still read as a display.
 */
internal fun steppedProfile(
    dive: Dive,
    frame: SlateFrame,
    stepSeconds: Double,
    stepMetres: Double,
): List<Pt> {
    if (dive.samples.isEmpty()) return emptyList()

    val buckets = LinkedHashMap<Int, Double>()
    for (sample in dive.samples) {
        val slot = (sample.timeSeconds / stepSeconds).toInt()
        // Deepest wins. Averaging would draw a dive shallower than the one the
        // figures report, which is the one thing a stylised profile may not do.
        buckets[slot] = max(buckets[slot] ?: 0.0, sample.depthMetres)
    }

    val points = mutableListOf<Pt>()
    var previousY: Float? = null
    for ((slot, depth) in buckets) {
        val snapped = ceil(depth / stepMetres) * stepMetres
        val x = frame.sx(slot * stepSeconds)
        val y = frame.sy(snapped)
        val last = previousY
        if (last != null && y != last) points.add(Pt(x, last))
        points.add(Pt(x, y))
        previousY = y
    }
    previousY?.let { points.add(Pt(frame.sx(dive.computedDurationSeconds), it)) }
    return points
}

/**
 * How coarse the series gets before it is drawn as a curve, at 1080px.
 *
 * A polyline wants [envelope]'s two points per pixel; a curve wants far fewer,
 * since a spline through points a pixel apart is a polyline with extra
 * arithmetic. This started at 34, which was the point where the reference dive
 * stopped reading as a jagged line someone had rounded off — enough to make the
 * line legal as a curve, not enough to make it *look* like one. At 64 the
 * ascent reads as one continuous sweep and the sawtooth bottom as a rolling
 * floor, which is what a dive profile actually looks like.
 *
 * **This number is free to move because of what [coarsened] guarantees.** Every
 * bucket keeps its shallowest *and* its deepest sample, so a coarser step
 * changes how much of the wobble survives and never where the extremes are: the
 * deepest point of the dive is on the drawing at any step, and the profile can
 * never contradict the figure printed beside it. Were the reduction an average,
 * or a first-sample rule, this constant would be a depth error waiting for
 * someone to tune it.
 *
 * What it does cost is detail, and that is why smoothing stayed a choice —
 * [OverlayOptions.smoothProfile] turns it off for a reader who wants every
 * tooth of a sawtooth bottom rather than a line through them.
 */
internal const val SMOOTH_STEP_PX = 64f

/**
 * The series a style should draw its profile through, given the options.
 *
 * One place, so that "smooth" means the same thing on every style that offers
 * it. Callers pair this with `smooth = options.smoothProfile` on the ops they
 * emit — the two go together: coarsening without smoothing is just a worse
 * polyline, and smoothing without coarsening is arithmetic nobody can see.
 *
 * The one style that does not come through here is the segment screen, which
 * quantises to one minute and one metre and draws its own steps.
 */
internal fun profileTrace(
    dive: Dive,
    frame: SlateFrame,
    options: OverlayOptions,
): List<Pt> {
    val points = profilePoints(dive, frame)
    return if (options.smoothProfile) {
        coarsened(points, frame.m.px(SMOOTH_STEP_PX))
    } else {
        points
    }
}

/**
 * The profile at a coarser grain, keeping each bucket's extremes.
 *
 * [envelope] reduces to about two points per *pixel*, which is the right answer
 * for a line drawn through every one of them and the wrong one for a line drawn
 * as a curve: a spline through points a pixel apart is a polyline with extra
 * arithmetic. This buckets by [stepPx] instead, and keeps the same two samples
 * per bucket that [envelope] does — the shallowest and the deepest, in the order
 * they occurred.
 *
 * Keeping both is what makes this a reduction rather than a redrawing. Dropping
 * to one sample per bucket would smooth the profile by *losing* the deepest
 * point in it, and the slate would then draw a dive shallower than the figure
 * printed beside it.
 */
internal fun coarsened(points: List<Pt>, stepPx: Float): List<Pt> {
    if (points.size < 3 || stepPx <= 1f) return points

    val buckets = LinkedHashMap<Int, MutableList<Pt>>()
    for (point in points) {
        buckets.getOrPut((point.x / stepPx).toInt()) { mutableListOf() }.add(point)
    }

    val reduced = mutableListOf<Pt>()
    for (bucket in buckets.values) {
        if (bucket.size <= 2) {
            reduced.addAll(bucket)
            continue
        }
        val shallowest = bucket.minBy { it.y }
        val deepest = bucket.maxBy { it.y }
        if (shallowest == deepest) {
            reduced.add(shallowest)
        } else {
            reduced.addAll(
                listOf(shallowest, deepest).sortedBy { bucket.indexOf(it) }
            )
        }
    }
    return reduced
}

// ---------------------------------------------------------------------------
// the ceiling

/**
 * The deco ceiling as one stepped edge per obligation, in slate coordinates.
 *
 * A ceiling steps: it is a series of stop depths, not a smooth line, and
 * sloping between them would draw a diver rising through a boundary they were
 * held below. Runs stay separate because a dive that clears deco and re-incurs
 * it served two obligations, and joining them would draw a stop across the
 * interval in between that never existed.
 */
internal fun ceilingEdges(dive: Dive, frame: SlateFrame): List<List<Pt>> {
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

    return runs.mapNotNull { run ->
        val edge = mutableListOf<Pt>()
        var previous: Float? = null
        for (sample in run) {
            val x = frame.sx(sample.timeSeconds)
            val y = frame.sy(sample.stopDepthMetres ?: 0.0)
            val last = previous
            if (last != null && y != last) edge.add(Pt(x, last))
            edge.add(Pt(x, y))
            previous = y
        }
        edge.takeIf { it.isNotEmpty() }
    }
}

/**
 * The ceiling drawn the way every style must draw it: hatch, then dashed edge.
 *
 * Shared rather than left to each style, because this pair is not art
 * direction. The hatch says *region you may not enter*; the dash says
 * *boundary*. Between them they carry the hazard without help from colour —
 * which is the only reason a style is allowed to paint the ceiling white on
 * violet, or in the same ink as everything else on a monochrome screen. A style
 * that took the colour freedom and dropped these marks would have kept the
 * concession and thrown away the thing that justified it.
 */
internal fun ceilingOps(
    dive: Dive,
    frame: SlateFrame,
    theme: SlateTheme,
    strokeWidth: Float,
    dash: Dash,
    hatchSpacing: Float,
    hatchWidth: Float,
    crisp: Boolean = false,
): List<SlateOp> {
    val edges = ceilingEdges(dive, frame)
    if (edges.isEmpty()) return emptyList()

    val hatch = SlateFill.Hatch(
        argb = withAlpha(theme.ceiling, 0.34f),
        spacing = hatchSpacing,
        strokeWidth = hatchWidth,
    )
    return buildList {
        for (edge in edges) {
            add(
                SlateOp.Path(
                    points = closedToSurface(edge, frame.plotTop),
                    closed = true,
                    fill = hatch,
                    crisp = crisp,
                )
            )
            add(
                SlateOp.Path(
                    points = edge,
                    closed = false,
                    strokeArgb = theme.ceiling,
                    strokeWidth = strokeWidth,
                    dash = dash,
                    crisp = crisp,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// gas switches

/**
 * Gas-switch markers: a dot on the profile, a leader up to the surface, and the
 * mix named in a tab at the top of the plot.
 *
 * The label is not the style's to drop. The accent sits below 3:1 against the
 * surface in several palettes, which the gates permit *only* because the text
 * carries the identity — without it the mark is one that colour alone has to
 * distinguish, which under colour-vision deficiency it cannot.
 *
 * Which is exactly why it is drawn this way rather than beside the dot, as the
 * designs have it. Beside the dot, the name lands wherever the diver happened to
 * be: on the curve, inside the hatched ceiling, over the fill, or off the right
 * edge near the end of a dive — and on an opaque card there is no halo to save
 * it, because the halo was dropped as pointless on a known background. A label
 * that is mandatory cannot also be the one placed by luck. At the surface line
 * it is always in the plot's quietest band, always at the same height, and the
 * leader says which moment it belongs to; where two switches fall close
 * together the tabs step down a row instead of printing through each other.
 */
internal fun gasOps(
    dive: Dive,
    frame: SlateFrame,
    theme: SlateTheme,
    font: SlateFont,
): List<SlateOp> {
    if (dive.samples.isEmpty() || dive.gasSwitches.isEmpty()) return emptyList()
    val m = frame.m
    val ops = mutableListOf<SlateOp>()

    // Sized against the plot, not only against the slate. A badge whose profile
    // is 60px of sparkline cannot give a third of it to a name tag, and px()
    // alone would: it scales with the canvas, which the corner layouts do not
    // shrink. A fifth of the plot's height leaves the tab reading as a label on
    // a sparkline rather than as the sparkline's main event.
    val size = minOf(m.px(22f), m.curveHeight * 0.2f)
    val padX = size * 0.42f
    val tabHeight = size + size * 0.5f
    var previousRight = Float.NEGATIVE_INFINITY
    var row = 0

    for (switch in dive.gasSwitches.sortedBy { it.timeSeconds }) {
        val nearest = dive.samples.minBy { abs(it.timeSeconds - switch.timeSeconds) }
        val x = frame.sx(switch.timeSeconds)
        val y = frame.sy(nearest.depthMetres)

        val name = switch.gas.name
        val width = boxedAdvance(name, size, font) + padX * 2f
        // Kept inside the plot: near the end of a dive the switch sits at the
        // right edge, and a tab centred on it would hang off the slate.
        val tabLeft = (x - width / 2f).coerceIn(frame.left, frame.right - width)
        row = if (tabLeft < previousRight + m.px(4f)) row + 1 else 0
        previousRight = tabLeft + width
        // Straddling the surface line, not floating inside the water.
        //
        // The tab used to hang a fixed distance below the surface, which is fine
        // for a switch at depth and falls apart for one near it: the leader
        // shrinks to nothing, the marker rises into the tab, and the two draw
        // through each other — a switch at 3 m produced a pill with a circle
        // sitting on top of it. On the surface line the tab is always in the
        // same place whatever the depth, which is also where the eye is already
        // looking for the start of the dive.
        val tabTop = frame.plotTop - tabHeight / 2f + row * (tabHeight + m.px(4f))
        val tabBottom = tabTop + tabHeight

        // Marker first, then the leader, then the tab on top of both.
        //
        // Paint order is the whole of it: the tab carries the only text the
        // palette gates permit this mark to be identified by, so nothing may be
        // drawn over it. Whatever the marker and the tab share, the tab wins.
        ops.add(
            SlateOp.Circle(
                centre = Pt(x, y),
                radius = m.px(7f),
                fillArgb = theme.accent,
                strokeArgb = theme.ink,
                strokeWidth = m.px(2f),
            )
        )

        // The leader, only where there is a run to draw. A dotted line two
        // pixels long is a speck beside the tab, not a line to anywhere.
        if (y > tabBottom + m.px(6f)) {
            ops.add(
                SlateOp.Line(
                    start = Pt(x, tabBottom), end = Pt(x, y),
                    argb = withAlpha(theme.accent, 0.7f),
                    strokeWidth = m.px(2f),
                    dash = Dash(m.px(6f), m.px(5f)),
                )
            )
        }
        ops.add(
            SlateOp.Rect(
                x = tabLeft, y = tabTop, width = width, height = tabHeight,
                cornerRadius = tabHeight / 2f,
                // The panel colour, opaque, outlined in the accent — not filled
                // with the accent. Filling with it made the label's legibility
                // depend on a colour chosen to separate from *other marks*,
                // which is a different question: on the wrapped card the accent
                // was the same pink as the water it sat on, and `O2` in two
                // characters had nothing to be read against. Ink on panel is the
                // one pairing every palette has already had measured, so the
                // label lands on it and the accent does what it is for —
                // marking which mark this is.
                fill = SlateFill.Solid(withAlpha(theme.scrim, 1f)),
                strokeArgb = theme.accent,
                strokeWidth = m.px(2.5f),
            )
        )
        ops.add(
            SlateOp.Text(
                text = name,
                // Centred in the tab rather than set from its left edge: the
                // width is an estimate, and a centred word absorbs the error at
                // both ends instead of walking out of one of them.
                x = tabLeft + width / 2f,
                baselineY = tabTop + tabHeight / 2f + size * 0.36f,
                sizePx = size,
                fillArgb = theme.ink,
                haloArgb = theme.halo,
                haloWidth = 0f,
                bold = true,
                font = font,
                anchor = TextAnchor.MIDDLE,
            )
        )
    }
    return ops
}

// ---------------------------------------------------------------------------
// figures

/**
 * How one style typesets a figure: colours and faces only.
 *
 * Never placement — that is the layout's, and it arrives through
 * [SlateFrame.figureSlots].
 */
internal data class FigureInk(
    val valueFont: SlateFont,
    val labelFont: SlateFont,
    val valueArgb: Long,
    val unitArgb: Long,
    val labelArgb: Long,
    val haloArgb: Long,
    val haloWidth: Float,
    val valueBold: Boolean = true,
    val italic: Boolean = false,
    val labelSpacing: Float = 0.10f,
    val uppercaseLabel: Boolean = true,
    val align: TextAnchor = TextAnchor.START,
)

/**
 * One figure: value, unit, label.
 *
 * The value shrinks to fit its column and the label does not, because they fail
 * differently. A value is a number and stays a number at 80% of nominal; a
 * label shrunk to fit would set `MAX DEPTH` at a size nobody reads while the
 * number beside it had room to spare. Baselines come off the layout's nominal
 * size rather than the fitted one, so a shrunk figure still sits on the line
 * its neighbours do.
 */
internal fun figureOps(
    stat: SlateStat,
    origin: Pt,
    available: Float,
    m: LayoutMetrics,
    ink: FigureInk,
): List<SlateOp> {
    val unitSize = m.labelSize * 1.35f
    val unitRoom =
        if (stat.unit.isEmpty()) 0f
        else m.px(8f) + advanceOf(stat.unit, unitSize, ink.labelFont)
    val nominal = m.valueSize
    val valueSize = fittedSize(
        stat.value,
        nominal,
        available - m.px(14f) - unitRoom,
        ink.valueFont,
    )
    val baseline = origin.y + nominal * 0.8f
    val valueWidth = advanceOf(stat.value, valueSize, ink.valueFont) + unitRoom

    fun placed(width: Float): Float = when (ink.align) {
        TextAnchor.START -> origin.x
        TextAnchor.MIDDLE -> origin.x + (available - width) / 2f
        TextAnchor.END -> origin.x + available - width
    }

    val valueX = placed(valueWidth)
    val ops = mutableListOf<SlateOp>(
        SlateOp.Text(
            text = stat.value,
            x = valueX,
            baselineY = baseline,
            sizePx = valueSize,
            fillArgb = ink.valueArgb,
            haloArgb = ink.haloArgb,
            haloWidth = ink.haloWidth,
            bold = ink.valueBold,
            font = ink.valueFont,
            italic = ink.italic,
        )
    )
    if (stat.unit.isNotEmpty()) {
        ops.add(
            SlateOp.Text(
                text = stat.unit,
                x = valueX + advanceOf(stat.value, valueSize, ink.valueFont) + m.px(8f),
                baselineY = baseline,
                sizePx = unitSize,
                fillArgb = ink.unitArgb,
                haloArgb = ink.haloArgb,
                haloWidth = ink.haloWidth * 0.7f,
                font = ink.labelFont,
                italic = ink.italic,
            )
        )
    }
    val label = if (ink.uppercaseLabel) stat.label.uppercase() else stat.label
    ops.add(
        SlateOp.Text(
            text = label,
            x = placed(advanceOf(label, m.labelSize, ink.labelFont, ink.labelSpacing)),
            baselineY = baseline + m.labelSize + m.px(8f),
            sizePx = m.labelSize,
            fillArgb = ink.labelArgb,
            haloArgb = ink.haloArgb,
            haloWidth = ink.haloWidth * 0.7f,
            bold = true,
            letterSpacingEm = ink.labelSpacing,
            font = ink.labelFont,
        )
    )
    return ops
}

/** `18 Aug 2025` — the one date format the slate uses, whatever the style. */
internal fun dateLabel(dive: Dive): String? =
    dive.whenLogged?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))

/**
 * Depth gridlines that come from the dive rather than from a fixed ruler.
 *
 * The survey mockup drew hairlines at 15, 30 and 45 metres, which is a scale
 * bar for a dive that happened to be 45 metres deep. On a 12-metre reef it
 * draws nothing at all, and on a 60-metre dive it stops two thirds of the way
 * down — in both cases silently, so the map looks finished and is wrong. The
 * interval is chosen from the depth actually reached, in steps a diver reads
 * in, and the lines are labelled by the style that draws them.
 *
 * Returned **in the units being printed**, and the ladder of steps is chosen
 * per system, because a round number is the whole point of a gridline: 10, 20,
 * 30 in metres and 25, 50, 75 in feet are both what a diver reads, whereas feet
 * converted from a metric ladder would label the map 33, 66, 98. The caller
 * converts back with [metresOfDepth] to place them, so the geometry stays in
 * the one canonical unit.
 */
internal fun depthGridlines(
    maxDepthMetres: Double,
    units: SlateUnits = SlateUnits.METRIC,
    count: Int = 3,
): List<Double> {
    if (maxDepthMetres <= 0.0 || count <= 0) return emptyList()
    val max = depthInUnits(maxDepthMetres, units)
    val rough = max / (count + 1)
    val ladder = if (units == SlateUnits.METRIC) {
        listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0)
    } else {
        listOf(5.0, 10.0, 20.0, 25.0, 50.0, 60.0, 75.0, 100.0)
    }
    val coarsest = if (units == SlateUnits.METRIC) 50.0 else 150.0
    val step = ladder.firstOrNull { it >= rough } ?: coarsest
    return generateSequence(step) { it + step }
        .takeWhile { it < max }
        .toList()
}
