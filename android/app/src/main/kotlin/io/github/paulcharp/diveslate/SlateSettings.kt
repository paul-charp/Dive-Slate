package io.github.paulcharp.diveslate

import android.content.Context
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.SLATE_STYLES
import io.github.paulcharp.diveslate.core.STAT_KEYS
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateStyle
import io.github.paulcharp.diveslate.core.SlateTheme
import io.github.paulcharp.diveslate.core.SlateUnits
import io.github.paulcharp.diveslate.core.adopt
import io.github.paulcharp.diveslate.core.styleById
import io.github.paulcharp.diveslate.core.themeNamed

/**
 * Everything the editor lets the user decide, in one object.
 *
 * It exists so those choices can outlive the composition that made them. They
 * were `remember`ed inside the editor, which meant they were reset by a
 * rotation, by opening a second dive, and by every launch — so a diver who had
 * settled on a look re-picked it each time they shared a dive in, which is more
 * taps than the export it precedes.
 *
 * Resolved objects rather than the ids they persist as. The invalid states
 * (a palette the style does not own, a figure list longer than the layout has
 * room for) are all reachable from stored text and none of them are reachable
 * from the UI, so they are resolved once here in [normalised] rather than
 * defended against at every use — `renderOverlay` refuses a foreign palette,
 * and a stored one would otherwise blank the preview on launch with no way back
 * except reinstalling.
 */
