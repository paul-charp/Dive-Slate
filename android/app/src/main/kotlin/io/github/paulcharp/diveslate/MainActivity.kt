package io.github.paulcharp.diveslate

import android.content.ClipData
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewModelScope
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.ParseException
import io.github.paulcharp.diveslate.core.parseText
import io.github.paulcharp.diveslate.core.renderOverlay
import io.github.paulcharp.diveslate.ui.DiveSlateApp
import io.github.paulcharp.diveslate.ui.ExportState
import io.github.paulcharp.diveslate.ui.LoadState
import io.github.paulcharp.diveslate.ui.Notice
import io.github.paulcharp.diveslate.ui.UpdateState
import io.github.paulcharp.diveslate.ui.Updates
import io.github.paulcharp.diveslate.ui.withMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single activity: receive one or more dive logs, preview the slate, export it.
 *
 * The flow is deliberately short. From tapping Export in Subsurface-mobile to a
 * finished slate should be about three taps, so the only decision on the path is
 * which dive — or which dives, when the export holds more than one and the user
 * wants a slate for several. Everything else is a setting with a remembered
 * default.
 */
class MainActivity : ComponentActivity() {

    /**
     * Everything the app is in the middle of, kept out of the activity.
     *
     * Held in a ViewModel because an activity is destroyed and rebuilt by every
     * configuration change: with these as fields, rotating the phone put the
     * user back on the start screen with the open dive log gone. See
     * [SessionState] — the coroutines below run on its scope for the same
     * reason, so an export in progress survives the same rotation.
     */
    private val session: SessionState by viewModels()

    /**
     * The three states below read and write [session]. Kept as properties so
     * the methods in this file — which is otherwise unchanged by where the
     * state lives — still read as they did.
     */
    private var state: LoadState
        get() = session.logs
        set(value) { session.logs = value }

    private var updateState: UpdateState
        get() = session.updates
        set(value) { session.updates = value }

    private var exportState: ExportState
        get() = session.exports
        set(value) { session.exports = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Content runs under the system bars, which is how an Android app has
        // looked since 15 and is mandatory from targetSdk 36 anyway. The
        // compose side takes the insets per screen, so an app bar can paint its
        // own colour behind the status bar instead of stopping short of it.
        //
        // Both bars are on auto, which picks its icon colour from the system's
        // light/dark setting. That is right again now that the app follows the
        // same setting; it was wrong for as long as the app was pinned dark,
        // where a light-themed phone gave dark clock and battery glyphs on a
        // near-black bar. The two have to be decided together — whichever way
        // the shell goes, the bar icons go with it — so if the app is ever
        // pinned to one mode again, these get pinned to match.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Guarded, and before setContent: an unexpected intent must not kill
        // the activity before there is a screen on which to say so. A share
        // that launches nothing looks identical to a share that did nothing.
        //
        // Once per intent, not once per activity. A rotation rebuilds the
        // activity with the same intent still attached, and reading it again
        // would parse the shared file a second time and list every dive twice.
        if (!session.handledIntent) {
            session.handledIntent = true
            runCatching { handleIntent(intent) }.onFailure {
                state = LoadState.Failed(
                    "Could not open that share: ${it.message ?: it::class.simpleName}"
                )
            }
        }
        setContent {
            // The chooser and the installer are produced by work that outlives
            // this activity, so they are parked in the session and launched
            // from whichever activity is on screen when they turn up.
            val pending = session.pendingLaunch
            LaunchedEffect(pending?.id) {
                pending?.let {
                    session.pendingLaunch = null
                    runCatching { startActivity(it.intent) }.onFailure(it.onFailure)
                }
            }

            DiveSlateApp(
                state = state,
                settings = session.settings,
                savedDefault = session.savedDefault,
                onSettings = { session.settings = it },
                onSaveDefault = {
                    session.saveAsDefault()
                    state = state.withMessage("Saved as your default look")
                },
                onRestoreDefault = { session.restoreSavedDefault() },
                onRestoreFactory = { session.restoreFactoryDefaults() },
                updates = Updates(
                    state = updateState,
                    onCheck = { checkForUpdate(announce = true) },
                    onDownload = { release -> downloadUpdate(release) },
                    onInstall = { ready -> installUpdate(ready.release, ready.apk) },
                    onDismiss = { updateState = UpdateState.Idle },
                ),
                exports = exportState,
                onLoadSample = { loadBundledSample() },
                onOpenUris = { uris -> openPicked(uris) },
                onBack = { state = LoadState.Empty },
                onExport = { request -> shareSlates(request) },
                onSaveToGallery = { request -> saveToGallery(request) },
            )
        }

        // Last, and unannounced. Nothing above this line waits on the network,
        // so a phone with no signal opens a dive log exactly as fast as one on
        // wifi.
        if (UpdateCheck.isAutoCheckDue(this)) checkForUpdate(announce = false)
    }

