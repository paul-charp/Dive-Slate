package io.github.paulcharp.diveslate.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Printing the figures in feet.
 *
 * The parsers already accept `ft`, `psi` and `F` and normalise them away, so
 * nothing here is about reading a log — it is about the far end, where a diver
 * who thinks in feet reads a badge drawn from a log written in metres. The two
 * questions worth guarding are that the conversion rounds in the right place,
 * and that a longer number does not quietly break a layout that was sized
 * around a two-digit one.
 */
class SlateUnitsTest {

    private val dive: Dive by lazy {
        val source = File(Fixtures.repoRoot, "conformance/data/reference.ssrf")
        parseText(source.readText(Charsets.UTF_8), hint = source.name).only()
    }

    private fun figures(units: SlateUnits, stats: List<String>): List<SlateStat> =
        resolveStats(dive, OverlayOptions(units = units, stats = stats))

    // ---- rounding ----------------------------------------------------------

    /**
     * Converted first, rounded second.
     *
     * Rounding the metres up and converting afterwards would print 44.4 m as
     * 148 ft, which is nearly a metre deeper than the diver went — the badge
     * would be overstating the dive rather than merely rounding it.
     */
    @Test
    fun `feet are rounded after the conversion, not before`() {
        assertEquals(146, ceilFeet(44.4))
        // What rounding in the wrong order would have printed.
        assertEquals(148, kotlin.math.ceil(ceilMetres(44.4) * 3.280839895013123).toInt())
    }

    /** The ceiling convention survives the change of system. */
    @Test
    fun `depth always rounds up, in either system`() {
        assertEquals(45, ceilDepth(44.4, SlateUnits.METRIC))
        assertEquals(146, ceilDepth(44.4, SlateUnits.IMPERIAL))
        // A depth that is exactly round must not tip into the next unit.
        assertEquals(30, ceilDepth(30.0, SlateUnits.METRIC))
        assertEquals(100, ceilDepth(100 / 3.280839895013123, SlateUnits.IMPERIAL))
    }

    @Test
    fun `temperature converts and keeps its degree symbol`() {
        assertEquals("18" to "°C", temperatureFigure(18.0, SlateUnits.METRIC))
        assertEquals("64" to "°F", temperatureFigure(18.0, SlateUnits.IMPERIAL))
    }

    @Test
    fun `gas volumes and rates convert to cubic feet`() {
        assertEquals("2000" to "L", volumeFigure(2000.0, SlateUnits.METRIC))
        assertEquals("71" to "cf", volumeFigure(2000.0, SlateUnits.IMPERIAL))
        assertEquals("15.4" to "L/min", consumptionFigure(15.4, SlateUnits.METRIC))
        // Two decimals, because a real range of 0.4–0.8 rounds to one number at
        // one decimal for far too many dives.
        assertEquals("0.54" to "cf/min", consumptionFigure(15.4, SlateUnits.IMPERIAL))
    }

    // ---- what the slate says -----------------------------------------------

    @Test
    fun `an imperial slate labels every figure in imperial`() {
        // Four at a time: the layout's figure budget binds a hand-picked list
        // as firmly as an automatic one, and Wide has room for four.
        val depths = listOf("depth", "avg", "temp", "used")
        assertEquals(
            figures(SlateUnits.METRIC, depths).map { it.label },
            figures(SlateUnits.IMPERIAL, depths).map { it.label },
        )
        assertEquals(
            listOf("m", "m", "°C", "L"),
            figures(SlateUnits.METRIC, depths).map { it.unit },
        )
        assertEquals(
            listOf("ft", "ft", "°F", "cf"),
            figures(SlateUnits.IMPERIAL, depths).map { it.unit },
        )
        assertEquals("L/min", figures(SlateUnits.METRIC, listOf("sac")).single().unit)
        assertEquals("cf/min", figures(SlateUnits.IMPERIAL, listOf("sac")).single().unit)
    }

    /**
     * A unit-free figure is untouched.
     *
     * Runtime, deco, gradient factors and the mix names are the same figure in
     * either system, and a unit switch that reworded them would be changing
     * something it was not asked about.
     */
    @Test
    fun `figures that carry no unit are identical in both systems`() {
        val keys = listOf("time", "deco", "gf", "gas")
        assertEquals(figures(SlateUnits.METRIC, keys), figures(SlateUnits.IMPERIAL, keys))
    }

