package io.github.paulcharp.diveslate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.ParseException
import io.github.paulcharp.diveslate.core.parseText
import io.github.paulcharp.diveslate.ui.DiveSlateApp
import io.github.paulcharp.diveslate.ui.LoadState
import io.github.paulcharp.diveslate.ui.withMessage
import java.io.File

/**
 * Single activity: receive a dive log, preview the slate, hand it to Instagram.
 *
 * The flow is deliberately short. From tapping Export in Subsurface-mobile to
 * Instagram opening with the slate in place should be about three taps, so the
 * only decision on the path is which dive — and only when the export holds more
 * than one. Everything else is a setting with a remembered default.
 */
class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<LoadState>(LoadState.Empty)

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
                onLoadSample = { loadBundledSample() },
                onOpenUri = { uri -> openPicked(uri) },
                onBack = { state = LoadState.Empty },
                onExport = { slate, background -> exportToInstagram(slate, background) },
                onSaveToGallery = { slate, title -> saveToGallery(slate, title) },
            )
        }
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

        var failure: Exception? = null
        for (uri in uris) {
            try {
                state = LoadState.Loaded(readLog(uri))
                return
            } catch (e: Exception) {
                failure = e
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
                failure = e
            }
        }

        state = LoadState.Failed(failure?.let { describe(it) } ?: describeShare(intent, uris))
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
        val log = parseText(text, hint = name, source = name)

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
     * Separate from the Instagram path on purpose: the PNG is the thing this
     * project actually produces, and wanting it in an editor — or in any app
     * other than the one the export button names — is a normal thing to want.
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
     * Hand the slate to Instagram as a story sticker.
     *
     * Two things here are easy to get wrong and fail silently. The asset URI
     * travels in an extra, and extras are not walked by the automatic grant that
     * `addFlags` performs on the intent's data — so the permission is granted
     * explicitly. And without the `<queries>` entry in the manifest, Android 11+
     * reports Instagram as absent whether or not it is installed.
     */
    private fun exportToInstagram(slate: SlateExport, background: Pair<Long, Long>) {
        val uri = try {
            SlateFiles.writePng(this, slate)
        } catch (e: Exception) {
            state = state.withMessage("could not save the slate: ${e.message}")
            return
        }

        val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setPackage(INSTAGRAM_PACKAGE)
            putExtra("interactive_asset_uri", uri)
            putExtra("top_background_color", background.first.asCssColour())
            putExtra("bottom_background_color", background.second.asCssColour())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(packageManager) == null) {
            // Fall back to a plain share so the slate is still usable — and so
            // the emulator, where Instagram is usually absent, is not a dead end.
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            state = state.withMessage("Instagram is not installed — sharing the PNG instead")
            startActivity(Intent.createChooser(fallback, "Share slate"))
            return
        }

        grantUriPermission(INSTAGRAM_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }
}

private fun Long.asCssColour(): String = "#%06X".format(this and 0xFFFFFFL)
