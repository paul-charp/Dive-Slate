package io.github.paulcharp.diveslate

import java.io.File

/**
 * The app's own copies of the logs it has been given.
 *
 * Every incoming log is copied because the permission granted with a
 * `content://` URI dies with the activity that received it — stashing the URI
 * to open later is a guaranteed bug. For a long time nothing read the directory
 * back; now [RecentDives] does, and this is the store behind it.
 *
 * It holds files and nothing else. **What is worth keeping is decided by
 * [RecentDives]**, because the thing a user recognises is a dive and not a
 * logbook: a file is kept while some remembered dive still needs it and deleted
 * when none does. Two different questions, and putting the retention here as
 * well would mean two authorities disagreeing about the same directory.
 *
 * Plain [File] work and no Android types, so it is tested rather than reasoned
 * about.
 */
class LogCache(private val dir: File) {

    /**
     * Keep a copy of a log, and answer to what it is now called.
     *
     * The file is named for the log, with no timestamp: when it was last opened
     * is a property of a *dive*, and lives in the index. A second copy of a name
     * already held therefore replaces the first. Two logbooks genuinely called
     * `dives.ssrf` exist and this loses one of them — but the alternative is two
     * sets of dives whose source a reader cannot tell apart, which is the same
     * objection this app makes to merging dive lists across files.
     */
    fun save(name: String?, text: String): String? {
        val clean = sanitise(name)
        dir.mkdirs()
        return runCatching {
            File(dir, clean).writeText(text)
            clean
        }.getOrNull()
    }

    /** Whether a copy of this log is still held. */
    fun has(name: String): Boolean = File(dir, name).isFile

    /** The copy of a log, or null if it is no longer held. */
    fun read(name: String): String? =
        File(dir, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /** What the whole cache occupies, which is the number worth showing. */
    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** What one log occupies. */
    fun bytesOf(name: String): Long = File(dir, name).length()

    /**
     * Delete every copy no remembered dive refers to.
     *
     * Called after anything that changes the index. A cached log with nothing
     * pointing at it can never be reached from the start screen again, so
     * keeping it is pure cost.
     */
    fun keepOnly(names: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    /** Forget everything. The logs themselves are wherever they came from. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /**
         * A display name reduced to something safe to be part of a file name.
         *
         * The name comes from another app, via `OpenableColumns.DISPLAY_NAME`,
         * and nothing obliges it to be a legal file name — a separator in it
         * would write outside the cache directory. The surviving set also
         * excludes `|`, which is what [RecentDives] separates its fields with,
         * so a name can never be mistaken for the rest of an index line.
         */
        fun sanitise(name: String?): String {
            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return "log.ssrf"
            return trimmed
                .map { if (it.isLetterOrDigit() || it in " ._-()[]") it else '_' }
                .joinToString("")
                .take(96)
        }
    }
}
