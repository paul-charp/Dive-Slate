package io.github.paulcharp.diveslate.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three axes, and the rules that keep them independent.
 *
 * Style, layout and theme are only worth separating if they compose: a layout
 * that quietly only works with the style it was drawn for is a worse structure
 * than the pair of booleans it replaced. These tests are what makes that a
 * checked claim rather than an intention — the full cross-product is rendered,
 * so a style added later cannot ship supporting only one layout.
 */
class SlateStyleTest {

    private val dive: Dive by lazy {
        val source = File(Fixtures.repoRoot, "conformance/data/reference.ssrf")
        parseText(source.readText(Charsets.UTF_8), hint = source.name).only()
    }

    /**
     * A second style, so the seam is exercised by more than one implementation.
     *
     * It draws nothing of its own — the point is the contract around the
     * drawing, not the drawing. It offers a deliberately narrow palette list so
     * that adopting a theme across styles has something to fail to find.
     */
    private object DarkOnlyStyle : SlateStyle {
        override val id = "test-dark-only"
        override val label = "Dark only"
        override val description = "A test double offering dark palettes alone."
        override val themes = SLATE_THEMES.filter { it.isDark }
        override fun render(dive: Dive, options: OverlayOptions): Slate =
            ModernStyle.render(dive, options)
    }

    // ---- every layout works for every style --------------------------------

    @Test
    fun `every style renders in every layout`() {
        for (style in SLATE_STYLES) {
            for (layout in SlateLayout.entries) {
                val slate = renderOverlay(
                    dive,
                    OverlayOptions(style = style, layout = layout, theme = style.defaultTheme),
                )
                assertTrue(
                    slate.ops.isNotEmpty(),
                    "style ${style.id} drew nothing in the ${layout.id} layout",
                )
                assertTrue(
                    slate.height > 0f,
                    "style ${style.id} has no height in the ${layout.id} layout",
                )
                assertEquals(
                    layout.metrics(SlateLayout.REFERENCE_WIDTH).width,
                    slate.width,
                    "style ${style.id} ignored the layout's width in ${layout.id}",
                )
            }
        }
    }

    /**
     * Every style must render every palette it claims to offer.
     *
     * The list is the style saying which colours it has been designed for, so a
     * name on it that cannot actually be drawn is a broken promise rather than
     * an unused option.
     */
    @Test
    fun `every style renders every palette it offers`() {
        for (style in SLATE_STYLES) {
            assertTrue(style.themes.isNotEmpty(), "style ${style.id} offers no palettes")
            for (theme in style.themes) {
                val slate = renderOverlay(dive, OverlayOptions(style = style, theme = theme))
                assertTrue(
                    slate.ops.isNotEmpty(),
                    "style ${style.id} drew nothing with palette ${theme.name}",
                )
            }
        }
    }

    /**
     * Both palette pickers must have something in them.
     *
     * The dark/light split is not decoration: it is the user saying whether the
     * slate lands on footage or on a page, and each palette was validated
     * against its own assumed surface. A style offering only one mode silently
     * removes that choice.
     */
    @Test
    fun `every style offers palettes for dark footage and for pale backgrounds`() {
        for (style in SLATE_STYLES) {
            assertTrue(
                style.themes.any { it.isDark },
                "style ${style.id} has no palette for dark footage",
            )
            assertTrue(
                style.themes.any { !it.isDark },
                "style ${style.id} has no palette for pale backgrounds",
            )
        }
    }

    @Test
    fun `style and layout ids are unique`() {
        val styleIds = SLATE_STYLES.map { it.id }
        assertEquals(styleIds.distinct(), styleIds, "two styles share an id")
        val layoutIds = SlateLayout.entries.map { it.id }
        assertEquals(layoutIds.distinct(), layoutIds, "two layouts share an id")
    }

    @Test
    fun `a style can be looked up by the id it is persisted under`() {
        for (style in SLATE_STYLES) {
            assertSame(style, styleById(style.id), "styleById lost ${style.id}")
        }
        assertEquals(null, styleById("no-such-style"))
    }

    // ---- a style carries its themes ----------------------------------------

