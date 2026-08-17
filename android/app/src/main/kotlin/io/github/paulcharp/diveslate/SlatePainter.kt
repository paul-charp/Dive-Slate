package io.github.paulcharp.diveslate

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.paulcharp.diveslate.core.Pt
import io.github.paulcharp.diveslate.core.Slate
import io.github.paulcharp.diveslate.core.SlateFill
import io.github.paulcharp.diveslate.core.SlateOp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Paints a [Slate] display list.
 *
 * Deliberately dumb: every decision was made in `core`, so this file only
 * translates operations into draw calls. If something here needs to *decide*
 * anything, it belongs on the other side of the split.
 *
 * Text is drawn through the native canvas rather than Compose's text stack
 * because the halo needs a stroke pass beneath the fill at a controlled width,
 * and `Paint.Style.STROKE` expresses that directly.
 */
object SlatePainter {

    private fun color(argb: Long) = Color(argb.toInt())

    private fun pathOf(points: List<Pt>, closed: Boolean): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
        if (closed) close()
    }

    /** Draw the whole slate at its natural size, in the current coordinate space. */
    fun DrawScope.drawSlate(slate: Slate) {
        for (op in slate.ops) {
            when (op) {
                is SlateOp.Rect -> drawRect(op)
                is SlateOp.Line -> drawLine(
                    color = color(op.argb),
                    start = Offset(op.start.x, op.start.y),
                    end = Offset(op.end.x, op.end.y),
                    strokeWidth = op.strokeWidth,
                )
                is SlateOp.Path -> drawPathOp(op)
                is SlateOp.Circle -> drawCircleOp(op)
                is SlateOp.Text -> drawTextOp(op)
            }
        }
    }

    private fun DrawScope.drawRect(op: SlateOp.Rect) {
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = op.x,
                    top = op.y,
                    right = op.x + op.width,
                    bottom = op.y + op.height,
                    radiusX = op.cornerRadius,
                    radiusY = op.cornerRadius,
                )
            )
        }
        fill(path, op.fill)
    }

    private fun DrawScope.drawPathOp(op: SlateOp.Path) {
        val path = pathOf(op.points, op.closed)
        op.fill?.let { fill(path, it) }
        op.strokeArgb?.let {
            drawPath(
                path = path,
                color = color(it),
                style = Stroke(
                    width = op.strokeWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }

    private fun DrawScope.drawCircleOp(op: SlateOp.Circle) {
        val centre = Offset(op.centre.x, op.centre.y)
        op.fillArgb?.let { drawCircle(color(it), op.radius, centre) }
        op.strokeArgb?.let {
            drawCircle(color(it), op.radius, centre, style = Stroke(op.strokeWidth))
        }
    }

    private fun DrawScope.fill(path: Path, fill: SlateFill) {
        when (fill) {
            is SlateFill.Solid -> drawPath(path, color(fill.argb))

            is SlateFill.Vertical -> {
                val bounds = path.getBounds()
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(color(fill.topArgb), color(fill.bottomArgb)),
                        startY = bounds.top,
                        endY = bounds.bottom,
                    ),
                )
            }

            // Compose has no pattern fill, so the region is clipped and ruled.
            // Hatched rather than washed on purpose: the ceiling is a boundary
            // the diver must not cross, and hatching reads as a barrier where a
            // flat tint reads as just another series.
            is SlateFill.Hatch -> {
                val bounds = path.getBounds()
                clipPath(path) {
                    val stroke = Stroke(fill.strokeWidth)
                    val ink = color(fill.argb)
                    val slope = tan(Math.toRadians(fill.angleDegrees.toDouble())).toFloat()
                    val span = bounds.height * abs(slope)
                    val step = fill.spacing * hypot(1f, slope)
                    var x = bounds.left - span
                    while (x <= bounds.right + span) {
                        drawLine(
                            color = ink,
                            start = Offset(x, bounds.bottom),
                            end = Offset(x + span, bounds.top),
                            strokeWidth = stroke.width,
                        )
                        x += step
                    }
                }
            }
        }
    }

    private fun DrawScope.drawTextOp(op: SlateOp.Text) {
        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = op.sizePx
                typeface = if (op.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                letterSpacing = op.letterSpacingEm
            }

            // The halo first, then the fill over it. Two passes, never one: the
            // backdrop is footage this code never sees, and a single-pass label
            // disappears wherever the frame behind it matches its colour.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = op.haloWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = op.haloArgb.toInt()
            canvas.nativeCanvas.drawText(op.text, op.x, op.baselineY, paint)

            paint.style = Paint.Style.FILL
            paint.color = op.fillArgb.toInt()
            canvas.nativeCanvas.drawText(op.text, op.x, op.baselineY, paint)
        }
    }

    /**
     * Rasterise a slate to a transparent bitmap.
     *
     * No background is ever painted. Transparency is the product: the slate is
     * composited over footage by whatever the user drops it into, and a bitmap
     * that arrived with an opaque backing would blank the frame behind it.
     */
    fun toBitmap(slate: Slate, scale: Float = 1f): Bitmap {
        val width = (slate.width * scale).toInt().coerceAtLeast(1)
        val height = (slate.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = NativeCanvas(bitmap)
        canvas.scale(scale, scale)

        val density = androidx.compose.ui.unit.Density(1f)
        val drawScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
        drawScope.draw(
            density = density,
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            canvas = androidx.compose.ui.graphics.Canvas(canvas),
            size = androidx.compose.ui.geometry.Size(slate.width, slate.height),
        ) {
            drawSlate(slate)
        }
        return bitmap
    }
}
