package io.github.paulcharp.diveslate

import io.github.paulcharp.diveslate.core.Dive
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a batch is called in the gallery.
 *
 * One timestamp for the whole batch is what makes a trip's slates sort together,
 * and it is also what puts the entire burden of uniqueness on the dive — which
 * is not enough on its own, since the same dive can arrive in two files. These
 * are the cases where two slates would otherwise be written to one name.
 */
class SlateFilesTest {

    private val at = LocalDateTime.of(2025, 8, 18, 14, 32, 5)

    private fun dive(number: Int?, site: String?): Dive =
        TestDives.reference().copy(number = number, site = site)

    @Test
    fun `a name carries the stamp, the dive number and the site`() {
        assertEquals(
            listOf("diveslate-20250818-143205-118-shark-and-yolanda-reef"),
            SlateFiles.exportNames(listOf(dive(118, "Shark and Yolanda Reef")), at),
        )
    }

    @Test
    fun `one stamp for the whole batch, so it sorts together`() {
        val names = SlateFiles.exportNames(
            listOf(dive(1, "Blue Hole"), dive(2, "Elphinstone")),
            at,
        )
        assertTrue(names.all { it.startsWith("diveslate-20250818-143205-") })
    }

    /**
     * The same dive twice is an ordinary accident — a log shared again after an
     * edit, or two files holding one trip — and de-duplicating it is not
     * possible, since dive numbers are per-logbook. So the names are numbered
     * here, deliberately, rather than left for MediaStore to resolve out of
     * sight.
     */
    @Test
    fun `a repeated dive is numbered rather than overwritten`() {
        val one = dive(118, "Shark and Yolanda Reef")
        val names = SlateFiles.exportNames(listOf(one, one, one), at)

        assertEquals(names.toSet().size, names.size)
        assertEquals("diveslate-20250818-143205-118-shark-and-yolanda-reef", names[0])
        assertEquals("diveslate-20250818-143205-118-shark-and-yolanda-reef-2", names[1])
        assertEquals("diveslate-20250818-143205-118-shark-and-yolanda-reef-3", names[2])
    }

    /** A dive with nothing to name it by still gets a distinct name. */
    @Test
    fun `dives with no number and no site are still told apart`() {
        val bare = dive(null, null)
        val names = SlateFiles.exportNames(listOf(bare, bare), at)

        assertEquals("diveslate-20250818-143205", names[0])
        assertEquals("diveslate-20250818-143205-2", names[1])
    }

    /**
     * Dive sites are written by hand and run long. Capping here keeps the dive
     * number at the front, where truncation by whatever displays the name would
     * otherwise take it.
     */
    @Test
    fun `a long site name is capped and slugged`() {
        val name = SlateFiles.exportNames(
            listOf(dive(7, "Shark and Yolanda Reef, Ras Mohammed, from the north")),
            at,
        ).single()

        assertTrue(name, name.startsWith("diveslate-20250818-143205-7-"))
        assertTrue(name, name.length < 70)
        assertTrue(name, Regex("^[a-z0-9-]+$").matches(name))
    }

    @Test
    fun `punctuation and accents cannot reach the filename`() {
        val name = SlateFiles.exportNames(listOf(dive(3, "Ténéré / Épave #2")), at).single()
        assertTrue(name, Regex("^[a-z0-9-]+$").matches(name))
        assertTrue(name, !name.endsWith("-"))
    }
}
