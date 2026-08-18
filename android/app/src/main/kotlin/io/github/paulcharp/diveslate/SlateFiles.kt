package io.github.paulcharp.diveslate

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.Slate
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A slate, the density it should be rasterised at, and the name it saves under. */
data class SlateExport(val slate: Slate, val scale: Float, val name: String)

/**
 * A batch waiting to be drawn: the dives, and the one settings object every one
 * of them is drawn with.
 *
 * Unrendered on purpose. The editor hands over dives rather than slates so that
 * building the display lists happens on the same background thread as the
 * rasterising — a hundred-dive selection would otherwise spend its first second
 * blocking the very click that started it, before the progress bar it is meant
 * to fill has drawn once.
 */
data class ExportRequest(val dives: List<Dive>, val options: OverlayOptions)

object SlateFiles {

    /**
     * Whatever pixels the PNG has are all the receiving app has to work with —
     * a story editor drops it at its own size and lets the viewer pinch from
     * there, and a video editor scales it to the timeline. Three times the
     * layout size leaves enough headroom that scaling up does not soften the
     * type.
     */
    const val EXPORT_SCALE = 3f

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Filenames for a batch: when it was exported, then which dive each one is.
     *
     * One timestamp for the whole batch rather than one per file. Twelve slates
     * written in the same second would otherwise carry twelve near-identical
     * stamps that sort them apart for no reason; sharing one stamp makes a batch
     * a block in a filename-sorted gallery, and the dive number and site are
     * what tell its members apart.
     *
     * That puts the whole burden of uniqueness on the dive, which is not enough
     * on its own — a log shared twice, or two files holding the same dive,
     * produces two identical names. Repeats are therefore numbered here, where
     * it happens deliberately, rather than left to MediaStore's own
     * de-duplication to resolve out of sight.
     */
    fun exportNames(dives: List<Dive>, at: LocalDateTime = LocalDateTime.now()): List<String> {
        val stamp = at.format(STAMP)
        val used = mutableMapOf<String, Int>()
        return dives.map { dive ->
            val base = buildString {
                append("diveslate-")
                append(stamp)
                dive.number?.let { append("-").append(it) }
                slug(dive.site)?.let { append("-").append(it) }
            }
            val seen = used.merge(base, 1, Int::plus)!!
            if (seen == 1) base else "$base-$seen"
        }
    }

    /**
     * A site name reduced to something a filesystem and a gallery both accept.
     *
     * Capped, because dive sites are written by hand and run long — "Shark and
     * Yolanda Reef, Ras Mohammed, from the north" is a real shape of entry, and
     * a filename carrying all of it gets truncated by whatever displays it,
     * which is worse than truncating it here where the dive number still
     * survives at the front.
     */
    private fun slug(site: String?): String? = site
        ?.replace(Regex("[^A-Za-z0-9]+"), "-")
        ?.take(40)
        ?.trim('-')
        ?.lowercase()
        ?.ifEmpty { null }

    /**
     * Rasterise and write a transparent PNG, returning a shareable URI.
     *
     * PNG, not JPEG, and never flattened onto a background: the alpha channel is
     * the entire point. A slate exported with an opaque backing would cover the
     * footage it is meant to sit on.
     */
    fun writePng(context: Context, export: SlateExport): Uri {
        val dir = File(context.cacheDir, "slates").apply { mkdirs() }
        val file = File(dir, "${export.name}.png")

        rasterise(export) { bitmap ->
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * The hand-off directory for a share, emptied before the batch fills it.
     *
     * This used to be a single file overwritten on every export, which kept the
     * cache from growing without anyone having to remember to sweep it. A batch
     * needs a file per slate and so cannot do that, which turns what was
     * implicit into this. It runs before a batch rather than after on purpose:
     * the previous share's files are the ones nobody is looking at any more,
     * whereas the ones just handed out may still be being read by whichever app
     * took them.
     */
    fun clearSlateCache(context: Context) {
        val dir = File(context.cacheDir, "slates")
        dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * Save a transparent PNG into the gallery, under Pictures/Dive Slate.
     *
     * Written through MediaStore rather than to a path, so no storage
     * permission is needed and the file is indexed immediately. IS_PENDING
     * holds it invisible until the bytes are actually there — without it a
     * gallery scan can pick up a half-written image.
     *
     * PNG at full quality, never JPEG: flattening would fill the transparent
     * region with black and the slate would stop being an overlay.
     */
    fun saveToGallery(context: Context, export: SlateExport): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${export.name}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Dive Slate",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("the gallery refused a new image")

        try {
            rasterise(export) { bitmap ->
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: throw IllegalStateException("could not open the new image for writing")
            }
        } catch (e: Exception) {
            // Leave no invisible half-written entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Draw one slate, hand the pixels to [use], and free them.
     *
     * A slate at export density is some thirty megabytes of ARGB_8888, so the
     * recycle is not housekeeping — it is what makes a batch possible at all.
     * Exactly one is alive at a time, which is also why a batch is written in
     * sequence rather than in parallel: four threads here means four bitmaps,
     * and the fourth is an OutOfMemoryError on a modest phone.
     */
    private inline fun rasterise(export: SlateExport, use: (Bitmap) -> Unit) {
        val bitmap = SlatePainter.toBitmap(export.slate, export.scale)
        try {
            use(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