    /** A share can arrive while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Take every dive log an incoming intent happens to carry.
     *
     * Deliberately exhaustive rather than assuming EXTRA_STREAM. There is no
     * one way to share a document on Android: the payload may be a stream
     * extra, the intent's own data URI, a ClipData item, or the file's text
     * inlined in EXTRA_TEXT. Subsurface's export is not documented and an
     * earlier version of this method handled only the first of those, which
     * made picking Dive Slate in the export chooser do nothing at all.
     *
     * Every file that parses is kept, rather than the first one. Sharing four
     * logbooks and being shown the contents of one — with nothing to say the
     * other three were dropped — is the same class of silent failure as the
     * missing ClipData was, just further along.
     *
     * And nothing here returns quietly. A share that arrives and produces no
     * visible result is the worst outcome — indistinguishable from a crash,
     * and impossible to report usefully — so an unusable intent ends on a
     * screen describing what actually turned up.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND &&
            action != Intent.ACTION_SEND_MULTIPLE &&
            action != Intent.ACTION_VIEW
        ) {
            return // a plain launcher start; there is nothing to open
        }

        val uris = buildList {
            intent.data?.let { add(it) }
            intent.streamExtra()?.let { add(it) }
            addAll(intent.streamExtras())
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
            }
        }.distinct()

        // Every attempt is recorded, not just the last. A share can carry both a
        // file and a scrap of text; reporting only the final failure let the
        // text attempt — which fails with a bare "unrecognised format" — mask
        // the detailed reason the actual file could not be read.
        val attempts = mutableListOf<String>()
        val logs = mutableListOf<DiveLog>()

        for (uri in uris) {
            try {
                logs += readLog(uri)
            } catch (e: Exception) {
                attempts += "• file ${uri.scheme}:…/${uri.lastPathSegment ?: "?"}\n${describe(e)}"
            }
        }

        // Some apps inline the document instead of handing over a file. Only
        // consulted when no file was readable: a share carrying both is
        // carrying one document twice, and loading it twice would put the same
        // dives in the list under two headings.
        if (logs.isEmpty()) {
            val inlined = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
            if (!inlined.isNullOrBlank()) {
                try {
                    logs += parseText(inlined, source = "shared text")
                } catch (e: Exception) {
                    attempts += "• inline text, ${inlined.length} chars\n" +
                        "${describe(e)}\nstarts with: ${inlined.take(120)}"
                }
            }
        }

        state = when {
            logs.isEmpty() -> LoadState.Failed(
                if (attempts.isEmpty()) describeShare(intent, uris)
                else attempts.joinToString("\n\n")
            )
            // Partly readable. The detail of each failure is too long for a
            // snackbar, but the count is the part that cannot be inferred from
            // the screen — a list of three files when four were shared looks
            // entirely normal.
            attempts.isNotEmpty() -> LoadState.Loaded(
                logs,
                Notice(
                    "Opened ${logs.size} of ${logs.size + attempts.size} files — " +
                        "the rest could not be read"
                ),
            )
            else -> LoadState.Loaded(logs)
        }
    }

    /**
     * What arrived, when none of it was usable.
     *
     * Verbose on purpose. This text is the only diagnostic available when the
     * handover fails on someone else's phone against an app whose sharing
     * behaviour is undocumented.
     */
    private fun describeShare(intent: Intent, uris: List<Uri>): String = buildString {
        append("Nothing readable arrived in that share.\n\n")
        append("action: ${intent.action ?: "none"}\n")
        append("type: ${intent.type ?: "none"}\n")
        append("uris: ${uris.size}\n")
        append("stream extra: ${if (intent.hasExtra(Intent.EXTRA_STREAM)) "yes" else "no"}\n")
        append("text extra: ${if (intent.hasExtra(Intent.EXTRA_TEXT)) "yes" else "no"}\n")
        append("clip items: ${intent.clipData?.itemCount ?: 0}\n")
        val keys = intent.extras?.keySet()?.joinToString().orEmpty()
        append("extras: ${keys.ifEmpty { "none" }}")
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtras(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()

    /**
     * Read the shared document immediately and keep our own copy.
     *
     * The permission granted with a `content://` URI dies with the activity that
     * received it, so stashing the URI to open later is a guaranteed bug. The
     * bytes are copied now, which also gives the app a small history to re-render
     * from without going back to Subsurface for another export.
     */
    private fun readLog(uri: Uri): DiveLog {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw ParseException("could not open the shared file")

        val name = displayName(uri)
        File(filesDir, "logs").mkdirs()
        val saved = File(filesDir, "logs/${System.currentTimeMillis()}-${name ?: "log.ssrf"}")
        runCatching { saved.writeText(text) }

        // Detection is by content: a shared URI frequently carries no usable
        // filename, and both formats routinely arrive as plain .xml anyway.
        val log = try {
            parseText(text, hint = name, source = name)
        } catch (e: ParseException) {
            // Show what actually turned up. "Unrecognised format" on its own is
            // useless when the file came from another app over a content URI
            // that may not be what anyone expected — an empty read, an archive,
            // or a wrapper all look identical from here.
            throw ParseException(
                buildString {
                    append(e.message ?: "unrecognised dive log format")
                    append("\n\nname: ${name ?: "none"}")
                    append("\nmime: ${contentResolver.getType(uri) ?: "none"}")
                    append("\nchars: ${text.length}")
                    append("\n\nit starts with:\n")
                    append(
                        if (text.isBlank()) "(nothing — the file read as empty)"
                        else text.take(240).replace(' ', '.')
                    )
                }
            )
        }

        // A well-formed log with nothing in it. Subsurface will happily export
        // an empty dive list, and the result parses perfectly — there is simply
        // nothing to draw. Refusing here says so plainly; letting it through
        // left the editor to index into an empty list.
        if (log.dives.isEmpty()) {
            throw ParseException("that log parsed fine, but it contains no dives")
        }
        return log
    }

    /**
     * What to call the file behind a content URI.
     *
     * Asked of the provider rather than read off the URI. The last path segment
     * is a document id — Downloads hands over `msf:44` — and using it named the
     * file headings in the dive list after the provider's internal numbering,
     * which tells the user nothing about which logbook they are looking at. It
     * also fed the format hint, so a real `.ssrf` extension was being thrown
     * away before detection ever saw it.
     *
     * The URI is still the fallback, because a provider is not obliged to
     * answer and a name is a nicety: everything downstream of this already
     * copes with having none.
     */
    private fun displayName(uri: Uri): String? {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Open logs the user picked out of their files.
     *
     * Same path as a share: read now, keep our own copy, sniff the content. The
     * picker offers every file type because a Subsurface export has no MIME type
     * of its own — filtering would hide the very files this is for — so anything
     * can arrive here and being refused clearly is part of the job.
     *
     * Several files at once, because a logbook split by year or by trip is a
     * normal thing to have and picking them one at a time would mean losing the
     * previous one each time.
     */
    private fun openPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val logs = mutableListOf<DiveLog>()
        val problems = mutableListOf<String>()
        for (uri in uris) {
            try {
                logs += readLog(uri)
            } catch (e: Exception) {
                problems += describe(e)
            }
        }

        state = when {
            logs.isEmpty() -> LoadState.Failed(problems.joinToString("\n\n"))
            problems.isEmpty() -> LoadState.Loaded(logs)
            else -> LoadState.Loaded(
                logs,
                Notice("Opened ${logs.size} of ${uris.size} files — the rest could not be read"),
            )
        }
    }

    private fun loadBundledSample() {
        state = try {
            val text = assets.open("sample.ssrf").use { it.readBytes().decodeToString() }
            LoadState.Loaded(listOf(parseText(text, hint = "sample.ssrf", source = "sample.ssrf")))
        } catch (e: Exception) {
            LoadState.Failed(describe(e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ParseException -> e.message ?: "this file is not a dive log we understand"
        else -> "could not read that file: ${e.message ?: e::class.simpleName}"
    }

    /**
     * Draw every dive in the request, at export density.
     *
     * Rendering is done here rather than in the editor so that it lands on the
     * background thread with the rasterising. A dive that will not render is
     * recorded and skipped rather than aborting the batch: nineteen slates and
     * a note about the twentieth is a better outcome than nothing at all, and
     * the alternative — stopping — would make one odd dive in a long selection
     * cost the whole export.
     */
    private fun slatesFor(request: ExportRequest): Pair<List<SlateExport>, List<String>> {
        val names = SlateFiles.exportNames(request.dives)
        val exports = mutableListOf<SlateExport>()
        val problems = mutableListOf<String>()
        for ((i, dive) in request.dives.withIndex()) {
            try {
                exports += SlateExport(
                    renderOverlay(dive, request.options),
                    SlateFiles.EXPORT_SCALE,
                    names[i],
                )
            } catch (e: Exception) {
                problems += "${dive.title}: ${e.message ?: e::class.simpleName}"
            }
        }
        return exports to problems
    }

    /**
     * The first thing that went wrong, and how much else did.
     *
     * Only the leading reason is quoted. A batch usually fails for one reason —
     * the same missing figure, the same full disk — so listing twenty variants
     * of it in a snackbar buries the count, which is the part that cannot be
     * read off the screen.
     */
    private fun firstProblem(problems: List<String>): String =
        problems.first() + if (problems.size > 1) " (and ${problems.size - 1} more)" else ""

    /**
     * Save the slates to the gallery as transparent PNGs.
     *
     * Separate from the share path on purpose: the gallery is where the PNG
     * stays put, and wanting it there rather than immediately handing it to
     * another app is a normal thing to want.
     *
     * All of it on a background thread, and one slate at a time. At export
     * density a single bitmap is tens of megabytes and takes a visible moment
     * to compress; this used to run inline on the click, which was a stutter
     * for one dive and would be an ANR for twenty.
     */
    private fun saveToGallery(request: ExportRequest) {
        if (exportState is ExportState.Running) return
        val total = request.dives.size
        exportState = ExportState.Running("Saving", 0, total)

        session.viewModelScope.launch {
            val (saved, problems) = withContext(Dispatchers.IO) {
                val (exports, rendering) = slatesFor(request)
                val failures = rendering.toMutableList()
                var written = 0
                for ((i, export) in exports.withIndex()) {
                    exportState = ExportState.Running("Saving", i, total)
                    try {
                        SlateFiles.saveToGallery(applicationContext, export)
                        written++
                    } catch (e: Exception) {
                        failures += "${export.name}: ${e.message ?: e::class.simpleName}"
                    }
                }
                written to failures.toList()
            }
            exportState = ExportState.Idle
            // Confirmed explicitly. Writing through MediaStore is silent and the
            // files land in an album the user is not looking at, so without this
            // a successful save is indistinguishable from a button that did
            // nothing.
            val where = "Pictures › Dive Slate"
            state = state.withMessage(
                when {
                    problems.isNotEmpty() ->
                        "Saved $saved of $total to $where. ${firstProblem(problems)}"
                    total == 1 -> "Saved to $where"
                    else -> "Saved $saved slates to $where"
                }
            )
        }
    }

    /**
     * Hand the slates to the system share sheet.
     *
     * Deliberately not addressed to any one app. This used to fire Instagram's
     * `ADD_TO_STORY` directly, which made the button a bet on which app the
     * user wanted — and it silently degraded to a chooser anyway whenever
     * Instagram was absent. A transparent PNG is useful in a video editor, a
     * message, or a notes app, and the chooser is what lets the user say so.
     *
     * The URIs travel in an extra, and extras are *not* walked by the automatic
     * grant that `addFlags` performs on an intent's data. Putting them in
     * [Intent.setClipData] as well is what actually carries the read permission
     * to whichever app the user picks; without it the receiver gets URIs it is
     * not allowed to open, which fails at the far end where it cannot be
     * diagnosed. Every URI goes in, not just the first — a batch where only the
     * leading image opens would fail in exactly that undiagnosable way.
     */
    private fun shareSlates(request: ExportRequest) {
        if (exportState is ExportState.Running) return
        val total = request.dives.size
        exportState = ExportState.Running("Preparing", 0, total)

        session.viewModelScope.launch {
            val (uris, problems) = withContext(Dispatchers.IO) {
                val (exports, rendering) = slatesFor(request)
                val failures = rendering.toMutableList()
                SlateFiles.clearSlateCache(applicationContext)
                val written = mutableListOf<Uri>()
                for ((i, export) in exports.withIndex()) {
                    exportState = ExportState.Running("Preparing", i, total)
                    try {
                        written += SlateFiles.writePng(applicationContext, export)
                    } catch (e: Exception) {
                        failures += "${export.name}: ${e.message ?: e::class.simpleName}"
                    }
                }
                written.toList() to failures.toList()
            }
            exportState = ExportState.Idle

            if (uris.isEmpty()) {
                state = state.withMessage(
                    "Could not prepare the slate: ${problems.firstOrNull() ?: "no slate was drawn"}"
                )
                return@launch
            }
            if (problems.isNotEmpty()) {
                state = state.withMessage(
                    "Sharing ${uris.size} of $total. ${firstProblem(problems)}"
                )
            }
            // Through the session rather than started here: this coroutine now
            // outlives the activity that began it, and a chooser started on a
            // destroyed one is a share that silently never appears.
            session.launch(Intent.createChooser(shareIntent(uris), "Share slate")) { e ->
                state = state.withMessage(
                    "Could not open the share sheet: ${e.message ?: e::class.simpleName}"
                )
            }
        }
    }

    /**
     * One image or several, in the form each case expects.
     *
     * `ACTION_SEND` for a single slate rather than a one-item `SEND_MULTIPLE`:
     * plenty of receivers handle only the former, and a batch of one is the
     * ordinary case this app was built for.
     */
    private fun shareIntent(uris: List<Uri>): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newUri(contentResolver, "Dive slate", uris[0])
        for (extra in uris.drop(1)) clip.addItem(ClipData.Item(extra))
        return intent.apply {
            type = "image/png"
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Ask GitHub whether a newer build exists.
     *
     * [announce] separates the two reasons to be here. Asked for, every outcome
     * is worth showing, including "you already have the latest" and whatever went
     * wrong. Run by itself once a day, only an actual update is — nobody wants a
     * banner reporting a failed background request they never made, and a daily
     * "up to date" is noise about a thing that changes a few times a year.
     *
     * The timestamp is written whatever happens, including on failure. A phone in
     * a dive centre with no usable wifi would otherwise retry on every single
     * launch.
     */
    private fun checkForUpdate(announce: Boolean) {
        if (updateState is UpdateState.Checking) return
        if (announce) updateState = UpdateState.Checking

        session.viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { UpdateCheck.check() } }
            UpdateCheck.markChecked(applicationContext)
            outcome
                .onSuccess { release ->
                    updateState = when {
                        release != null -> UpdateState.Available(release)
                        announce -> UpdateState.UpToDate
                        else -> UpdateState.Idle
                    }
                }
                .onFailure { e ->
                    updateState = if (announce) {
                        UpdateState.Failed(e.message ?: e::class.simpleName.orEmpty())
                    } else {
                        UpdateState.Idle
                    }
                }
        }
    }

    /**
     * Fetch the APK, then verify it before anyone is asked to install it.
     *
     * The progress figure is updated from the download thread and read by
     * Compose; that is safe because it only ever writes [updateState], and a
     * dropped intermediate value costs a repaint of a progress bar. The export
     * progress above works the same way and for the same reason.
     */
    private fun downloadUpdate(release: UpdateCheck.Release) {
        updateState = UpdateState.Downloading(release, 0f)
        session.viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateCheck.download(applicationContext, release) { fraction ->
                        updateState = UpdateState.Downloading(release, fraction)
                    }
                }
            }
            outcome
                .onSuccess { apk ->
                    updateState = UpdateState.Ready(release, apk)
                    // Straight into the installer, since the download was an
                    // explicit request and stopping to ask again would be a tap
                    // for nothing. The Install button stays for the case below,
                    // where the first attempt cannot proceed yet.
                    installUpdate(release, apk)
                }
                .onFailure { e ->
                    updateState = UpdateState.Failed(
                        e.message ?: "the download failed (${e::class.simpleName})",
                    )
                }
        }
    }

    /**
     * Hand the verified APK to Android's installer.
     *
     * Installing from outside a store is a permission the user grants in
     * Settings, per app, and it cannot be requested with a runtime prompt. So the
     * first attempt on a fresh install typically cannot proceed: that is not an
     * error, it is a detour, and the banner keeps its Install button for the
     * return trip. Sending them to the right Settings page and saying why is the
     * whole of the handling.
     */
    private fun installUpdate(release: UpdateCheck.Release, apk: File) {
        // Reported through updateState, never through the load state. Anything
        // routed via LoadState.withMessage becomes LoadState.Failed unless a log
        // is already open, which would replace the whole screen with the "that
        // did not load" page over a message about an install.
        if (!UpdateCheck.canInstall(applicationContext)) {
            updateState = UpdateState.Ready(
                release,
                apk,
                note = "Allow Dive Slate to install apps, then tap Install again",
            )
            session.launch(UpdateCheck.unknownSourcesSettings(applicationContext)) {
                updateState = UpdateState.Ready(
                    release,
                    apk,
                    note = "This phone offers no page for allowing installs from " +
                        "an app, so the APK has to be opened from your files",
                )
            }
            return
        }
        session.launch(UpdateCheck.installIntent(applicationContext, apk)) { e ->
            updateState = UpdateState.Failed(
                "Android would not open the installer: ${e.message ?: e::class.simpleName}",
            )
        }
    }
}
