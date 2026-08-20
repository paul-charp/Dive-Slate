package io.github.paulcharp.diveslate

import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.DiveLog
import java.io.File
import java.time.LocalDateTime

/**
 * The dives most recently taken into the editor, and the look each was given.
 *
 * A dive, not a file. The first version of this listed logbooks — `reference.ssrf,
 * 6 dives` — which made the reader do the finding, when the thing they came back
 * for is *that dive*. Listing dives makes the start screen a list of the slates
 * you have made, which is much closer to what the app is for.
 *
 * Three decisions are worth stating, because none of them is the obvious one:
 *
 * * **Reaching the editor is what makes a dive recent**, not opening the file
 *   it is in. A 200-dive logbook would otherwise put 200 rows here, none of
 *   which anyone chose. Opening the editor is a statement about one dive — or,
 *   for a batch, about each dive in the selection, which is why all of them are
 *   recorded.
 * * **A dive is identified by its number and its time, not by its position.**
 *   The list is sorted newest-first for display, so an index into *that* would
 *   move the moment a logbook gained a dive, and a remembered slate would
 *   quietly become a different dive's. Both fields are optional in the model,
 *   so the raw position in the file is kept as a last resort — see [resolve].
 * * **The look is stored per dive**, and restoring it is scoped to that dive;
 *   see [SessionState.editorSettings] for why it must not become the session's.
 *
 * One line of text per entry, in the same spirit as [SlateSettings.encode]: the
 * app carries no reflection at all and the release build is shrunk with R8, so
 * a serialisation library would be a dependency and a keep rule for a dozen
 * scalars. The encoded settings ride in the last field, which is why the
 * separator here is a pipe — a character [LogCache.sanitise] removes and
 * `encode()` never emits.
 */
class RecentDives(private val index: File, private val cache: LogCache) {

    /**
     * One remembered dive.
     *
     * Everything the row draws is stored, because drawing it otherwise means
     * parsing: a start screen that parses every cached logbook to label its own
     * rows costs whatever the largest one costs, on every launch. All of it is
     * known for free at record time, where the dive is already in hand.
     */
    data class Entry(
        val openedAt: Long,
        /** Which cached log holds it; see [LogCache]. */
        val logName: String,
        val number: Int?,
        val whenLogged: LocalDateTime?,
        /** Position in the file, used only when the two above cannot decide. */
        val position: Int,
        val site: String?,
        val maxDepthMetres: Double?,
        val durationSeconds: Double?,
        /** The look this dive was last given, as [SlateSettings.encode] writes it. */
        val settings: String,
    ) {
        /** What the row calls it. A dive with neither a site nor a number happens. */
        val title: String
            get() = site?.takeIf { it.isNotBlank() }
                ?: number?.let { "Dive $it" }
                ?: "Untitled dive"

        val look: SlateSettings get() = SlateSettings.decode(settings)

        /** Same dive, same slate: what makes a second visit an update. */
        fun sameAs(other: Entry): Boolean =
            logName == other.logName &&
                number == other.number &&
                whenLogged == other.whenLogged &&
                position == other.position
    }

    /** Every remembered dive, most recently opened first. */
    fun entries(): List<Entry> =
        runCatching { index.readLines() }.getOrNull()
            ?.mapNotNull(::decode)
            ?.sortedByDescending { it.openedAt }
            .orEmpty()

    /** What the cache occupies on disk, for the one line that admits the cost. */
    fun bytes(): Long = cache.totalBytes()

    /**
     * Remember a dive, with the look it is being given.
     *
     * A dive already remembered is updated in place rather than duplicated —
     * the same dive opened twice is one row, and the second visit is what its
     * timestamp and its look now describe.
     *
     * **A dive whose log is not cached is not recorded.** The row would be one
     * that cannot be opened, which is worse than no row; and it is how the
     * bundled sample stays out of the list, since that is the one log the app
     * deliberately keeps no copy of. Checked here rather than at the call site
     * so the rule sits with the rest of what this class decides to keep.
     */
    fun record(logName: String, dive: Dive, position: Int, settings: SlateSettings) {
        if (!cache.has(logName)) return
        val entry = Entry(
            openedAt = System.currentTimeMillis(),
            logName = logName,
            number = dive.number,
            whenLogged = dive.whenLogged,
            position = position,
            site = dive.site?.let(::clean),
            // The computed figures, not the logged ones: the slate prints what
            // the samples say, and a row quoting the optional field would read
            // blank for exactly the dives whose slate shows a depth.
            maxDepthMetres = dive.computedMaxDepthMetres,
            durationSeconds = dive.computedDurationSeconds,
            settings = settings.encode(),
        )
        write(entries().filterNot { it.sameAs(entry) } + entry)
    }