    /**
     * A palette borrowed from another style has cleared the gates for marks
     * nobody is drawing, so it is refused rather than substituted. Silently
     * swapping it would hide exactly the mismatch this rule exists to catch.
     */
    @Test
    fun `a style is refused a palette it does not offer`() {
        val foreign = SLATE_THEMES.first { !it.isDark }
        assertTrue(foreign !in DarkOnlyStyle.themes, "the fixture no longer sets up the case")
        try {
            renderOverlay(dive, OverlayOptions(style = DarkOnlyStyle, theme = foreign))
            fail("expected a palette outside the style to be refused")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                foreign.name in (error.message ?: ""),
                "the refusal should name the palette, said: ${error.message}",
            )
        }
    }

    /** Switching style keeps the same palette when the new style has it. */
    @Test
    fun `adopting a palette keeps it when the style offers it`() {
        val shared = SLATE_THEMES.first { it.isDark }
        assertSame(shared, DarkOnlyStyle.adopt(shared))
    }

    /**
     * Failing that, it keeps the dark/light intent.
     *
     * That choice is a statement about the footage the slate will land on, which
     * the incoming style knows nothing about — moving a light palette to a dark
     * one because the names did not match would silently undo it.
     */
    @Test
    fun `adopting a palette falls back within the same mode`() {
        val light = SLATE_THEMES.first { !it.isDark }
        val adopted = DarkOnlyStyle.adopt(light)
        assertTrue(adopted in DarkOnlyStyle.themes, "adopted a palette the style does not offer")
        assertTrue(
            adopted.isDark,
            "a style with no light palette should still not hand back one it lacks",
        )
    }

    @Test
    fun `every style can adopt every palette of every other style`() {
        for (style in SLATE_STYLES) {
            for (other in SLATE_STYLES) {
                for (theme in other.themes) {
                    assertTrue(
                        style.adopt(theme) in style.themes,
                        "${style.id} adopted ${theme.name} into a palette it does not offer",
                    )
                }
            }
        }
    }

    // ---- layout is proportions, and nothing else ---------------------------

    /** At the reference width a layout is its own quoted numbers, unscaled. */
    @Test
    fun `layout metrics are unscaled at the reference width`() {
        val m = SlateLayout.WIDE.metrics(SlateLayout.REFERENCE_WIDTH)
        assertEquals(1f, m.scale)
        assertEquals(44f, m.pad)
        assertEquals(210f, m.curveHeight)
    }

    /**
     * Everything scales off the width, so the design holds at any size. A style
     * sizing its own details through [LayoutMetrics.px] scales with it.
     */
    @Test
    fun `layout metrics scale linearly with the slate width`() {
        for (layout in SlateLayout.entries) {
            val full = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            val half = layout.metrics(SlateLayout.REFERENCE_WIDTH / 2f)
            assertEquals(0.5f, half.scale, "${layout.id} did not halve its scale")
            assertTrue(
                abs(half.pad * 2f - full.pad) < 1e-3f,
                "${layout.id} padding does not scale: ${half.pad} vs ${full.pad}",
            )
            assertTrue(
                abs(half.px(9f) * 2f - full.px(9f)) < 1e-3f,
                "${layout.id} px() does not scale with the slate",
            )
        }
    }

    private fun labels(layout: SlateLayout, stats: List<String>? = null) =
        renderOverlay(dive, OverlayOptions(layout = layout, stats = stats))
            .ops.filterIsInstance<SlateOp.Text>().map { it.text }

    /**
     * Layout changes proportions, and beyond its figure budget, nothing else.
     *
     * Layouts sharing a budget are the same slate at different shapes. If one
     * of them dropped a figure or a label *to fit*, the control would be doing
     * two jobs and the user would have no way to ask for only one of them —
     * which is why the budget is a stated number the picker enforces rather
     * than whatever happens to overflow at render time.
     */
    @Test
    fun `layouts with the same figure budget say the same thing`() {
        val byBudget = SlateLayout.entries.groupBy { it.maxFigures }
        for ((budget, layouts) in byBudget) {
            val reference = labels(layouts.first())
            for (layout in layouts) {
                assertEquals(
                    reference,
                    labels(layout),
                    "the ${layout.id} layout says something else at a budget of $budget",
                )
            }
        }
    }

    /**
     * Spending the budget is the only thing a narrower layout may do to the
     * content — everything it does show, it shows in full.
     */
    @Test
    fun `a narrower layout drops figures from the end and nothing else`() {
        val wide = labels(SlateLayout.WIDE)
        val compact = labels(SlateLayout.COMPACT)
        assertTrue(
            compact.size < wide.size,
            "the compact budget is no longer doing anything, so this proves nothing",
        )
        assertEquals(
            compact,
            wide.take(compact.size),
            "compact did not simply stop early; it changed what it says",
        )
    }

    /**
     * The budget binds a hand-picked list too.
     *
     * Otherwise the cap is only a default, and the one case it exists for — a
     * user who picks four figures for a badge with room for two — is exactly
     * the case that escapes it.
     */
    @Test
    fun `the figure budget binds a hand-picked list as well as an automatic one`() {
        for (layout in SlateLayout.entries) {
            val asked = listOf("depth", "time", "deco", "temp", "gf")
            assertTrue(asked.size > layout.maxFigures, "the fixture no longer overruns ${layout.id}")

            // Figure labels are the only mark the style tracks out at 0.10em,
            // which counts them without depending on their wording.
            val shown = renderOverlay(dive, OverlayOptions(layout = layout, stats = asked))
                .ops.filterIsInstance<SlateOp.Text>()
                .filter { it.letterSpacingEm == 0.10f }
                .map { it.text }

            assertEquals(
                layout.maxFigures,
                shown.size,
                "${layout.id} printed $shown against a budget of ${layout.maxFigures}",
            )
        }
    }

    /**
     * Stacked layouts give each figure a row; columned ones give each a column.
     *
     * This is the whole mechanism behind a watch-sized badge carrying numerals
     * larger than the layout that spans the frame: one column instead of two
     * roughly doubles the width a numeral has. A style that ignored the metric
     * and always laid figures out in a row would still draw — and the numbers
     * would quietly shrink back to a share of the width.
     */
    @Test
    fun `figures are stacked or columned as the layout asks`() {
        for (layout in SlateLayout.entries) {
            val m = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            val labels = renderOverlay(dive, OverlayOptions(layout = layout))
                .ops.filterIsInstance<SlateOp.Text>()
                .filter { it.letterSpacingEm == 0.10f }
            assertTrue(labels.size >= 2, "${layout.id} drew fewer than two figures")

            if (m.figuresStacked) {
                assertEquals(
                    1, labels.map { it.x }.distinct().size,
                    "${layout.id} stacks its figures, so they share a left edge",
                )
                assertEquals(
                    labels.size, labels.map { it.baselineY }.distinct().size,
                    "${layout.id} stacks its figures, so each needs its own line",
                )
            } else {
                assertEquals(
                    labels.size, labels.map { it.x }.distinct().size,
                    "${layout.id} columns its figures, so each needs its own left edge",
                )
                assertEquals(
                    1, labels.map { it.baselineY }.distinct().size,
                    "${layout.id} columns its figures, so they share a baseline",
                )
            }
        }
    }

    /**
     * Stacking is only worth its complexity if the numerals actually grow.
     *
     * The watch badge is a third of the wide layout's width, and its figures
     * are still set larger. If that ever stops being true, the stacked
     * arrangement is costing a branch in the renderer and buying nothing.
     */
    @Test
    fun `the watch badge sets bigger numerals than the layout spanning the frame`() {
        val watch = SlateLayout.WATCH.metrics(SlateLayout.REFERENCE_WIDTH)
        val wide = SlateLayout.WIDE.metrics(SlateLayout.REFERENCE_WIDTH)

        assertTrue(
            watch.width < wide.width / 2f,
            "the watch badge is no longer small: ${watch.width}px against ${wide.width}px",
        )
        assertTrue(
            watch.valueSize > wide.valueSize,
            "stacking bought nothing: ${watch.valueSize}px against ${wide.valueSize}px",
        )
    }

    /**
     * And the styles have to honour it, not just the layout.
     *
     * The test above asks [SlateLayout] what it quotes. That is not the same
     * question as what a style actually sets, and the difference is where this
     * went wrong once: a style scaled its figures down to pay for the padding
     * its own containers added, which took the watch badge to 51px numerals —
     * under the 56px of the layout spanning the whole frame, and under Compact's
     * 80px on a *larger* badge. Every metric-level test passed, because no
     * metric had moved. The smallest slate was setting the smallest numbers and
     * only a screenshot could see it. The style is gone and so is the lever it
     * used, but the gap in the guarding was the real defect, and this closes it
     * for whatever is added next.
     *
     * Largest text as the stand-in for "the numerals": on every style the figure
     * value is the biggest thing on the slate, and the assertion below says so
     * out loud, so a style that ever sets a heading larger fails here rather
     * than quietly changing what this measures.
     */
    @Test
    fun `every style sets bigger numerals on the watch badge than on the wide one`() {
        for (style in SLATE_STYLES) {
            val sizes = mapOf(
                SlateLayout.WATCH to largestText(style, SlateLayout.WATCH),
                SlateLayout.WIDE to largestText(style, SlateLayout.WIDE),
            )
            assertTrue(
                sizes.getValue(SlateLayout.WATCH) > sizes.getValue(SlateLayout.WIDE),
                "${style.id} sets ${sizes.getValue(SlateLayout.WATCH)}px on the watch badge " +
                    "and ${sizes.getValue(SlateLayout.WIDE)}px on the layout spanning the " +
                    "frame — stacking is meant to buy bigger numerals, not smaller ones",
            )
        }
    }

    /**
     * The largest text a style emits in a layout, which is its figure value.
     *
     * Asserted rather than assumed: if some style ever sets a heading or an
     * ornament larger than its numbers, the caller above is silently measuring
     * the wrong thing, and this is where that shows up.
     */
    private fun largestText(style: SlateStyle, layout: SlateLayout): Float {
        val slate = renderOverlay(
            dive,
            OverlayOptions(style = style, layout = layout, theme = style.defaultTheme),
        )
        val texts = slate.ops.filterIsInstance<SlateOp.Text>()
        assertTrue(texts.isNotEmpty(), "${style.id} drew no text in the ${layout.id} layout")
        val biggest = texts.maxBy { it.sizePx }
        assertTrue(
            biggest.text.any { it.isDigit() },
            "${style.id}'s largest text in the ${layout.id} layout is '${biggest.text}', " +
                "not a figure — this test is measuring the wrong mark",
        )
        return biggest.sizePx
    }

    /** A budget of nothing would render a slate with no numbers on it. */
    @Test
    fun `every layout has room for at least the two headline figures`() {
        for (layout in SlateLayout.entries) {
            assertTrue(
                layout.maxFigures >= 2,
                "${layout.id} cannot show both depth and runtime, which every diver reads first",
            )
        }
    }

    /**
     * A layout may claim less than the canvas, and never more.
     *
     * The width is a proportion like any other — a corner badge is narrower
     * than the frame it sits in — but the canvas is still the bound. A layout
     * wider than what it was given would be cropped by whatever painted it,
     * silently, at the right-hand edge.
     */
    @Test
    fun `a layout may be narrower than the canvas but never wider`() {
        for (layout in SlateLayout.entries) {
            val m = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            assertTrue(m.width > 0f, "${layout.id} has no width")
            assertTrue(
                m.width <= SlateLayout.REFERENCE_WIDTH,
                "${layout.id} is ${m.width}px on a ${SlateLayout.REFERENCE_WIDTH}px canvas",
            )
            assertTrue(
                m.width - m.pad * 2 > 0f,
                "${layout.id} is narrower than its own padding",
            )
        }
    }

    /** The slate's own width scales with the canvas, like every other measure. */
    @Test
    fun `a layout keeps its share of the canvas at any size`() {
        for (layout in SlateLayout.entries) {
            val full = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            val half = layout.metrics(SlateLayout.REFERENCE_WIDTH / 2f)
            assertTrue(
                abs(half.width * 2f - full.width) < 1e-3f,
                "${layout.id} width does not scale: ${half.width} vs ${full.width}",
            )
        }
    }

    /**
     * The figures land where the layout says, not where the style prefers.
     *
     * Reading order is the layout's call — a badge that leads with its numbers
     * and one that leads with its profile are the same marks in a different
     * order — so a style that hard-coded the sequence would quietly ignore the
     * control, and the slate would still draw.
     */
    @Test
    fun `the figures are placed on the side of the profile the layout asks for`() {
        for (layout in SlateLayout.entries) {
            val slate = renderOverlay(dive, OverlayOptions(layout = layout))
            // The surface line is the only Line the slate draws, and it is
            // where the profile starts.
            val surface = slate.ops.filterIsInstance<SlateOp.Line>()
                .firstOrNull() ?: fail("${layout.id}: no surface line")
            val figure = slate.ops.filterIsInstance<SlateOp.Text>()
                .firstOrNull { it.text == "MAX DEPTH" } ?: fail("${layout.id}: no depth figure")

            if (layout.metrics(SlateLayout.REFERENCE_WIDTH).figuresLead) {
                assertTrue(
                    figure.baselineY < surface.start.y,
                    "${layout.id} leads with its figures but drew them below the profile",
                )
            } else {
                assertTrue(
                    figure.baselineY > surface.start.y,
                    "${layout.id} leads with its profile but drew the figures above it",
                )
            }
        }
    }

    // ---- what a style may not trade away -----------------------------------

    /**
     * A palette that does not separate its marks by hue must separate them by
     * shape.
     *
     * This is the other half of a decision made in `tools/palette.py`. The
     * monochrome profile waives the CVD and normal-vision floors — a screen
     * drawn in one ink cannot pass them and it is not trying to — and in
     * exchange the claim moves here, because "these two marks differ in dash
     * and hatching" is not something a colour gate can measure. Waiving the
     * gate without checking the substitute would be loosening the rule and
     * calling it a profile.
     */
    @Test
    fun `a one-ink palette separates the ceiling from the profile by form`() {
        for (style in SLATE_STYLES) {
            for (theme in style.themes.filter { it.separatesByForm }) {
                val slate = renderOverlay(
                    dive,
                    OverlayOptions(style = style, theme = theme, showCeiling = true),
                )
                val paths = slate.ops.filterIsInstance<SlateOp.Path>()
                assertTrue(
                    paths.any { it.fill is SlateFill.Hatch },
                    "${style.id}/${theme.name} drew no hatched ceiling region, so the " +
                        "hazard rests on a colour this palette does not have",
                )
                assertTrue(
                    paths.any { it.dash != null && it.strokeArgb != null },
                    "${style.id}/${theme.name} drew the ceiling edge solid, so it is " +
                        "the profile line in a different place",
                )
            }
        }
    }

    /**
     * Every style hatches the ceiling, whatever else it does with it.
     *
     * The hatch is what says *region you may not enter*. Styles are free to
     * re-colour the ceiling — several do, and the substitutes were measured
     * against the card they land on — but that freedom was granted because the
     * hatch and the step carry the meaning without help from hue. A style that
     * kept the colour and dropped the hatch would have taken the concession and
     * thrown away the reason for it.
     */
    @Test
    fun `every style hatches the deco ceiling`() {
        for (style in SLATE_STYLES) {
            val slate = renderOverlay(
                dive,
                OverlayOptions(style = style, theme = style.defaultTheme, showCeiling = true),
            )
            assertTrue(
                slate.ops.filterIsInstance<SlateOp.Path>().any { it.fill is SlateFill.Hatch },
                "${style.id} drew the ceiling without a hatch",
            )
        }
    }

    /**
     * Every style prints the mix beside a gas switch.
     *
     * The accent sits below 3:1 against the surface in several palettes, and
     * the gates permit that *only* because a text label carries the identity.
     * Drop the label to reduce clutter and the mark becomes one that colour
     * alone has to distinguish, which under colour-vision deficiency it cannot.
     */
    @Test
    fun `every style names the mix at a gas switch`() {
        val switched = dive.copy(
            gasSwitches = listOf(
                GasSwitch(
                    timeSeconds = dive.samples[dive.samples.size / 2].timeSeconds,
                    gas = GasMix(o2 = 0.5),
                )
            )
        )
        for (style in SLATE_STYLES) {
            val slate = renderOverlay(
                switched,
                OverlayOptions(style = style, theme = style.defaultTheme, showGas = true),
            )
            assertTrue(
                slate.ops.filterIsInstance<SlateOp.Text>().any { it.text == "EAN50" },
                "${style.id} drew a gas switch with no mix name beside it",
            )
        }
    }

    /**
     * Turning the panel off turns the panel off.
     *
     * A style that paints its own card has to put it behind the same switch as
     * the plain scrim, or the control silently means something different
     * depending on which style is selected — and the one thing a user reaches
     * for it to do, drop the slate straight onto the footage, would not happen.
     */
    @Test
    fun `no style paints a panel when the scrim is off`() {
        for (style in SLATE_STYLES) {
            val slate = renderOverlay(
                dive,
                OverlayOptions(style = style, theme = style.defaultTheme, showScrim = false),
            )
            val panel = slate.ops.filterIsInstance<SlateOp.Rect>().firstOrNull {
                it.fill != null &&
                    it.width >= slate.width * 0.9f &&
                    it.height >= slate.height * 0.9f
            }
            assertEquals(
                null,
                panel,
                "${style.id} painted a full-slate panel with the scrim switched off",
            )
        }
    }

    /**
     * Ornament stays inside the image.
     *
     * The slate's bounds are the exported PNG, so a decoration placed past them
     * is not soft-edged, it is cropped — and it is cropped only on the layout
     * that happened to be narrow, which is exactly the kind of thing a test
     * across the cross-product catches and a glance at one preview does not.
     */
    @Test
    fun `every style keeps its panels and ornament inside the slate`() {
        for (style in SLATE_STYLES) {
            for (layout in SlateLayout.entries) {
                val slate = renderOverlay(
                    dive,
                    OverlayOptions(
                        style = style, layout = layout, theme = style.defaultTheme,
                    ),
                )
                for (rect in slate.ops.filterIsInstance<SlateOp.Rect>()) {
                    assertTrue(
                        rect.x >= -0.5f && rect.y >= -0.5f &&
                            rect.x + rect.width <= slate.width + 0.5f &&
                            rect.y + rect.height <= slate.height + 0.5f,
                        "${style.id} in ${layout.id} drew a rectangle at " +
                            "(${rect.x}, ${rect.y}) ${rect.width}x${rect.height}, " +
                            "outside a ${slate.width}x${slate.height} slate",
                    )
                }
            }
        }
    }

    /**
     * Reducing the series before curving it must not lose the extreme.
     *
     * A smoothed profile is drawn as a curve, and a spline through points a
     * pixel apart is a polyline with extra arithmetic — so the series is
     * coarsened first. That is the moment where a smoothing pass would
     * quietly become a *data* pass: drop to one sample per bucket and the
     * deepest point of the dive can fall in a bucket where something shallower
     * won, leaving the drawn profile shallower than the figure printed beside
     * it. Both extremes survive instead, which is the same rule `envelope`
     * follows a pixel at a time.
     */
    @Test
    fun `coarsening the profile keeps its deepest point`() {
        val points = (0..400).map { step ->
            // A gentle profile with one narrow spike, which is exactly the shape
            // a per-bucket average or a first-sample rule would flatten.
            val depth = if (step == 173) 240f else 60f + (step % 17)
            Pt(step.toFloat(), depth)
        }
        // At the step the styles actually use, not a token one: the whole
        // reason SMOOTH_STEP_PX is free to be tuned for looks is that this
        // property holds at any coarseness, so the test should be exercising
        // the value someone would reach for when tuning it.
        val reduced = coarsened(points, SMOOTH_STEP_PX)

        assertTrue(reduced.size < points.size, "coarsening reduced nothing")
        assertEquals(
            points.maxOf { it.y },
            reduced.maxOf { it.y },
            "the deepest sample did not survive coarsening",
        )
        assertEquals(
            points.minOf { it.y },
            reduced.minOf { it.y },
            "the shallowest sample did not survive coarsening",
        )
        // Still left to right: a curve drawn through points out of order would
        // double back on itself.
        assertTrue(
            reduced.zipWithNext().all { (a, b) -> a.x <= b.x },
            "coarsening returned points out of order",
        )
    }
}
