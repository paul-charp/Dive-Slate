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

sealed interface SlateOp {

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        val fill: SlateFill,
    ) : SlateOp

    data class Line(
        val start: Pt,
        val end: Pt,
        val argb: Long,
        val strokeWidth: Float,
    ) : SlateOp

    data class Path(
        val points: List<Pt>,
        val closed: Boolean,
        val fill: SlateFill? = null,
        val strokeArgb: Long? = null,
        val strokeWidth: Float = 0f,
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
    ) : SlateOp
}
