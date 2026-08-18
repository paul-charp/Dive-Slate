package io.github.paulcharp.diveslate.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Properties of the rendered slate.
 *
 * There is no recorded fixture for the drawing operations — the original SVG
 * writer emitted a document, not a display list, and pinning the renderer to
 * that markup would have been pinning it to the wrong thing. So these assert
 * the properties the layout has to hold, several of which encode decisions
 * CLAUDE.md records as expensive.
 */
class SlateRendererTest {

    private val dive: Dive by lazy {
        val source = File(Fixtures.repoRoot, "conformance/data/reference.ssrf")
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
     * The compact layout exists to sit in a corner, so it must actually be a
     * corner-sized block rather than another full-width strip.
     *
     * Both halves matter. Narrow without squaring up is just a smaller strip,
     * and squared without narrowing is a poster — the shape is the whole reason
     * for the third layout.
     */
    @Test
    fun `the compact layout is a corner badge rather than a strip`() {
        val wide = renderOverlay(dive, OverlayOptions(layout = SlateLayout.WIDE))
        val compact = renderOverlay(dive, OverlayOptions(layout = SlateLayout.COMPACT))

        assertTrue(
            compact.width < wide.width * 0.7f,
            "compact (${compact.width}px) is not appreciably narrower than wide (${wide.width}px)",
        )
        assertTrue(
            compact.height > wide.height * 0.7f,
            "compact (${compact.height}px) gave up the height that makes it square-ish",
        )
        assertTrue(
            compact.width / compact.height < wide.width / wide.height,
            "compact is no squarer than wide",
        )
    }

    /**
     * The profile takes a smaller share of the compact badge.
     *
     * That is the point of it: at corner scale a depth curve is a texture, so
     * the figures lead and the profile says which dive it was. A compact layout
     * whose curve still dominates has only changed the outline.
     */
    @Test
    fun `the compact layout gives the profile less of the slate`() {
        fun profileShare(layout: SlateLayout): Float {
            val slate = renderOverlay(dive, OverlayOptions(layout = layout))
            return layout.metrics(SlateLayout.REFERENCE_WIDTH).curveHeight / slate.height
        }

        val compact = profileShare(SlateLayout.COMPACT)
        val wide = profileShare(SlateLayout.WIDE)
        assertTrue(
            compact < wide,
            "the profile takes $compact of the compact slate against $wide of the wide one",
        )
    }

    /**
     * A figure too wide for its column is shrunk, not left to collide.
     *
     * The layout's figure budget is the first defence and handles the ordinary
     * case — it is why `Air, O2` fits a corner badge at full size. It cannot
     * handle every case, because one of the figures is a list: a trimix dive on
     * three mixes prints a value four times the length of any number, and it
     * overruns its column in *every* layout. Two figures running into each
     * other leaves neither readable, and dropping one would make the slate
     * silently say less than it was asked to.
     *
     * Advance is estimated from character count here exactly as the renderer
     * estimates it, because core has no font: it emits a display list and the
     * app paints it. What the test pins is that the fitting is applied at all,
     * which is the part that can regress.
     *
     * Note there is no lower bound asserted. A floor was tried and removed: it
     * hands back a size that still does not fit, so the figure overlaps its
     * neighbour anyway and two columns are lost instead of one being small.
     */
    @Test
    fun `a figure too wide for its column is shrunk to fit`() {
        // Three mixes, so "Gases" is a sentence rather than a number.
        val trimix = Dive(
            samples = listOf(Sample(0.0, 0.0), Sample(600.0, 48.0), Sample(3600.0, 0.0)),
            gasSwitches = listOf(
                GasSwitch(0.0, GasMix(o2 = 0.18, he = 0.45)),
                GasSwitch(1800.0, GasMix(o2 = 0.50)),
                GasSwitch(3000.0, GasMix(o2 = 1.0)),
            ),
        )

        for (layout in SlateLayout.entries) {
            val m = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            val slate = renderOverlay(
                trimix,
                OverlayOptions(layout = layout, stats = listOf("gas", "depth")),
            )
            // A stacked layout hands each figure the whole width; a columned
            // one splits it. Either way nothing may reach past its own share.
            val inner = m.width - m.pad * 2
            val slot = if (m.figuresStacked) inner else inner / 2
            val gases = slate.texts().first { it.text.contains("Tx18/45") }

            assertTrue(
                gases.sizePx < m.valueSize,
                "${layout.id} kept the full ${m.valueSize}px for '${gases.text}' " +
                    "and ran into the next column",
            )
            assertTrue(
                gases.sizePx > 0f,
                "${layout.id} shrank a figure out of existence",
            )

            // Nothing on the figure baseline may reach into the column right of it.
            for (text in slate.texts()) {
                if (text.baselineY != gases.baselineY) continue
                val column = ((text.x - m.pad) / slot).toInt()
                val right = text.x + text.text.length * text.sizePx * 0.56f
                assertTrue(
                    right <= m.pad + (column + 1) * slot,
                    "${layout.id}: '${text.text}' overruns column $column, ending at $right",
                )
            }
        }
    }

    /** A figure that fits is typeset at the layout's own size, untouched. */
    @Test
    fun `figures that fit keep the layout's figure size`() {
        for (layout in SlateLayout.entries) {
            val m = layout.metrics(SlateLayout.REFERENCE_WIDTH)
            val slate = renderOverlay(dive, OverlayOptions(layout = layout, stats = listOf("depth")))
            val value = slate.texts().first { it.text == "45" }
            assertEquals(
                m.valueSize,
                value.sizePx,
                "${layout.id} shrank a figure that had a whole slate to sit in",
            )
        }
    }

    /**
     * Watch and Compact are two shapes, not two sizes.
     *
     * Worth stating precisely, because the obvious claim is false: stacking
     * makes Watch *taller* than Compact, so their areas come out within a few
     * percent of each other. What actually separates them is footprint width
     * and squareness — Watch spends much less of the frame's width, which is
     * what "put it in a corner" means, and reads as a block rather than a bar.
     */
    @Test
    fun `the watch layout is narrower and squarer than the compact one`() {
        val watch = renderOverlay(dive, OverlayOptions(layout = SlateLayout.WATCH))
        val compact = renderOverlay(dive, OverlayOptions(layout = SlateLayout.COMPACT))

        assertTrue(
            watch.width < compact.width,
            "watch (${watch.width}px) takes no less width than compact (${compact.width}px)",
        )
        val watchOff = abs(1f - watch.width / watch.height)
        val compactOff = abs(1f - compact.width / compact.height)
        assertTrue(
            watchOff < compactOff,
            "watch is no squarer than compact: $watchOff against $compactOff",
        )
        // And both stay corner-sized rather than creeping back to full width.
        for (slate in listOf(watch, compact)) {
            assertTrue(
                slate.width < SlateLayout.REFERENCE_WIDTH / 2f,
                "a corner badge grew to ${slate.width}px of a 1080px frame",
            )
        }
    }

    /**
     * A long site name is fitted, not left to run off the badge.
     *
     * Every fixture here is a conveniently short name, which is exactly the trap
     * the trip fixture taught: a corpus assembled from convenient exports has a
     * shape no real logbook has. `SS Thistlegorm, Sha'ab Ali` needs 379px of the
     * 340px the watch badge has, and there is no scrim edge to hide the overrun.
     */
    @Test
    fun `a long site name is fitted to the badge`() {
        val long = "SS Thistlegorm, Sha'ab Ali"
        val wordy = Dive(
            samples = listOf(Sample(0.0, 0.0), Sample(600.0, 18.0)),
            site = long,
        )
        val m = SlateLayout.WATCH.metrics(SlateLayout.REFERENCE_WIDTH)
        val site = renderOverlay(wordy, OverlayOptions(layout = SlateLayout.WATCH))
            .texts().first { it.text == long.uppercase() }

        assertTrue(site.sizePx < m.siteSize, "the site name was left at full size and overhangs")
        // Fitting targets the available width exactly, so compare with a
        // tolerance rather than asserting an exact float equality.
        val right = site.x + long.length * site.sizePx * 0.56f
        assertTrue(
            right <= m.width - m.pad + 0.5f,
            "the fitted site name still runs past the badge, ending at $right",
        )

        // A name that fits is left alone.
        val short = Dive(samples = wordy.samples, site = "Blue Hole")
        val kept = renderOverlay(short, OverlayOptions(layout = SlateLayout.WATCH))
            .texts().first { it.text == "BLUE HOLE" }
        assertEquals(m.siteSize, kept.sizePx, "a site name that fits was shrunk anyway")
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

    /**
     * Every stat key the UI offers must actually render.
     *
     * The picker lists all of them, so a key that builds nothing for a dive
     * that plainly recorded the figure would look like a broken control rather
     * than a missing measurement.
     */
    @Test
    fun `every offered stat renders for a dive that recorded it`() {
        val expected = mapOf(
            "depth" to ("MAX DEPTH" to "45"),
            "time" to ("RUNTIME" to "1:05"),
            "deco" to ("DECO" to "24"),
            "gf" to ("GF" to "70/80"),
            "used" to ("GAS USED" to "3388"),
            "avg" to ("AVG DEPTH" to "24"),
            "temp" to ("TEMP" to "15"),
            "sac" to ("SAC" to "15.4"),
            "cns" to ("CNS" to "31"),
            "gas" to ("GASES" to "Air, O2"),
        )

        for ((key, pair) in expected) {
            val (label, value) = pair
            val texts = renderOverlay(dive, OverlayOptions(stats = listOf(key))).texts().map { it.text }
            assertTrue(texts.contains(label), "stat '$key' produced no '$label' label; got $texts")
            assertTrue(texts.contains(value), "stat '$key' produced no '$value' value; got $texts")
        }
    }

    /** SAC in particular, since it was the one missing from the picker. */
    @Test
    fun `sac renders with one decimal and its unit`() {
        val texts = renderOverlay(dive, OverlayOptions(stats = listOf("sac"))).texts().map { it.text }
        assertTrue(texts.contains("SAC"), "no SAC label: $texts")
        assertTrue(texts.contains("15.4"), "no SAC value: $texts")
        assertTrue(texts.contains("L/min"), "no SAC unit: $texts")
    }

    /** The picker offers every key the core knows about, and no others. */
    @Test
    fun `no stat key is unrenderable`() {
        for (key in STAT_KEYS) {
            renderOverlay(dive, OverlayOptions(stats = listOf(key)))
        }
    }

    /**
     * [availableStats] has to agree with what the renderer actually produces,
     * because the editor warns from the first about slates drawn by the second.
     * A disagreement would warn about a figure that renders fine, or — worse —
     * stay quiet about one that silently vanishes from half a batch.
     */
    @Test
    fun `availableStats agrees with what resolveStats can build`() {
        val available = availableStats(dive)
        for (key in STAT_KEYS) {
            val built = resolveStats(dive, OverlayOptions(stats = listOf(key))).isNotEmpty()
            assertEquals(built, key in available, "availableStats disagrees about '$key'")
        }
    }

    /** A dive the log barely recorded still answers, and answers with less. */
    @Test
    fun `availableStats shrinks for a dive carrying nothing derived`() {
        val bare = Dive(samples = dive.samples)
        val available = availableStats(bare)
        // Depth and runtime come from the samples themselves, so they survive.
        assertTrue("depth" in available, "depth should always be available")
        assertTrue("time" in available, "runtime should always be available")
        // These need cylinders, a label, or switches the bare dive has none of.
        assertTrue("gf" !in available, "a dive with no label has no GFs")
        assertTrue("used" !in available, "a dive with no cylinders has no gas used")
        assertTrue("gas" !in available, "a dive with no switches has no mixes")
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