    /**
     * The units decide how a figure is printed, never whether it exists.
     *
     * [availableStats] answers in metric and the batch warning is built from
     * it, so a figure that appeared or vanished with the unit switch would make
     * that warning wrong for half the users.
     */
    @Test
    fun `availableStats does not depend on the units`() {
        for (key in STAT_KEYS) {
            val metric = resolveStats(dive, OverlayOptions(stats = listOf(key)))
            val imperial = resolveStats(
                dive,
                OverlayOptions(units = SlateUnits.IMPERIAL, stats = listOf(key)),
            )
            assertEquals(
                metric.isNotEmpty(),
                imperial.isNotEmpty(),
                "'$key' exists in one system and not the other",
            )
        }
        assertEquals(STAT_KEYS.filter { key ->
            resolveStats(dive, OverlayOptions(stats = listOf(key))).isNotEmpty()
        }.toSet(), availableStats(dive))
    }

    // ---- the drawing -------------------------------------------------------

    /**
     * Three digits where there were two, in every style and every layout.
     *
     * A depth in feet is one character longer than the same depth in metres,
     * and the figures share the width in equal columns — so this is exactly the
     * shape of change that fits on Wide and collides on Watch. The value is
     * allowed to shrink to fit its column; what it may not do is shrink to
     * something nobody can read on the badge that was sized around it.
     */
    @Test
    fun `imperial figures still fit every style and layout`() {
        for (style in SLATE_STYLES) {
            for (layout in SlateLayout.entries) {
                val options = OverlayOptions(
                    style = style,
                    layout = layout,
                    theme = style.defaultTheme,
                    units = SlateUnits.IMPERIAL,
                )
                val slate = renderOverlay(dive, options)
                assertTrue(
                    slate.ops.isNotEmpty(),
                    "${style.id} drew nothing in ${layout.id} in feet",
                )

                val metrics = layout.metrics(options.width)
                val largest = slate.ops.filterIsInstance<SlateOp.Text>()
                    .maxOf { it.sizePx }
                assertTrue(
                    largest >= metrics.valueSize * 0.6f,
                    "${style.id}/${layout.id} shrank its largest figure to $largest, " +
                        "against a nominal ${metrics.valueSize} — a three-digit depth " +
                        "should cost a little size, not most of it",
                )
            }
        }
    }

    /** The depth printed is the depth drawn, whichever system it is quoted in. */
    @Test
    fun `the printed depth still matches the profile`() {
        val deepest = dive.computedMaxDepthMetres
        val printed = figures(SlateUnits.IMPERIAL, listOf("depth")).single().value.toInt()
        assertTrue(
            printed >= depthInUnits(deepest, SlateUnits.IMPERIAL),
            "the badge says $printed ft for a dive that reached " +
                "${depthInUnits(deepest, SlateUnits.IMPERIAL)} ft",
        )
        assertTrue(printed - depthInUnits(deepest, SlateUnits.IMPERIAL) < 1.0)
    }

    // ---- the survey's grid -------------------------------------------------

    /**
     * The grid is drawn in the units it is labelled in.
     *
     * A metric ladder converted to feet would label the survey 33, 66, 98 —
     * numbers no diver has ever read a depth as. The gridlines are chosen in
     * whichever system the figures beside them are printed in, so the map and
     * the numbers never quote two different scales.
     */
    @Test
    fun `gridlines are round numbers in the system they are labelled in`() {
        val metric = depthGridlines(dive.computedMaxDepthMetres, SlateUnits.METRIC)
        val imperial = depthGridlines(dive.computedMaxDepthMetres, SlateUnits.IMPERIAL)

        assertTrue(metric.isNotEmpty() && imperial.isNotEmpty())
        assertNotEquals(metric, imperial)
        for (line in metric + imperial) {
            assertEquals(0.0, line % 5.0, "gridline at $line is not a number anyone reads")
        }
    }

    /** And every one of them is inside the dive, in either system. */
    @Test
    fun `no gridline is drawn past the deepest point`() {
        for (units in SlateUnits.entries) {
            val max = depthInUnits(dive.computedMaxDepthMetres, units)
            for (line in depthGridlines(dive.computedMaxDepthMetres, units)) {
                assertTrue(line < max, "a $units gridline at $line is below the dive's $max")
            }
        }
    }

    @Test
    fun `the survey prints its grid in the chosen units`() {
        fun legend(units: SlateUnits): List<String> = renderOverlay(
            dive,
            OverlayOptions(style = TopoStyle, theme = TopoStyle.defaultTheme, units = units),
        ).ops.filterIsInstance<SlateOp.Text>().map { it.text }

        assertTrue(legend(SlateUnits.METRIC).any { it.endsWith(" m") })
        assertTrue(legend(SlateUnits.IMPERIAL).any { it.endsWith(" ft") })
        assertTrue(legend(SlateUnits.IMPERIAL).none { it.endsWith(" m") })
    }
}
