package io.github.paulcharp.diveslate

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.paulcharp.diveslate.ui.ExportState
import io.github.paulcharp.diveslate.ui.LoadState
import io.github.paulcharp.diveslate.ui.UpdateState

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
        val normalised = settings.normalised()
        settings = normalised
        store.save(normalised)
        savedDefault = normalised
    }

    /** Go back to the saved default, discarding this session's fiddling. */
    fun restoreSavedDefault() {
        savedDefault?.let { settings = it }
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
        store.clear()
        savedDefault = null
    }
}
