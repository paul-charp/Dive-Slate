package io.github.paulcharp.diveslate

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.paulcharp.diveslate.core.Slate
import java.io.File

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
}
