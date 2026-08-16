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
        handleIntent(intent)
        setContent {
            DiveSlateApp(
                state = state,
                onLoadSample = { loadBundledSample() },
                onExport = { slate, background -> exportToInstagram(slate, background) },
            )
        }
    }

    /** A share can arrive while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.streamExtra()
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return

        state = try {
            LoadState.Loaded(readLog(uri))
        } catch (e: Exception) {
            LoadState.Failed(describe(e))
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

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
        return parseText(text, hint = name, source = name)
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
