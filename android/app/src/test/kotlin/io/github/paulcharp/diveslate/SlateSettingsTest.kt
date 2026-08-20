package io.github.paulcharp.diveslate

import io.github.paulcharp.diveslate.core.ModernStyle
import io.github.paulcharp.diveslate.core.RetroStyle
import io.github.paulcharp.diveslate.core.SLATE_STYLES
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateUnits
import io.github.paulcharp.diveslate.core.TopoStyle
import io.github.paulcharp.diveslate.core.renderOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved look, and everything a stored string can be wrong about.
 *
 * These settings are the one thing in the app that outlives the process, which
 * makes them the one thing that can arrive holding a state the UI cannot
 * produce: a palette belonging to another style, four figures on a badge with
 * room for two, a style id from a build that has since been uninstalled. Each
 * of those would surface as a blank preview or an unreadable slate rather than
 * as an error, so they are settled on the way in — and this is what says so.
 */
class SlateSettingsTest {

    @Test
    fun `a round trip through text changes nothing`() {
        val settings = SlateSettings(
            style = TopoStyle,
            layout = SlateLayout.WATCH,
            theme = TopoStyle.themes.last(),
            units = SlateUnits.IMPERIAL,
            scrimAlpha = 0.87f,
            showBackdrop = false,
            showSite = false,
            showDate = true,
            showScrim = true,
            showCeiling = false,
            showGas = true,
            smooth = false,
            stats = setOf("depth", "gf"),
        ).normalised()

        assertEquals(settings, SlateSettings.decode(settings.encode()))
    }

    /** Every style and layout the app ships, not just the one in the fixture. */
    @Test
    fun `every style and layout survives a round trip`() {
        for (style in SLATE_STYLES) {
            for (theme in style.themes) {
                for (layout in SlateLayout.entries) {
                    val settings = SlateSettings(style = style, layout = layout, theme = theme)
                        .normalised()
                    assertEquals(
                        "${style.id}/${layout.id}/${theme.name} did not survive",
                        settings,
                        SlateSettings.decode(settings.encode()),
                    )
                }
            }
        }
    }

    // ---- what a stored string may not do -----------------------------------

    /**
     * A palette belongs to the style that paints it.
     *
     * `renderOverlay` refuses a foreign one rather than substituting, so a
     * stored pairing the user can no longer produce — they picked a palette,
     * then a style, in an older build — would blank the preview on every launch
     * with no way back except clearing the app's data.
     */
    @Test
    fun `a palette the style does not own is adopted, not kept`() {
        val foreign = SlateSettings(style = RetroStyle, theme = ModernStyle.defaultTheme)
        val settled = foreign.normalised()

        assertTrue(settled.theme in RetroStyle.themes)
        // And the result is renderable, which is the point of the rule.
        assertNotEquals(0, RetroStyle.themes.size)
        assertEquals(RetroStyle, settled.style)
    }

    /** The dark/light choice is about the footage, so it crosses the change. */
    @Test
    fun `adopting a palette keeps the mode`() {
        for (style in SLATE_STYLES) {
            for (other in SLATE_STYLES) {
                for (theme in style.themes) {
                    val settled = SlateSettings(style = other, theme = theme).normalised()
                    assertEquals(
                        "${theme.name} changed mode moving to ${other.id}",
                        theme.isDark,
                        settled.theme.isDark,
                    )
                }
            }
        }
    }

    @Test
    fun `the figure budget is enforced on the way in`() {
        val greedy = SlateSettings(
            layout = SlateLayout.WATCH,
            stats = setOf("depth", "time", "deco", "gf"),
        ).normalised()

        assertEquals(SlateLayout.WATCH.maxFigures, greedy.stats.size)
        // What survives is the front of the printed order, not whatever the set
        // happened to iterate first.
        assertEquals(
            listOf("depth", "time"),
            SlateSettings.STAT_ORDER.filter { it in greedy.stats },
        )
    }

    @Test
    fun `the opacity floor binds`() {
        val faint =
            SlateSettings(style = TopoStyle, theme = TopoStyle.defaultTheme, scrimAlpha = 0f)
        assertEquals(
            TopoStyle.defaultTheme.scrimAlphaMin,
            faint.normalised().scrimAlpha,
            1e-6f,
        )
    }

