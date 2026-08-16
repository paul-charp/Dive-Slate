package io.github.paulcharp.diveslate.core

/**
 * Pick the right parser for a document.
 *
 * Detection is content-first. Extensions only decide what to *try* first,
 * because they are unreliable in practice: both Subsurface and UDDF exports
 * routinely arrive as plain `.xml`, and dive logs get renamed on the way out of
 * a computer's software.
 *
 * This matters more on Android than it did on the desktop. A share intent hands
 * over a `content://` URI that frequently carries no usable filename at all, so
 * content sniffing is the only thing there is to go on.
 */

/** A dive log reader. */
interface DiveLogParser {
    val formatName: String
    val extensions: List<String>
    fun sniff(text: String): Boolean
    fun parse(text: String, source: String? = null): DiveLog
}

private object Subsurface : DiveLogParser {
    override val formatName = SubsurfaceParser.FORMAT_NAME
    override val extensions = SubsurfaceParser.extensions
    override fun sniff(text: String) = SubsurfaceParser.sniff(text)
    override fun parse(text: String, source: String?) = SubsurfaceParser.parse(text, source)
}

private object Uddf : DiveLogParser {
    override val formatName = UddfParser.FORMAT_NAME
    override val extensions = UddfParser.extensions
    override fun sniff(text: String) = UddfParser.sniff(text)
    override fun parse(text: String, source: String?) = UddfParser.parse(text, source)
}

/**
 * Every parser, in the order they are tried.
 *
 * The Python original discovers these through entry points so third parties can
 * add formats. There is no equivalent on Android and no plugin story for a
 * phone app, so the list is explicit — adding a format means adding a line here.
 */
val PARSERS: List<DiveLogParser> = listOf(Subsurface, Uddf)

/**
 * The parser that claims [text].
 *
 * [hint] is an optional filename or extension, used only to order candidates.
 */
fun sniff(text: String, hint: String? = null): DiveLogParser {
    val suffix = hint?.substringAfterLast('.', "")?.lowercase()?.let { if (it.isEmpty()) null else ".$it" }
    val candidates = if (suffix == null) {
        PARSERS
    } else {
        PARSERS.sortedBy { suffix !in it.extensions }
    }

    val head = text.take(SNIFF_CHARS)
    for (parser in candidates) {
        // A parser that throws while sniffing must not mask a good one.
        val claimed = try {
            parser.sniff(head)
        } catch (_: Exception) {
            false
        }
        if (claimed) return parser
    }

    throw ParseException(
        "unrecognised dive log format; expected Subsurface XML (<divelog>) or UDDF (<uddf>)"
    )
}

/** Detect the format of [text] and parse it. */
fun parseText(text: String, hint: String? = null, source: String? = null): DiveLog =
    sniff(text, hint).parse(text, source)
