package io.github.paulcharp.diveslate.core

/**
 * A resolved slate: everything to paint, and nothing about how to paint it.
 *
 * The layout lives here in plain Kotlin rather than in the Android module, so
 * the geometry is testable on the JVM. The app walks [ops] and issues the
 * corresponding Compose Canvas calls; it makes no decisions of its own.
 *
 * That split is what makes "does the scrim fit the text" a question a unit test
 * can answer, instead of one that needs a device and a screenshot.
 */
data class Slate(
    val width: Float,
    val height: Float,
    val ops: List<SlateOp>,
)

/** How an area is filled. */
sealed interface SlateFill {
    data class Solid(val argb: Long) : SlateFill

    /** Top-to-bottom gradient, used for the area under the depth curve. */
    data class Vertical(val topArgb: Long, val bottomArgb: Long) : SlateFill

    /**
     * A gradient at an arbitrary angle. 90° is [Vertical]; 0° runs left to right.
     *
     * Separate from [Vertical] rather than replacing it because the vertical
     * case is the one every style uses for the water column, and a wash under a
     * depth curve that is not aligned with depth would be decoration pretending
     * to be data.
     */
    data class Linear(
        val startArgb: Long,
        val endArgb: Long,
        val angleDegrees: Float = 90f,
    ) : SlateFill

    /**
     * A regular grid of dots — the tech-panel texture behind a HUD.
     *
     * A texture, not a grid in the chart sense: it carries no values and is not
     * aligned to depth or time. That is why it is a fill rather than a set of
     * axis lines, and why nothing reads a depth off it.
     */
    data class Dots(val argb: Long, val radius: Float, val pitch: Float) : SlateFill

    /**
     * Deterministic speckle, for paper grain.
     *
     * [seed] is part of the value: the same slate must rasterise identically
     * every time it is exported, so the noise cannot come from a global random
     * source. Two exports of one dive that differ in their grain would be two
     * different files for no reason anybody could explain.
     */
    data class Grain(
        val argb: Long,
        val cell: Float,
        val density: Float,
        val seed: Int,
    ) : SlateFill

    /**
     * Diagonal hatching, used for the deco ceiling.
     *
     * Hatched rather than solid on purpose: the ceiling is a region the diver
     * must not enter, and a hatch reads as a barrier where a flat wash reads as
     * just another series.
     */
    data class Hatch(
        val argb: Long,
        val spacing: Float,
        val strokeWidth: Float,
        val angleDegrees: Float = 45f,
    ) : SlateFill
}

data class Pt(val x: Float, val y: Float)

/**
 * A stroke dash pattern, in pixels.
 *
 * Load-bearing rather than decorative. Where a palette is one ink — the LCD
 * screen, the magazine masthead — the dash is what separates the ceiling from
 * the profile, so it is doing the job hue does elsewhere. `SlateStyleTest`
 * holds those styles to it.
 */
data class Dash(val on: Float, val off: Float)

/**
 * Which face a label is set in.
 *
 * These are the families Android ships, deliberately: bundling the faces the
 * mockups named would add licensed binaries to the APK for a difference most
 * viewers see at a glance and nobody at export scale. What matters for layout
 * is [advance] — core has no font and cannot measure text, so each face carries
 * its own average character width and the fitting maths uses that. Getting it
 * wrong is not cosmetic: a condensed face estimated at the sans figure wastes a
 * third of every column, and a black one overruns into its neighbour.
 */
