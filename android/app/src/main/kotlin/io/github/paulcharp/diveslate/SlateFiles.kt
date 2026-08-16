package io.github.paulcharp.diveslate

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.paulcharp.diveslate.core.Slate
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A slate plus the density it should be rasterised at. */
data class SlateExport(val slate: Slate, val scale: Float)

object SlateFiles {

    /**
     * Instagram drops a sticker at its own size and lets the viewer pinch from
     * there, so whatever pixels the PNG has are all it has to work with. Three
     * times the layout size leaves enough headroom that scaling up does not
     * soften the type.
     */
    const val EXPORT_SCALE = 3f

    /**
     * Rasterise and write a transparent PNG, returning a shareable URI.
     *
     * PNG, not JPEG, and never flattened onto a background: the alpha channel is
     * the entire point. A slate exported with an opaque backing would cover the
     * footage it is meant to sit on.
     */
    fun writePng(context: Context, export: SlateExport): Uri {
        val dir = File(context.cacheDir, "slates").apply { mkdirs() }
        // One file, overwritten: the cache is a hand-off buffer, not a gallery,
        // and accumulating a PNG per preview would quietly fill the device.
        val file = File(dir, "slate.png")

        val bitmap = SlatePainter.toBitmap(export.slate, export.scale)
        try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

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
    fun saveToGallery(context: Context, export: SlateExport, title: String): Uri {
        val safeTitle = title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "dive" }
        val name = "diveslate-$safeTitle-${LocalDateTime.now().format(STAMP)}.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
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

        val bitmap = SlatePainter.toBitmap(export.slate, export.scale)
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("could not open the new image for writing")
        } catch (e: Exception) {
            // Leave no invisible half-written entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            bitmap.recycle()
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