    /**
     * The style that cannot smooth draws no curve — and does not take the
     * preference with it.
     *
     * Two halves, and they pull in opposite directions. What is *drawn* must
     * not claim a smoothed profile the segment screen never draws; what is
     * *remembered* has to survive a look at that style, because smoothing is a
     * statement about how the slate should read and the styles after it can
     * still honour it. So the flag stays and the options mask it.
     */
    @Test
    fun `the segment screen refuses smoothing without forgetting the choice`() {
        assertFalse(RetroStyle.supportsSmooth)
        assertFalse(SlateSettings(style = RetroStyle, smooth = true).toOptions().smoothProfile)
        assertTrue(SlateSettings(style = RetroStyle, smooth = true).normalised().smooth)
        // And it is still on for the next style the user picks.
        assertTrue(
            SlateSettings(style = RetroStyle, smooth = true)
                .normalised()
                .copy(style = ModernStyle, theme = ModernStyle.defaultTheme)
                .toOptions()
                .smoothProfile
        )
    }

    // ---- decoding what another build wrote ---------------------------------

    @Test
    fun `an unknown style falls back to the shipped one`() {
        val decoded = SlateSettings.decode("style=no-such-style;layout=tall;theme=nonsense")
        assertEquals(SlateSettings.FACTORY.style, decoded.style)
        assertEquals(SlateLayout.TALL, decoded.layout)
        assertTrue(decoded.theme in decoded.style.themes)
    }

    /** A key this build has never heard of is dropped, not printed blank. */
    @Test
    fun `an unknown figure key is dropped`() {
        val decoded = SlateSettings.decode("stats=depth,teleport,time")
        assertEquals(setOf("depth", "time"), decoded.stats)
    }

    @Test
    fun `a truncated or empty string still yields something usable`() {
        for (text in listOf("", "   ", "style=", "=modern", ";;;", "units")) {
            val decoded = SlateSettings.decode(text)
            assertTrue(decoded.theme in decoded.style.themes)
            assertTrue(decoded.stats.size <= decoded.layout.maxFigures)
        }
    }

    /**
     * A newer build's fields are ignored, and the rest still loads.
     *
     * The alternative is discarding the whole string on one unrecognised key,
     * which would silently reset a user's saved look every time they moved
     * between builds.
     */
    @Test
    fun `fields from a future build are ignored rather than fatal`() {
        val decoded = SlateSettings.decode("units=imperial;hologram=true;layout=watch")
        assertEquals(SlateUnits.IMPERIAL, decoded.units)
        assertEquals(SlateLayout.WATCH, decoded.layout)
    }

    /**
     * "NaN" parses as a float, survives a clamp untouched, and paints the panel
     * at zero alpha — which reads as a broken slate rather than as a bad string.
     */
    @Test
    fun `a non-finite opacity falls back to the style's own`() {
        for (text in listOf("scrim=NaN", "scrim=Infinity", "scrim=-Infinity", "scrim=hello")) {
            val decoded = SlateSettings.decode(text)
            assertTrue(text, decoded.scrimAlpha.isFinite())
            assertTrue(text, decoded.scrimAlpha in decoded.theme.scrimAlphaMin..1f)
        }
    }

    @Test
    fun `unknown units fall back rather than throwing`() {
        assertEquals(SlateUnits.METRIC, SlateSettings.decode("units=furlongs").units)
        assertEquals(SlateUnits.IMPERIAL, SlateSettings.decode("units=imperial").units)
    }

    // ---- the options they describe -----------------------------------------

    @Test
    fun `an empty figure set means automatic`() {
        assertNull(SlateSettings(stats = emptySet()).toOptions().stats)
        assertEquals(
            listOf("depth", "temp"),
            SlateSettings(stats = setOf("temp", "depth")).toOptions().stats,
        )
    }

    /**
     * Anything the app can store, the renderer can draw.
     *
     * The end of the same argument the rules above make one at a time: a
     * settings object that survives decoding must not be one that throws when
     * it reaches the renderer, because by then there is no screen to say so on.
     */
    @Test
    fun `every decodable setting renders`() {
        val dive = TestDives.reference()
        for (style in SLATE_STYLES) {
            for (layout in SlateLayout.entries) {
                for (units in SlateUnits.entries) {
                    val stored = SlateSettings(
                        style = style,
                        layout = layout,
                        // Deliberately foreign, deliberately overspent.
                        theme = ModernStyle.themes.last(),
                        units = units,
                        stats = setOf("depth", "time", "deco", "gf", "temp"),
                    ).encode()
                    val slate = renderOverlay(dive, SlateSettings.decode(stored).toOptions())
                    assertTrue(
                        "${style.id}/${layout.id}/$units drew nothing",
                        slate.ops.isNotEmpty(),
                    )
                }
            }
        }
    }
}