enum class SlateFont(
    val family: String,
    /** Mean character width, for prose: a site name, a label, a list of mixes. */
    val advance: Float,
    /**
     * Mean width of a digit, which is wider than the average character in every
     * one of these faces.
     *
     * Worth separating because the slate's figures are almost all digits and
     * the average is pulled down by letters they never contain — `i`, `l`, `t`,
     * punctuation. The gap shows up as a unit crowding the number it belongs
     * to: in the condensed face the average is 0.46 and a digit is 0.52, so the
     * `m` after a two-figure depth was placed six pixels inside the number.
     * Proportionally worse the larger the figure, which is why it was visible
     * on the one style that sets its figures biggest.
     */
    val digitAdvance: Float,
) {
    SANS("sans-serif", 0.56f, 0.57f),
    MEDIUM("sans-serif-medium", 0.57f, 0.58f),
    BLACK("sans-serif-black", 0.64f, 0.68f),
    CONDENSED("sans-serif-condensed", 0.46f, 0.52f),
    MONO("monospace", 0.60f, 0.60f),
    SERIF("serif", 0.52f, 0.55f),
}

/** Which end of a label sits at its x. */
enum class TextAnchor { START, MIDDLE, END }

sealed interface SlateOp {

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        val fill: SlateFill? = null,
        val strokeArgb: Long? = null,
        val strokeWidth: Float = 0f,
    ) : SlateOp

    data class Line(
        val start: Pt,
        val end: Pt,
        val argb: Long,
        val strokeWidth: Float,
        val dash: Dash? = null,
    ) : SlateOp

    data class Path(
        val points: List<Pt>,
        val closed: Boolean,
        val fill: SlateFill? = null,
        val strokeArgb: Long? = null,
        val strokeWidth: Float = 0f,
        val dash: Dash? = null,
        /** Far end of a gradient stroke; null strokes flat in [strokeArgb]. */
        val strokeEndArgb: Long? = null,
        /**
         * Halo of light around the stroke, in pixels.
         *
         * Painted as a few wider, fainter passes of the same path rather than
         * as a blur. A blur mask is the obvious implementation and the wrong
         * one here: it is the part of the Android canvas most likely to be
         * dropped or approximated differently between the on-screen preview and
         * the software canvas the export rasterises through, and a glow that
         * only appears in one of them is worse than no glow.
         */
        val glowRadius: Float = 0f,
        /** Squared corners and no anti-aliasing — the LCD's stepped profile. */
        val crisp: Boolean = false,
        /**
         * Draw through the points as a curve rather than as straight segments.
         *
         * The interpolation is deliberately the flat-tangent kind: each segment
         * leaves one point horizontally and arrives at the next horizontally, so
         * the curve stays inside the vertical span of the two points it joins.
         * A spline with real tangents — Catmull-Rom, say — looks better through
         * a sparse series and *overshoots*, which here would draw the profile
         * deeper than the deepest sample and put the picture at odds with the
         * number printed beside it. Smoothing is allowed to change how the line
         * travels between two depths. It is not allowed to invent a third.
         */
        val smooth: Boolean = false,
    ) : SlateOp

    data class Circle(
        val centre: Pt,
        val radius: Float,
        val fillArgb: Long? = null,
        val strokeArgb: Long? = null,
        val strokeWidth: Float = 0f,
    ) : SlateOp

    /**
     * A text label, painted twice: a wide halo stroke beneath the fill.
     *
     * The output is transparent and lands on footage the renderer never sees, so
     * a single-pass label dies wherever the frame behind it happens to match its
     * colour. The halo is not decoration — dropping it is how the slate becomes
     * unreadable over video.
     */
    data class Text(
        val text: String,
        val x: Float,
        val baselineY: Float,
        val sizePx: Float,
        val fillArgb: Long,
        val haloArgb: Long,
        val haloWidth: Float,
        val bold: Boolean = false,
        val letterSpacingEm: Float = 0f,
        val font: SlateFont = SlateFont.SANS,
        val italic: Boolean = false,
        val anchor: TextAnchor = TextAnchor.START,
        /**
         * Far end of gradient ink, across the label's own width.
         *
         * Only ever an ornament — a heading, a wordmark. A figure never wears
         * one: a number whose colour changes across its digits reads as though
         * the colour meant something.
         */
        val gradientEndArgb: Long? = null,
    ) : SlateOp
}
