package io.github.paulcharp.diveslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's own copies of the logs it has been given.
 *
 * A file store and nothing more — what is worth keeping is [RecentDives]'
 * decision, and is tested there. What is left here is the one thing this class
 * alone is responsible for: a display name arrives from another app and is the
 * only input in this app that can name a path.
 */
class LogCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private fun cache() = LogCache(folder.newFolder())

    @Test
    fun `a saved log reads back under the name it was given`() {
        val cache = cache()
        val name = cache.save("reference.ssrf", "<dives/>")

        assertEquals("reference.ssrf", name)
        assertTrue(cache.has("reference.ssrf"))
        assertEquals("<dives/>", cache.read("reference.ssrf"))
    }

    @Test
    fun `the same name replaces rather than accumulates`() {
        val cache = cache()
        cache.save("dives.ssrf", "old")
        cache.save("dives.ssrf", "new")

        assertEquals("new", cache.read("dives.ssrf"))
        assertEquals(3L, cache.totalBytes())
    }

    @Test
    fun `a display name cannot write outside the cache`() {
        val cache = cache()
        val name = cache.save("../../evil.ssrf", "x")

        assertNotNull(name)
        assertFalse(name!!.contains("/"))
        assertFalse(name.contains("\\"))
        assertTrue(cache.has(name))
    }

    @Test
    fun `a name cannot contain the index separator`() {
        // RecentDives stores one entry per line with pipe-separated fields, and
        // the log name is one of them; a pipe here would split a row in two.
        assertFalse(LogCache.sanitise("red|sea.ssrf").contains("|"))
    }

    @Test
    fun `a name with dashes and spaces survives unchanged`() {
        assertEquals("red-sea 2026 (deep).ssrf", LogCache.sanitise("red-sea 2026 (deep).ssrf"))
    }

    @Test
    fun `a log with no name at all still gets one`() {
        val cache = cache()
        assertEquals("log.ssrf", cache.save(null, "x"))
    }

    @Test
    fun `a log that was never saved reads as nothing`() {
        val cache = cache()
        assertNull(cache.read("absent.ssrf"))
        assertFalse(cache.has("absent.ssrf"))
    }

    @Test
    fun `keepOnly deletes what is not named and nothing else`() {
        val cache = cache()
        cache.save("keep.ssrf", "x")
        cache.save("drop.ssrf", "x")
        cache.keepOnly(setOf("keep.ssrf"))

        assertTrue(cache.has("keep.ssrf"))
        assertFalse(cache.has("drop.ssrf"))
    }

    @Test
    fun `clearing empties it and reports nothing`() {
        val cache = cache()
        cache.save("a.ssrf", "x")
        cache.clear()

        assertEquals(0L, cache.totalBytes())
    }
}
