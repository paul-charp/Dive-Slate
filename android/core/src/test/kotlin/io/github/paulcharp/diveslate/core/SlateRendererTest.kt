package io.github.paulcharp.diveslate.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Properties of the rendered slate.
 *
 * There is no Python fixture for the drawing operations — the SVG writer emits
 * a document, not a display list, and pinning this port to that markup would be
 * pinning it to the wrong thing. So these assert the properties the layout has
 * to hold, several of which encode decisions CLAUDE.md records as expensive.
 */
class SlateRendererTest {

    private val dive: Dive by lazy {
        val source = File(Fixtures.repoRoot, "tests/data/reference.ssrf")
        parseText(source.readText(Charsets.UTF_8), hint = source.name).only()
    }

    private fun Slate.texts() = ops.filterIsInstance<SlateOp.Text>()

    private fun Slate.scrim(): SlateOp.Rect? = ops.filterIsInstance<SlateOp.Rect>().firstOrNull()

    private fun alphaOf(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

    @Test
    fun `the reference dive renders`() {
        val slate = renderOverlay(dive)
        assertEquals(1080f, slate.width)
        assertTrue(slate.height > 0f, "slate has no height")
        assertTrue(slate.ops.isNotEmpty(), "slate drew nothing")
        assertTrue(slate.texts().isNotEmpty(), "slate has no labels")
    }

    @Test
    fun `a dive with no samples is refused rather than drawn empty`() {
        try {
            renderOverlay(Dive())
            fail("expected a dive with no profile to be refused")
        } catch (_: IllegalArgumentException) {
            // As intended.
        }
    }

    /**
     * The scrim must cover everything it is there to protect. It is painted
     * first and sized to the whole slate, so nothing can land outside it.
     */
    @Test
    fun `the scrim covers the whole slate`() {
        val slate = renderOverlay(dive)
        val scrim = slate.scrim() ?: fail("no scrim painted")
        assertEquals(0f, scrim.x)
        assertEquals(0f, scrim.y)
        assertEquals(slate.width, scrim.width)
        assertEquals(slate.height, scrim.height)
        assertEquals(slate, slate.copy(ops = slate.ops), "sanity")
        assertTrue(slate.ops.first() is SlateOp.Rect, "scrim must be painted first, under everything")
    }

    /**
     * The opacity control moves the panel and nothing else.
     *
     * Fading the ink would void the contrast the palette gates enforce and turn
     * the deliberately-unthemed hazard red into a pink suggestion. This is the
     * invariant that keeps the slider from becoming a way to defeat the palette.
     */
    @Test
    fun `the opacity control never fades the ink`() {
        val faint = renderOverlay(dive, OverlayOptions(scrimAlpha = 0f))
        val solid = renderOverlay(dive, OverlayOptions(scrimAlpha = 1f))

        assertEquals(
            solid.texts().map { it.fillArgb },
            faint.texts().map { it.fillArgb },
            "label colours changed with the scrim slider",
        )
        for (text in faint.texts()) {
            assertEquals(255, alphaOf(text.fillArgb), "ink was painted translucent: ${text.text}")
        }
    }

    /**
     * The slider cannot reach an opacity where the panel stops working. The
     * floor is computed per theme from ink contrast against the worst possible
     * backdrop, not chosen.
     */
    @Test
    fun `scrim opacity is clamped to the theme floor`() {
        for (theme in SLATE_THEMES) {
            val slate = renderOverlay(dive, OverlayOptions(theme = theme, scrimAlpha = 0f))
            val scrim = slate.scrim() ?: fail("${theme.name}: no scrim")
            val fill = scrim.fill as? SlateFill.Solid ?: fail("${theme.name}: scrim is not solid")
            val alpha = alphaOf(fill.argb) / 255f

            assertTrue(
                alpha >= theme.scrimAlphaMin - 0.01f,
                "${theme.name}: slider reached $alpha, below the floor ${theme.scrimAlphaMin}",
            )
        }
    }

    @Test
    fun `the hazard red is the same in every theme`() {
        val reds = SLATE_THEMES.map { it.ceiling }.distinct()
        assertEquals(
            listOf(CEILING_ARGB),
            reds,
            "the ceiling colour must not shift with the palette",
        )
    }

    /** The tall layout exists to fill a 9:16 story, so it must actually be taller. */
    @Test
    fun `the tall layout gives the profile more height`() {
        val wide = renderOverlay(dive, OverlayOptions(layout = SlateLayout.WIDE))
        val tall = renderOverlay(dive, OverlayOptions(layout = SlateLayout.TALL))
        assertTrue(tall.height > wide.height, "tall (${tall.height}) is not taller than wide (${wide.height})")
    }

    /**
     * Decimation keeps the extremes of each pixel column.
     *
     * Every-Nth sampling would drop the deepest point of a column and clip the
     * spikes that give a profile its character, so the reduced series must still
     * reach the same depth.
     */
    @Test
    fun `envelope reduction preserves the depth extremes`() {
        // Squeeze the series into 200 columns so roughly ten samples land on
        // each: at two per column the algorithm rightly keeps both and there is
        // nothing to test.
        val width = 200f
        val duration = dive.computedDurationSeconds
        val points = dive.samples.map {
            Pt((it.timeSeconds / duration).toFloat() * width, it.depthMetres.toFloat())
        }
        val reduced = envelope(points, targetWidth = width)

        assertTrue(reduced.size < points.size, "nothing was reduced")
        assertTrue(
            reduced.size <= (width * 2).toInt() + 2,
            "reduction should approach two points per column, got ${reduced.size}",
        )
        assertEquals(
            points.maxOf { it.y },
            reduced.maxOf { it.y },
            "the deepest point was decimated away",
        )
        assertEquals(points.minOf { it.y }, reduced.minOf { it.y }, "the shallowest point was lost")
        assertTrue(
            reduced.zipWithNext().all { (a, b) -> a.x <= b.x },
            "reduced points must still read left to right",
        )
    }

    @Test
    fun `stats fall back gracefully when the log cannot supply them`() {
        // A bare dive: no deco, no gradient factors, no cylinders, no site.
        val bare = Dive(samples = listOf(Sample(0.0, 0.0), Sample(600.0, 18.0)))
        val slate = renderOverlay(bare)
        val labels = slate.texts().map { it.text }

        assertTrue(labels.contains("MAX DEPTH"), "depth should always be available")
        assertTrue(labels.contains("RUNTIME"), "runtime should always be available")
        assertTrue(labels.none { it == "DECO" }, "a no-stop dive must not show a deco figure")
        assertTrue(labels.none { it == "GF" }, "a dive with no model string must not show GFs")
    }

    @Test
    fun `an unknown stat key is refused`() {
        try {
            renderOverlay(dive, OverlayOptions(stats = listOf("nonsense")))
            fail("expected an unknown stat key to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("nonsense"), "message should name the key")
        }
    }
}
