package io.github.paulcharp.diveslate

import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateUnits
import io.github.paulcharp.diveslate.core.TopoStyle
import io.github.paulcharp.diveslate.ui.recentFigures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/**
 * The dives the start screen offers as a way back in.
 *
 * Two things are worth testing here and nothing else really is. The first is
 * **identity**: a dive is remembered by its number and its time, and getting
 * that wrong does not throw — it silently opens a neighbouring dive, which
 * looks exactly like opening the right one. The second is the **bound**, since
 * this is the only place the app takes storage without being asked, and the
 * whole reason retention hangs off the dive list is so there is one authority
 * over it rather than two.
 */
class RecentDivesTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var cache: LogCache
    private lateinit var recents: RecentDives

    @Before
    fun setUp() {
        dir = folder.newFolder()
        cache = LogCache(File(dir, "logs"))
        recents = RecentDives(File(dir, "recent.index"), cache)
    }

    private fun dive(
        number: Int? = 118,
        at: LocalDateTime? = LocalDateTime.of(2025, 8, 18, 10, 15),
        site: String? = "Shark and Yolanda Reef",
    ) = TestDives.reference().copy(number = number, whenLogged = at, site = site)

    private fun cached(name: String, text: String = "<dives/>"): String =
        cache.save(name, text)!!

    @Test
    fun `a recorded dive comes back with everything the row draws`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(), position = 0, settings = SlateSettings.FACTORY)

        val entries = recents.entries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("Shark and Yolanda Reef", entry.title)
        assertEquals(118, entry.number)
        assertEquals(LocalDateTime.of(2025, 8, 18, 10, 15), entry.whenLogged)
        assertEquals("reference.ssrf", entry.logName)
        assertNotNull(entry.maxDepthMetres)
    }

    @Test
    fun `the look is stored and comes back intact`() {
        val log = cached("reference.ssrf")
        val look = SlateSettings.FACTORY.copy(
            style = TopoStyle,
            layout = SlateLayout.WATCH,
            theme = TopoStyle.themes.last(),
            units = SlateUnits.IMPERIAL,
            showDate = true,
        ).normalised()
        recents.record(log, dive(), position = 0, settings = look)

        // Through the same encoding the saved default uses, so a dive's look and
        // the app's default cannot drift into two formats.
        assertEquals(look, recents.entries()[0].look)
    }

    @Test
    fun `the same dive twice is one row, updated`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(), 0, SlateSettings.FACTORY)
        Thread.sleep(2)
        recents.record(log, dive(), 0, SlateSettings.FACTORY.copy(units = SlateUnits.IMPERIAL))

        val entries = recents.entries()
        assertEquals(1, entries.size)
        assertEquals(SlateUnits.IMPERIAL, entries[0].look.units)
    }

    @Test
    fun `two dives from one log are two rows sharing one copy`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = 118), 0, SlateSettings.FACTORY)
        recents.record(log, dive(number = 119, at = LocalDateTime.of(2025, 8, 18, 14, 0)), 1,
            SlateSettings.FACTORY)

        assertEquals(2, recents.entries().size)
        assertTrue(cache.has("reference.ssrf"))
    }

    @Test
    fun `the most recently opened is first`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = 1), 0, SlateSettings.FACTORY)
        Thread.sleep(2)
        recents.record(log, dive(number = 2, site = "Blue Hole"), 1, SlateSettings.FACTORY)

        assertEquals(listOf("Blue Hole", "Shark and Yolanda Reef"), recents.entries().map { it.title })
    }

    // --- identity -----------------------------------------------------------

    @Test
    fun `a dive is found by its number and time, not by its position`() {
        val log = cached("reference.ssrf")
        val target = dive(number = 118)
        recents.record(log, target, position = 1, settings = SlateSettings.FACTORY)

        // The logbook has since been re-exported with a dive inserted ahead of
        // it, so the stored position now points at something else entirely.
        val reparsed = DiveLog(listOf(dive(number = 900, at = LocalDateTime.of(2026, 1, 1, 9, 0)),
            dive(number = 200, at = LocalDateTime.of(2026, 1, 2, 9, 0)), target))

        assertEquals(2, recents.resolve(reparsed, recents.entries()[0]))
    }

    @Test
    fun `position is the fallback when a dive has neither number nor time`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = null, at = null), position = 1, settings = SlateSettings.FACTORY)

        val reparsed = DiveLog(listOf(dive(), dive(number = null, at = null)))
        assertEquals(1, recents.resolve(reparsed, recents.entries()[0]))
    }

    @Test
    fun `a dive no longer in its log resolves to nothing rather than to a neighbour`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = 118), position = 4, settings = SlateSettings.FACTORY)

        // Opening the dive next to the one that was asked for is a slate of the
        // wrong dive, and it looks exactly like a slate of the right one.
        val reparsed = DiveLog(listOf(dive(number = 900, at = LocalDateTime.of(2026, 1, 1, 9, 0))))
        assertNull(recents.resolve(reparsed, recents.entries()[0]))
    }

    // --- retention ----------------------------------------------------------

    @Test
    fun `only the most recent dives are remembered`() {
        repeat(RecentDives.MAX_DIVES + 5) {
            recents.record(cached("log$it.ssrf"), dive(number = it,
                at = LocalDateTime.of(2025, 1, 1, 0, 0).plusHours(it.toLong()),
                site = "Site $it"), 0, SlateSettings.FACTORY)
            Thread.sleep(2)
        }

        val entries = recents.entries()
        assertEquals(RecentDives.MAX_DIVES, entries.size)
        assertTrue(entries.none { it.title == "Site 0" })
        assertTrue(entries.any { it.title == "Site ${RecentDives.MAX_DIVES + 4}" })
    }

    @Test
    fun `a log nothing refers to any more is deleted`() {
        repeat(RecentDives.MAX_DIVES + 3) {
            recents.record(cached("log$it.ssrf"), dive(number = it,
                at = LocalDateTime.of(2025, 1, 1, 0, 0).plusHours(it.toLong())), 0,
                SlateSettings.FACTORY)
            Thread.sleep(2)
        }

        // The dive list is the authority: a copy the list can no longer reach is
        // pure cost, and there is no second retention rule keeping it alive.
        assertFalse(cache.has("log0.ssrf"))
        assertTrue(cache.has("log${RecentDives.MAX_DIVES + 2}.ssrf"))
    }

    @Test
    fun `a run of large logs is trimmed by size before it reaches the count`() {
        val big = "x".repeat(3 * 1024 * 1024)
        repeat(4) {
            recents.record(cached("big$it.ssrf", big), dive(number = it,
                at = LocalDateTime.of(2025, 1, 1, 0, 0).plusHours(it.toLong()),
                site = "Site $it"), 0, SlateSettings.FACTORY)
            Thread.sleep(2)
        }

        val entries = recents.entries()
        assertTrue("kept ${entries.size} of 4 three-megabyte logs", entries.size < 4)
        assertTrue(cache.totalBytes() <= RecentDives.MAX_BYTES)
        assertEquals("Site 3", entries.first().title)
    }

    @Test
    fun `a log larger than the whole budget is still kept`() {
        val huge = "x".repeat((RecentDives.MAX_BYTES + 1024).toInt())
        recents.record(cached("huge.ssrf", huge), dive(), 0, SlateSettings.FACTORY)

        // Evicting the dive just worked on to satisfy a limit would empty the
        // list at the moment it was most useful.
        assertEquals(1, recents.entries().size)
        assertTrue(cache.has("huge.ssrf"))
    }

    @Test
    fun `several dives in one big log are not charged for it several times`() {
        val big = "x".repeat(5 * 1024 * 1024)
        val log = cached("big.ssrf", big)
        repeat(4) {
            recents.record(log, dive(number = it,
                at = LocalDateTime.of(2025, 1, 1, 0, 0).plusHours(it.toLong())), it,
                SlateSettings.FACTORY)
            Thread.sleep(2)
        }

        // One copy on disk, so four dives from it cost five megabytes and not
        // twenty — counting it per dive would evict rows for space nothing uses.
        assertEquals(4, recents.entries().size)
    }

    // --- the text it is stored as -------------------------------------------

    @Test
    fun `a site containing the separator cannot break the line`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(site = "Reef | North | Deep"), 0, SlateSettings.FACTORY)

        val entries = recents.entries()
        assertEquals(1, entries.size)
        assertFalse(entries[0].title.contains("|"))
        assertEquals(118, entries[0].number)
    }

    @Test
    fun `a site containing a newline cannot become two rows`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(site = "Blue Hole\nsecond line"), 0, SlateSettings.FACTORY)

        assertEquals(1, recents.entries().size)
    }

    @Test
    fun `a corrupt line is dropped rather than repaired`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(), 0, SlateSettings.FACTORY)
        val index = File(dir, "recent.index")
        index.writeText(index.readText() + "\nnot|an|entry\n")

        assertEquals(1, recents.entries().size)
    }

    @Test
    fun `a dive with neither site nor number still has something to be called`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = null, site = null), 0, SlateSettings.FACTORY)

        assertEquals("Untitled dive", recents.entries()[0].title)
    }

    // --- what the row reads as ----------------------------------------------

    /** An entry built directly, so a duration can be stated rather than sampled. */
    private fun entry(seconds: Double, site: String? = "Blue Hole", number: Int? = 118) =
        RecentDives.Entry(
            openedAt = 0L,
            logName = "reference.ssrf",
            number = number,
            whenLogged = null,
            position = 0,
            site = site,
            maxDepthMetres = 44.4,
            durationSeconds = seconds,
            settings = SlateSettings.FACTORY.encode(),
        )

    @Test
    fun `a runtime under an hour carries its unit`() {
        val segments = recentFigures(entry(55.0 * 60), SlateUnits.METRIC).split(" · ")
        assertTrue(segments.toString(), segments.contains("55 min"))
    }

    @Test
    fun `a runtime past an hour reads as a time and drops the unit`() {
        // The whole segment, not a substring: the row joins with a separator, so
        // "contains" would pass on a figure that had kept a trailing unit.
        val segments = recentFigures(entry(65.0 * 60), SlateUnits.METRIC).split(" · ")
        assertTrue(segments.toString(), segments.contains("1:05"))
    }

    @Test
    fun `depth rounds up, and converts before it rounds`() {
        // 44.4 m is 45 m and 146 ft. Rounding first and converting gives 148,
        // which would overstate the dive by nearly a metre.
        assertTrue(recentFigures(entry(600.0), SlateUnits.METRIC).startsWith("45 m"))
        assertTrue(recentFigures(entry(600.0), SlateUnits.IMPERIAL).startsWith("146 ft"))
    }

    @Test
    fun `neither figure is ever printed as a pair`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(), 0, SlateSettings.FACTORY)

        // Both come back from core as (value, unit), and interpolating one of
        // those whole prints "(55, min)" — which compiles, renders, and is only
        // wrong to look at. It shipped that way once.
        val figures = recentFigures(recents.entries()[0], SlateUnits.METRIC)
        assertFalse(figures, figures.contains("("))
        assertFalse(figures, figures.contains(","))
    }

    @Test
    fun `the figures follow the units the dive was left in`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(), 0, SlateSettings.FACTORY)
        val entry = recents.entries()[0]

        assertTrue(recentFigures(entry, SlateUnits.METRIC).contains(" m"))
        assertTrue(recentFigures(entry, SlateUnits.IMPERIAL).contains(" ft"))
    }

    @Test
    fun `a dive with no site does not print its number twice`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(site = null), 0, SlateSettings.FACTORY)

        // The number is already the title in that case.
        assertEquals("Dive 118", recents.entries()[0].title)
        assertFalse(recentFigures(recents.entries()[0], SlateUnits.METRIC).contains("#"))
    }

    @Test
    fun `clearing forgets the dives and deletes the copies`() {
        recents.record(cached("a.ssrf"), dive(number = 1), 0, SlateSettings.FACTORY)
        recents.record(cached("b.ssrf"), dive(number = 2, at = LocalDateTime.of(2025, 9, 1, 9, 0)),
            0, SlateSettings.FACTORY)
        recents.clear()

        assertTrue(recents.entries().isEmpty())
        assertEquals(0L, recents.bytes())
    }

    @Test
    fun `forgetting one dive leaves the others alone`() {
        val log = cached("reference.ssrf")
        recents.record(log, dive(number = 1), 0, SlateSettings.FACTORY)
        recents.record(log, dive(number = 2, at = LocalDateTime.of(2025, 9, 1, 9, 0)), 1,
            SlateSettings.FACTORY)

        recents.forget(recents.entries().first { it.number == 1 })

        assertEquals(listOf(2), recents.entries().map { it.number })
    }

    @Test
    fun `a dive whose log is uncached is still recordable but reads back as gone`() {
        // The activity refuses to record one of these; the store itself does not
        // need to, and the retention pass deletes nothing that was never there.
        recents.record("never-saved.ssrf", dive(), 0, SlateSettings.FACTORY)

        assertEquals(1, recents.entries().size)
        assertNull(cache.read("never-saved.ssrf"))
    }
}
