package io.github.paulcharp.diveslate.core

/**
 * The three axes a slate is chosen along, and the seam between them.
 *
 * They answer three different questions and are deliberately independent:
 *
 * * [SlateLayout] — **where things go and how big they are.** Page proportions:
 *   padding, type scale, how much vertical room the profile gets.
 * * [SlateStyle] — **how it is drawn.** The art direction: what a curve, a
 *   ceiling and a label look like as marks.
 * * [SlateTheme] — **what colour it is.** A palette that cleared the gates in
 *   `tools/palette.py`.
 *
 * Two rules hold that structure up, and both are load-bearing:
 *
 * 1. **Every layout works for every style.** A layout contributes
 *    [LayoutMetrics] and nothing else — a table of numbers, with no drawing in
 *    it. A style reads those numbers rather than deciding its own, so adding a
 *    layout cannot miss a style and adding a style cannot miss a layout.
 * 2. **A style carries its themes.** A palette is validated against the marks
 *    it will be painted as, so it belongs to the style that paints them, not to
 *    the app at large. [SlateStyle.themes] is that list, and rendering a style
 *    with a palette it does not own is refused rather than guessed at.
 */

/**
 * How the slate is proportioned.
 *
 * The numbers are given at the reference width of 1080px and scaled from there
 * by [metrics], so a layout is a row of constants rather than a branch in the
 * renderer — which is what makes adding a third one cheap and what keeps every
 * style getting the same treatment from it.
 */
enum class SlateLayout(
    val id: String,
    val label: String,
    private val pad: Float,
    private val gap: Float,
    private val siteSize: Float,
    private val dateSize: Float,
    private val valueSize: Float,
    private val labelSize: Float,
    private val curveHeight: Float,
) {
    /** A landscape strip, to sit in the corner of a 16:9 frame. */
    WIDE(
        id = "wide", label = "Wide",
        pad = 44f, gap = 26f,
        siteSize = 34f, dateSize = 22f, valueSize = 56f, labelSize = 18f,
        curveHeight = 210f,
    ),

    /** Portrait, for a 9:16 story — the profile gets roughly twice the height. */
    TALL(
        id = "tall", label = "Tall",
        pad = 56f, gap = 34f,
        siteSize = 46f, dateSize = 28f, valueSize = 86f, labelSize = 24f,
        curveHeight = 430f,
    );

    /** This layout's proportions realised at [width] pixels. */
    fun metrics(width: Float): LayoutMetrics {
        val scale = width / REFERENCE_WIDTH
        return LayoutMetrics(
            width = width,
            scale = scale,
            pad = pad * scale,
            gap = gap * scale,
            siteSize = siteSize * scale,
            dateSize = dateSize * scale,
            valueSize = valueSize * scale,
            labelSize = labelSize * scale,
            curveHeight = curveHeight * scale,
        )
    }

    companion object {
        /**
         * The width the proportions above are quoted at. 1080 is Instagram's
         * native width, so a slate dropped in at full width stays pixel-crisp.
         */
        const val REFERENCE_WIDTH: Float = 1080f

        fun byId(id: String): SlateLayout? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One layout's proportions, resolved to pixels.
 *
 * Everything a style needs to place its marks, and nothing about what those
 * marks look like. [scale] is here so a style can size its own details — a
 * stroke width, a hatch spacing — in the same reference units as the layout.
 */
data class LayoutMetrics(
    val width: Float,
    /** Multiplier from the reference width; 1.0 at 1080px. */
    val scale: Float,
    val pad: Float,
    val gap: Float,
    val siteSize: Float,
    val dateSize: Float,
    val valueSize: Float,
    val labelSize: Float,
    val curveHeight: Float,
) {
    /** A reference-unit measurement in pixels at this size. */
    fun px(referenceUnits: Float): Float = referenceUnits * scale
}

/**
 * An artistic style: how the slate is drawn, and which palettes it offers.
 *
 * The style *is* the renderer. Art direction is not a set of parameters that
 * can be dialled from outside — a style that hatched its ceiling and one that
 * drew it as a solid band with a torn edge do not differ by a flag — so the
 * whole display list is the style's to produce.
 *
 * What it must not do is decide proportions. Those come from
 * [OverlayOptions.layout] via [SlateLayout.metrics], which is what makes every
 * layout work with every style rather than only with the one it was drawn for.
 */
interface SlateStyle {
    /** Stable identifier. Persisted and used to look a style back up. */
    val id: String

    /** What the picker calls it. */
    val label: String

    /** One line on what the look is, for the picker. */
    val description: String

    /**
     * The palettes this style offers, default first.
     *
     * A palette is validated against the marks it will be painted as — the
     * curve, the ceiling and the gas accent, checked as a set — so it is a
     * property of the style that paints them. A style whose marks differ enough
     * to change that maths brings its own list rather than borrowing this one.
     */
    val themes: List<SlateTheme>

    val defaultTheme: SlateTheme get() = themes.first()

    /** Produce the display list. [dive] is guaranteed to have samples. */
    fun render(dive: Dive, options: OverlayOptions): Slate
}

/** Every style, default first. */
val SLATE_STYLES: List<SlateStyle> = listOf(ModernStyle)

fun styleById(id: String): SlateStyle? = SLATE_STYLES.firstOrNull { it.id == id }

fun SlateStyle.themeNamed(name: String): SlateTheme? = themes.firstOrNull { it.name == name }

/**
 * The nearest thing to [theme] that this style actually offers.
 *
 * Switching style must not silently move the slate from a dark palette to a
 * light one: the choice between them is a statement about the footage the slate
 * will land on, which the new style knows nothing about. So the same name wins,
 * then anything of the same mode, and only then the default.
 */
fun SlateStyle.adopt(theme: SlateTheme): SlateTheme =
    themeNamed(theme.name)
        ?: themes.firstOrNull { it.isDark == theme.isDark }
        ?: defaultTheme