    /** Drop one dive — used when its log turns out to be unreadable. */
    fun forget(entry: Entry) {
        write(entries().filterNot { it.sameAs(entry) })
    }

    /** Forget every dive, and every log copy behind them. */
    fun clear() {
        index.delete()
        cache.clear()
    }

    /**
     * Which dive in a freshly parsed log this entry means.
     *
     * By number and time first, because those belong to the dive. The stored
     * position is the fallback and is deliberately last: it is the field that
     * silently means something else when a logbook is re-exported with an extra
     * dive in it. Null when the log no longer contains the dive at all, which
     * the caller reports rather than papering over with a neighbouring dive.
     */
    fun resolve(log: DiveLog, entry: Entry): Int? {
        if (entry.number != null || entry.whenLogged != null) {
            val matched = log.dives.indexOfFirst {
                it.number == entry.number && it.whenLogged == entry.whenLogged
            }
            if (matched >= 0) return matched
        }
        return entry.position.takeIf { it in log.dives.indices }
    }

    /**
     * Write the index, then bring the cache into line with it.
     *
     * Retention hangs off the dive list rather than off the files, because the
     * dive list is what the user sees: a log is worth keeping exactly while
     * some remembered dive still needs it.
     *
     * [MAX_BYTES] is a second bound and a blunter one — twenty dives spread
     * across twenty large logbooks would satisfy the count and still cost
     * hundreds of megabytes. It is applied by *shortening the list*, oldest
     * first, so there is only ever one authority. The newest entry is never
     * dropped: a single logbook larger than the whole budget is a real thing to
     * open, and evicting the dive the user just worked on to satisfy a limit
     * would empty the list at the moment it was most useful.
     */
    private fun write(all: List<Entry>) {
        var kept = all.sortedByDescending { it.openedAt }.take(MAX_DIVES)
        while (kept.size > 1 && bytesOf(kept) > MAX_BYTES) kept = kept.dropLast(1)

        runCatching {
            index.parentFile?.mkdirs()
            index.writeText(kept.joinToString("\n") { encode(it) })
        }
        cache.keepOnly(kept.map { it.logName }.toSet())
    }

    /** What a set of entries costs, counting each log once however many use it. */
    private fun bytesOf(entries: List<Entry>): Long =
        entries.map { it.logName }.distinct().sumOf { cache.bytesOf(it) }

    private fun encode(e: Entry): String = listOf(
        e.openedAt.toString(),
        e.logName,
        e.number?.toString().orEmpty(),
        e.whenLogged?.toString().orEmpty(),
        e.position.toString(),
        e.site.orEmpty(),
        e.maxDepthMetres?.toString().orEmpty(),
        e.durationSeconds?.toString().orEmpty(),
        e.settings,
    ).joinToString(SEPARATOR)

    /**
     * Read one line back.
     *
     * A line that does not decode is dropped rather than repaired. This is a
     * cache: the cost of a bad line is one dive the user opens by hand, and the
     * cost of guessing at it is a row that means something it does not say.
     */
    private fun decode(line: String): Entry? {
        val f = line.split(SEPARATOR)
        if (f.size != FIELDS) return null
        val openedAt = f[0].toLongOrNull() ?: return null
        val logName = f[1].takeIf { it.isNotBlank() } ?: return null
        val position = f[4].toIntOrNull() ?: return null
        return Entry(
            openedAt = openedAt,
            logName = logName,
            number = f[2].toIntOrNull(),
            whenLogged = f[3].takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            position = position,
            site = f[5].takeIf { it.isNotBlank() },
            maxDepthMetres = f[6].toDoubleOrNull(),
            durationSeconds = f[7].toDoubleOrNull(),
            settings = f[8],
        )
    }

    /** A site name is free text from a logbook, and the index is line-based. */
    private fun clean(text: String): String =
        text.replace(SEPARATOR, " ").replace('\n', ' ').replace('\r', ' ').trim().take(120)

    companion object {
        /** How many dives to remember. Entries are tiny; the logs are the cost. */
        const val MAX_DIVES = 20

        /** How much disk the logs behind them may occupy. */
        const val MAX_BYTES = 8L * 1024 * 1024

        private const val SEPARATOR = "|"
        private const val FIELDS = 9
    }
}