data class SlateSettings(
    val style: SlateStyle = FACTORY_STYLE,
    val layout: SlateLayout = SlateLayout.WIDE,
    val theme: SlateTheme = FACTORY_STYLE.defaultTheme,
    val units: SlateUnits = SlateUnits.METRIC,
    val scrimAlpha: Float = FACTORY_STYLE.defaultScrimAlpha,
    /** The checkerboard behind the preview. Not part of the slate. */
    val showBackdrop: Boolean = true,
    val showSite: Boolean = true,
    val showDate: Boolean = false,
    val showScrim: Boolean = true,
    val showCeiling: Boolean = true,
    val showGas: Boolean = false,
    val smooth: Boolean = true,
    /** Hand-picked figures. Empty means automatic. */
    val stats: Set<String> = emptySet(),
) {

    /**
     * The same settings with every cross-axis rule applied.
     *
     * Called after anything that can break one — decoding stored text, changing
     * style, changing layout — rather than trusted to the caller. The rules are
     * the ones the renderer and the pickers already state:
     *
     * * a palette belongs to the style that paints it, so a foreign one is
     *   adopted (keeping the dark/light choice, which is a statement about the
     *   footage) rather than substituted at render time;
     * * the opacity floor is measured per palette, and below it the panel has
     *   stopped doing its job;
     * * the figure budget is the layout's geometry, and an overrun would be
     *   typeset too small to read;
     * * a figure key this build does not know is dropped, which is what a
     *   downgrade looks like from here.
     *
     * Smoothing is deliberately **not** in that list. A style that cannot
     * honour it hides the control rather than greying it, and clearing the flag
     * here would mean a trip through the segment screen silently threw the
     * preference away for every style after it — the same reasoning that keeps
     * the dark/light choice across a style change. [toOptions] masks it instead,
     * so what is drawn is right and what is remembered is what the user asked
     * for.
     */
    fun normalised(): SlateSettings {
        val palette = style.adopt(theme)
        val known = stats.filter { it in STAT_KEYS }
        return copy(
            theme = palette,
            scrimAlpha = scrimAlpha.coerceIn(palette.scrimAlphaMin, 1f),
            stats = STAT_ORDER.filter { it in known }.take(layout.maxFigures).toSet(),
        )
    }

    /**
     * The options these settings describe.
     *
     * One conversion, used by the preview and by the export, so the two cannot
     * describe different slates.
     */
    fun toOptions(): OverlayOptions = OverlayOptions(
        style = style,
        layout = layout,
        theme = theme,
        units = units,
        scrimAlpha = scrimAlpha.coerceAtLeast(theme.scrimAlphaMin),
        showScrim = showScrim,
        showSite = showSite,
        showDate = showDate,
        showCeiling = showCeiling,
        showGas = showGas,
        // A style that cannot honour smoothing is also the one that hides the
        // control, so the flag can never disagree with what is on screen.
        smoothProfile = smooth && style.supportsSmooth,
        stats = stats.takeIf { it.isNotEmpty() }?.let { picked ->
            STAT_ORDER.filter { it in picked }
        },
    )

    /**
     * These settings as one line of text.
     *
     * Hand-rolled rather than a serialisation library, because the format is a
     * dozen scalars and the app otherwise carries no reflection at all — the
     * release build is shrunk with R8 and nothing here needs a keep rule. The
     * same string is what [SlateSettingsStore] persists and what the composition
     * saves across a configuration change, so the two cannot drift.
     *
     * Every value is an id, a name, a boolean or a number, and none of them can
     * contain the separators; the theme names and stat keys are slugs.
     */
    fun encode(): String = listOf(
        "style" to style.id,
        "layout" to layout.id,
        "theme" to theme.name,
        "units" to units.id,
        "scrim" to scrimAlpha.toString(),
        "backdrop" to showBackdrop.toString(),
        "site" to showSite.toString(),
        "date" to showDate.toString(),
        "panel" to showScrim.toString(),
        "ceiling" to showCeiling.toString(),
        "gas" to showGas.toString(),
        "smooth" to smooth.toString(),
        "stats" to STAT_ORDER.filter { it in stats }.joinToString(","),
    ).joinToString(";") { (key, value) -> "$key=$value" }

    companion object {
        private val FACTORY_STYLE: SlateStyle get() = SLATE_STYLES.first()

        /**
         * The order figures are printed in, which is also the order a trimmed
         * set keeps: what falls off is the tail the reader would have seen
         * last, not whichever key a set happened to iterate late.
         */
        val STAT_ORDER: List<String> = listOf(
            "depth", "time", "deco", "gf", "used", "avg", "temp", "sac", "cns", "gas",
        )

        /** What the app ships with, before anyone has saved anything. */
        val FACTORY: SlateSettings = SlateSettings()

        /**
         * Read back a line written by [encode].
         *
         * Every field falls back to the factory value on its own, so a string
         * from an older or newer build — a style since renamed, a key not yet
         * invented — loads as far as it makes sense rather than being discarded
         * whole. Anything left inconsistent by that is settled by [normalised].
         */
        fun decode(text: String): SlateSettings {
            val fields = text.split(";")
                .mapNotNull { field ->
                    val at = field.indexOf('=')
                    if (at <= 0) null else field.take(at) to field.substring(at + 1)
                }
                .toMap()

            fun flag(key: String, fallback: Boolean): Boolean =
                fields[key]?.toBooleanStrictOrNull() ?: fallback

            val style = fields["style"]?.let { styleById(it) } ?: FACTORY.style
            return SlateSettings(
                style = style,
                layout = fields["layout"]?.let { SlateLayout.byId(it) } ?: FACTORY.layout,
                theme = fields["theme"]?.let { style.themeNamed(it) } ?: style.defaultTheme,
                units = fields["units"]?.let { SlateUnits.byId(it) } ?: FACTORY.units,
                // Finite, not merely parseable: "NaN" parses, survives
                // coerceIn untouched, and paints a panel at zero alpha — which
                // looks like a bug in the slate rather than in a stored string.
                scrimAlpha = fields["scrim"]?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: style.defaultScrimAlpha,
                showBackdrop = flag("backdrop", FACTORY.showBackdrop),
                showSite = flag("site", FACTORY.showSite),
                showDate = flag("date", FACTORY.showDate),
                showScrim = flag("panel", FACTORY.showScrim),
                showCeiling = flag("ceiling", FACTORY.showCeiling),
                showGas = flag("gas", FACTORY.showGas),
                smooth = flag("smooth", FACTORY.smooth),
                stats = fields["stats"]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty(),
            ).normalised()
        }
    }
}

/**
 * Where the saved default lives.
 *
 * SharedPreferences rather than DataStore: one string, written when the user
 * asks for it and read once at startup. DataStore would add a dependency and a
 * coroutine to a read that has to have happened before the first frame anyway.
 *
 * Saving is explicit — there is a button — rather than every slider drag being
 * persisted. The editor's settings are a scratchpad for the dive in front of
 * you, and quietly promoting the last thing you tried to the thing you always
 * get is how a remembered default becomes a surprise.
 */
class SlateSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The saved default, or null if the user has never saved one. */
    fun load(): SlateSettings? =
        prefs.getString(KEY_DEFAULT, null)?.let { SlateSettings.decode(it) }

    fun save(settings: SlateSettings) {
        prefs.edit().putString(KEY_DEFAULT, settings.encode()).apply()
    }

    /** Forget the saved default; the app goes back to what it shipped with. */
    fun clear() {
        prefs.edit().remove(KEY_DEFAULT).apply()
    }

    private companion object {
        const val PREFS = "slate"
        const val KEY_DEFAULT = "default"
    }
}
