package io.github.paulcharp.diveslate

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.ParseException
import io.github.paulcharp.diveslate.core.parseText
import io.github.paulcharp.diveslate.ui.DiveSlateApp
import io.github.paulcharp.diveslate.ui.LoadState
import io.github.paulcharp.diveslate.ui.UpdateState
import io.github.paulcharp.diveslate.ui.Updates
import io.github.paulcharp.diveslate.ui.withMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single activity: receive a dive log, preview the slate, export it.
 *
 * The flow is deliberately short. From tapping Export in Subsurface-mobile to a
 * finished slate should be about three taps, so the only decision on the path is
 * which dive — and only when the export holds more than one. Everything else is
 * a setting with a remembered default.
 */
class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<LoadState>(LoadState.Empty)
    private var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guarded, and before setContent: an unexpected intent must not kill
        // the activity before there is a screen on which to say so. A share
        // that launches nothing looks identical to a share that did nothing.
        runCatching { handleIntent(intent) }.onFailure {
            state = LoadState.Failed(
                "Could not open that share: ${it.message ?: it::class.simpleName}"
            )
        }
        setContent {
            DiveSlateApp(
                state = state,
                updates = Updates(
                    state = updateState,
                    onCheck = { checkForUpdate(announce = true) },
                    onOpen = { release -> openRelease(release) },
                    onDismiss = { updateState = UpdateState.Idle },
                ),
                onLoadSample = { loadBundledSample() },
                onOpenUri = { uri -> openPicked(uri) },
                onBack = { state = LoadState.Empty },
                onExport = { slate -> shareSlate(slate) },
                onSaveToGallery = { slate, title -> saveToGallery(slate, title) },
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
     * Take a dive log from whatever an incoming intent happens to carry.
     *
     * Deliberately exhaustive rather than assuming EXTRA_STREAM. There is no
     * one way to share a document on Android: the payload may be a stream
     * extra, the intent's own data URI, a ClipData item, or the file's text
     * inlined in EXTRA_TEXT. Subsurface's export is not documented and an
     * earlier version of this method handled only the first of those, which
     * made picking Dive Slate in the export chooser do nothing at all.
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

        for (uri in uris) {
            try {
                state = LoadState.Loaded(readLog(uri))
                return
            } catch (e: Exception) {
                attempts += "• file ${uri.scheme}:…/${uri.lastPathSegment ?: "?"}\n${describe(e)}"
            }
        }

        // Some apps inline the document instead of handing over a file.
        val inlined = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        if (!inlined.isNullOrBlank()) {
            try {
                state = LoadState.Loaded(parseText(inlined, source = "shared text"))
                return
            } catch (e: Exception) {
                attempts += "• inline text, ${inlined.length} chars\n" +
                    "${describe(e)}\nstarts with: ${inlined.take(120)}"
            }
        }

        state = LoadState.Failed(
            if (attempts.isEmpty()) describeShare(intent, uris)
            else attempts.joinToString("\n\n")
        )
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

        val name = uri.lastPathSegment?.substringAfterLast('/')
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
     * Open a log the user picked out of their files.
     *
     * Same path as a share: read now, keep our own copy, sniff the content. The
     * picker offers every file type because a Subsurface export has no MIME type
     * of its own — filtering would hide the very files this is for — so anything
     * can arrive here and being refused clearly is part of the job.
     */
    private fun openPicked(uri: Uri) {
        state = try {
            LoadState.Loaded(readLog(uri))
        } catch (e: Exception) {
            LoadState.Failed(describe(e))
        }
    }

    private fun loadBundledSample() {
        state = try {
            val text = assets.open("sample.ssrf").use { it.readBytes().decodeToString() }
            LoadState.Loaded(parseText(text, hint = "sample.ssrf", source = "sample.ssrf"))
        } catch (e: Exception) {
            LoadState.Failed(describe(e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ParseException -> e.message ?: "this file is not a dive log we understand"
        else -> "could not read that file: ${e.message ?: e::class.simpleName}"
    }

    /**
     * Save the slate to the gallery as a transparent PNG.
     *
     * Separate from the share path on purpose: the gallery is where the PNG
     * stays put, and wanting it there rather than immediately handing it to
     * another app is a normal thing to want.
     */
    private fun saveToGallery(export: SlateExport, title: String) {
        state = try {
            SlateFiles.saveToGallery(this, export, title)
            // Confirmed explicitly. Writing through MediaStore is silent and the
            // file lands in an album the user is not looking at, so without this
            // a successful save is indistinguishable from a button that did
            // nothing.
            state.withMessage("Saved to Pictures › Dive Slate")
        } catch (e: Exception) {
            state.withMessage("Could not save: ${e.message ?: e::class.simpleName}")
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

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { UpdateCheck.check() } }
            UpdateCheck.markChecked(this@MainActivity)
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
     * Hand the release page to the browser.
     *
     * The whole of what the app does about an update now. It downloaded and
     * installed the APK itself until 0.4.0, which needed REQUEST_INSTALL_PACKAGES
     * and got a correctly signed release classified as harmful — the reasoning
     * is on [UpdateCheck]. A browser is a far better-trusted unknown source than
     * this app will ever be.
     *
     * Reported through updateState, never through the load state: anything
     * routed via LoadState.withMessage becomes LoadState.Failed unless a log is
     * already open, which would replace the whole screen with the "that did not
     * load" page over a message about an update.
     */
    private fun openRelease(release: UpdateCheck.Release) {
        runCatching { startActivity(UpdateCheck.releaseIntent(release)) }
            .onFailure {
                updateState = UpdateState.Failed(
                    "No app here opens web links. The release is at ${release.releaseUrl}",
                )
            }
    }

    /**
     * Hand the slate to the system share sheet.
     *
     * Deliberately not addressed to any one app. This used to fire Instagram's
     * `ADD_TO_STORY` directly, which made the button a bet on which app the
     * user wanted — and it silently degraded to a chooser anyway whenever
     * Instagram was absent. A transparent PNG is useful in a video editor, a
     * message, or a notes app, and the chooser is what lets the user say so.
     *
     * The URI travels in an extra, and extras are *not* walked by the automatic
     * grant that `addFlags` performs on an intent's data. Putting it in
     * [Intent.setClipData] as well is what actually carries the read permission
     * to whichever app the user picks; without it the receiver gets a URI it is
     * not allowed to open, which fails at the far end where it cannot be
     * diagnosed.
     */
    private fun shareSlate(slate: SlateExport) {
        val uri = try {
            SlateFiles.writePng(this, slate)
        } catch (e: Exception) {
            state = state.withMessage("could not save the slate: ${e.message}")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Dive slate", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share slate"))
    }
}
