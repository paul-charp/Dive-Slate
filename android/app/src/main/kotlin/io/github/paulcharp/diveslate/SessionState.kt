package io.github.paulcharp.diveslate

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.paulcharp.diveslate.ui.ExportState
import io.github.paulcharp.diveslate.ui.LoadState
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.ui.UpdateState
import java.io.File

/** A dive on its way into the editor: which cached log holds it, and where. */
data class EditingDive(val logName: String, val dive: Dive, val position: Int)

/**
 * Everything the app is in the middle of, held where a rotation cannot reach it.
 *
 * All of this used to be fields on the activity, which meant Android threw it
 * away on every configuration change: rotating the phone with a log open landed
 * back on the start screen, and the dive log itself was gone — a share survived
 * only because the intent was still attached and got read a second time, and a
 * file opened from the picker had no such luck.
 *
 * A ViewModel outlives the activity across those changes, so the work outlives
 * it too: the export coroutines run in [androidx.lifecycle.viewModelScope]
 * rather than the activity's, and a batch of twenty slates is no longer
 * cancelled halfway by someone turning the phone over.
 *
 * What it deliberately does **not** survive is process death. The dive log is
 * parsed from a `content://` URI whose grant dies with the task, so there would
 * be nothing to restore it from; the start screen is the honest answer there.
 */
class SessionState(application: Application) : AndroidViewModel(application) {

    private val store = SlateSettingsStore(application)

    /**
     * The app's own copies of the logs it has been given, and the dives it
     * remembers being taken into the editor.
     *
     * Owned here rather than by the activity because the start screen draws
     * from them: the list has to survive the rotation that rebuilds the
     * activity, exactly like everything else on this object.
     */
    val cache = LogCache(File(application.filesDir, "logs"))

    val recents = RecentDives(File(application.filesDir, "recent.index"), cache)

    /** What the start screen offers as a way back in. Re-read, never guessed. */
    var recent by mutableStateOf(recents.entries())
        private set

    /** What the copies occupy, for the one line that admits the cost. */
    var recentBytes by mutableStateOf(recents.bytes())
        private set

    private fun refreshRecent() {
        recent = recents.entries()
        recentBytes = recents.bytes()
    }

    /** Forget every remembered dive, and every log copy behind them. */
    fun clearRecent() {
        recents.clear()
        refreshRecent()
    }

    /** What has been loaded, and what went wrong loading it. */
    var logs by mutableStateOf<LoadState>(LoadState.Empty)

    /**
     * The look the editor is currently working in.
     *
     * Seeded from the saved default, so the app opens in the state the user
     * last asked it to remember. Changes made in the editor stay here for the
     * rest of the session — opening a second dive keeps them — and reach the
     * store only when the user saves them as the default.
     */
    var settings by mutableStateOf(store.load()?.normalised() ?: SlateSettings.FACTORY)
        private set

    /**
     * The look a *particular* dive is being edited in, when it has one of its
     * own.
     *
     * Set when a dive is opened from the recent list, which restores the look
     * that dive was last given — reopening a slate ought to reproduce the
     * slate. It is deliberately **not** [settings]: letting it become the
     * session look would mean that opening an old dive silently changed what
     * every dive after it opened with, which is the same failure the editor
     * being a scratchpad exists to prevent. Cleared on the way out of the
     * editor.
     */
    var editorSettings by mutableStateOf<SlateSettings?>(null)
        private set

    /** What the editor should actually show: the dive's look, or the session's. */
    val effectiveSettings: SlateSettings get() = editorSettings ?: settings

    /** A change made by a control, applied to whichever of the two is in force. */
    fun changeSettings(value: SlateSettings) {
        if (editorSettings != null) editorSettings = value else settings = value
    }

    /**
     * Remember these dives, in the look currently on screen.
     *
     * Called on the way into the editor and again on the way out, so what is
     * stored is the look the user actually left the dive in. A dive whose log
     * has no cached copy is skipped rather than recorded: the row would be one
     * that cannot be opened, which is worse than no row at all.
     */
    fun rememberEditing(dives: List<EditingDive>) {
        var wrote = false
        for (it in dives) {
            if (!cache.has(it.logName)) continue
            recents.record(it.logName, it.dive, it.position, effectiveSettings)
            wrote = true
        }
        if (wrote) refreshRecent()
    }

    /** Leaving the editor: the dive's own look stops applying. */
    fun closeEditor() {
        editorSettings = null
    }

    /** Open a remembered dive in the look it was left in. */
    fun openInLook(look: SlateSettings) {
        editorSettings = look.normalised()
    }

    /** Drop a remembered dive whose log turned out to be unreadable. */
    fun forget(entry: RecentDives.Entry) {
        recents.forget(entry)
        refreshRecent()
    }

    /** The saved default, or null while the user has never saved one. */
    var savedDefault by mutableStateOf(store.load()?.normalised())
        private set

    var exports by mutableStateOf<ExportState>(ExportState.Idle)

    var updates by mutableStateOf<UpdateState>(UpdateState.Idle)

    /**
     * Whether the launching intent has already been read.
     *
     * The activity is recreated on a rotation with the same intent still
     * attached, and reading it again would parse the shared file a second time
     * and stack a second copy of every dive under a second heading. Held here
     * rather than keyed on `savedInstanceState`, so that a genuinely new
     * process — where this is false again and the state above is empty — still
     * opens what it was launched with.
     */
    var handledIntent = false

    /**
     * An intent waiting for a screen to launch it from.
     *
     * The chooser and the installer have to be started by an activity, and the
     * work that produces them now runs on a scope that outlives one. Parking the
     * intent here means a share that finishes rendering during a rotation waits
     * for the new activity instead of being dropped on the destroyed one.
     */
    var pendingLaunch by mutableStateOf<Launch?>(null)

    /** An intent, and what to say if Android will not take it. */
    data class Launch(
        val intent: Intent,
        val onFailure: (Throwable) -> Unit,
        val id: Long = System.nanoTime(),
    )

    fun launch(intent: Intent, onFailure: (Throwable) -> Unit) {
        pendingLaunch = Launch(intent, onFailure)
    }

    /** Remember the current look as what the app opens with. */
    fun saveAsDefault() {
        val normalised = effectiveSettings.normalised()
        settings = normalised
        editorSettings = null
        store.save(normalised)
        savedDefault = normalised
    }

    /** Go back to the saved default, discarding this session's fiddling. */
    fun restoreSavedDefault() {
        savedDefault?.let { settings = it; editorSettings = null }
    }

    /**
     * Back to what the app shipped with, and forget the saved default.
     *
     * Both halves, because "factory" that leaves a saved default in place would
     * put the old look back on the next launch — which is exactly the state
     * someone reaching for this button is trying to leave.
     */
    fun restoreFactoryDefaults() {
        settings = SlateSettings.FACTORY
        editorSettings = null
        store.clear()
        savedDefault = null
    }
}
