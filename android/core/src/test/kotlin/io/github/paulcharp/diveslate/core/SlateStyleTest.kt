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
                    SlateLayout.REFERENCE_WIDTH,
                    slate.width,
                    "style ${style.id} ignored the requested width in ${layout.id}",
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

    /**
     * Layout changes proportions without changing content.
     *
     * Wide and tall are the same slate at different shapes — if one of them
     * dropped a figure or a label to fit, the control would be doing two jobs
     * and the user would have no way to ask for only one of them.
     */
    @Test
    fun `layout changes the shape but not what is said`() {
        fun labels(layout: SlateLayout) =
            renderOverlay(dive, OverlayOptions(layout = layout))
                .ops.filterIsInstance<SlateOp.Text>().map { it.text }

        assertEquals(labels(SlateLayout.WIDE), labels(SlateLayout.TALL))
    }
}
