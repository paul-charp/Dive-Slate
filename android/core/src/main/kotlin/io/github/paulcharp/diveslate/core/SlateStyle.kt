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
 * renderer — which is what makes adding another one cheap and what keeps every
 * style getting the same treatment from it.
 *
 * Two of those constants are not sizes but placements, and they are here rather
 * than in the style for the same reason as the rest: they are the answer to
 * *where things go*, which is the layout's question.
 *
 * * [naturalWidth] — how wide the badge itself is. It need not be the whole
 *   canvas. A slate meant for the corner of a shot is narrower than the frame
 *   it sits in, and since the type sizes stay quoted against the reference
 *   width, narrowing a layout tightens the badge rather than shrinking
 *   everything printed on it.
 * * [figuresLead] — whether the figures are read before the profile or after
 *   it. Which is emphasised is a proportion decision, not an art-direction one:
 *   the marks are identical either way.
 * * [figuresStacked] — whether the figures share a row or a column. Stacking is
 *   what buys size on a small badge: one column instead of two roughly doubles
 *   the width each numeral has, which is the whole reason a watch-sized slate
 *   can carry numbers worth reading at all.
 *
 * [maxFigures] is the one place a layout is allowed to bear on *what* the slate
 * says rather than only on how it is arranged, and it is deliberately narrow.
 * The figures share the width in equal columns, so a narrow badge asked for
 * three of them types each one smaller than the badge can carry — the constraint
 * is real, and the only question is who resolves it. A budget resolves it in the
 * picker, where the user can see the number and choose which figures spend it.
 * The alternative is resolving it at render time, silently, which is the thing
 * worth refusing: a slate quietly missing a figure the user asked for looks
 * exactly like a log that never recorded one.
 */
enum class SlateLayout(
    val id: String,
    val label: String,
    /** How many summary figures fit across this layout at a readable size. */
    val maxFigures: Int,
    private val naturalWidth: Float,
    private val figuresLead: Boolean,
    private val figuresStacked: Boolean,
    private val pad: Float,
    private val gap: Float,
    private val siteSize: Float,
    private val dateSize: Float,
    private val valueSize: Float,
    private val labelSize: Float,
    private val curveHeight: Float,
) {
    /** A landscape strip, the full width of a 16:9 frame. */
    WIDE(
        id = "wide", label = "Wide", maxFigures = 4,
        naturalWidth = 1080f, figuresLead = false, figuresStacked = false,
        pad = 44f, gap = 26f,
        siteSize = 34f, dateSize = 22f, valueSize = 56f, labelSize = 18f,
        curveHeight = 210f,
    ),

    /** Portrait, for a 9:16 story — the profile gets roughly twice the height. */
    TALL(
        id = "tall", label = "Tall", maxFigures = 4,
        naturalWidth = 1080f, figuresLead = false, figuresStacked = false,
        pad = 56f, gap = 34f,
        siteSize = 46f, dateSize = 28f, valueSize = 86f, labelSize = 24f,
        curveHeight = 430f,
    ),

    /**
     * A corner badge: figures first, side by side, the profile cut to a strip.
     *
     * Wide and Tall span the shot, so the profile can carry it and the numbers
     * caption it. This one is meant for a corner of footage that is doing its
     * own talking, where a curve at badge scale is a texture rather than
     * something anyone reads — so depth and runtime lead, and the profile stays
     * as the shape that says which dive it was.
     *
     * Two figures, not three. A third column here would be 130px wide, which is
     * not enough for `Air, O2` or `15.4 L/min` at the size the rest of the badge
     * is set in — and a corner badge that shrinks its numbers to fit them has
     * stopped being worth reading at corner size.
     */
    COMPACT(
        id = "compact", label = "Compact", maxFigures = 2,
        naturalWidth = 460f, figuresLead = true, figuresStacked = false,
        pad = 32f, gap = 22f,
        siteSize = 28f, dateSize = 18f, valueSize = 80f, labelSize = 16f,
        curveHeight = 120f,
    ),

    /**
     * The smallest of them: roughly square, the figures stacked, the profile a
     * sparkline under them.
     *
     * Stacking is the whole point. Given the full width instead of half of it,
     * a numeral is 88px on a 400px badge — larger in absolute terms than the
     * ones on the layout that spans the entire frame, on a badge with a third of
     * its area. What pays for it is the profile, which at 60px is a silhouette
     * rather than something depths are read off. That trade is only right at
     * this size, which is why it is a separate layout and not a tweak to
     * [COMPACT].
     */
    WATCH(
        id = "watch", label = "Watch", maxFigures = 2,
        naturalWidth = 400f, figuresLead = true, figuresStacked = true,
        pad = 30f, gap = 20f,
        siteSize = 26f, dateSize = 17f, valueSize = 88f, labelSize = 16f,
        curveHeight = 60f,
    );

    /**
     * This layout's proportions realised on a canvas [canvasWidth] pixels wide.
     *
     * The canvas is what the slate is *allowed*; [LayoutMetrics.width] is what
     * it takes, which for a corner badge is less.
     */
    fun metrics(canvasWidth: Float): LayoutMetrics {
        val scale = canvasWidth / REFERENCE_WIDTH
        return LayoutMetrics(
            width = naturalWidth * scale,
            scale = scale,
            figuresLead = figuresLead,
            figuresStacked = figuresStacked,
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
    /** The slate's own width, which may be narrower than the canvas. */
    val width: Float,
    /** Multiplier from the reference width; 1.0 at 1080px. */
    val scale: Float,
    /** Whether the summary figures are placed above the profile or below it. */
    val figuresLead: Boolean,
    /** Whether the figures share one row, or each get a row of their own. */
    val figuresStacked: Boolean,
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

    /**
     * Where the panel-opacity control starts.
     *
     * A starting point, not a limit. [SlateTheme.scrimAlphaMin] is still the
     * floor and stays the palette's to set, since it is measured — it is where
     * that ink stops clearing 4.5:1 against the worst backdrop. This is the
     * style's judgement about how much panel its own marks want before anyone
     * touches the slider, which is a different question and belongs to whoever
     * draws them.
     */
    val defaultScrimAlpha: Float get() = defaultTheme.scrimAlphaNominal

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
