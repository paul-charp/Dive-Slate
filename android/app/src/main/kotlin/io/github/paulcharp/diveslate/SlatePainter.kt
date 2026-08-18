package io.github.paulcharp.diveslate

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.paulcharp.diveslate.core.Pt
import io.github.paulcharp.diveslate.core.Slate
import io.github.paulcharp.diveslate.core.SlateFill
import io.github.paulcharp.diveslate.core.SlateOp
import io.github.paulcharp.diveslate.core.TextAnchor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Paints a [Slate] display list.
 *
 * Deliberately dumb: every decision was made in `core`, so this file only
 * translates operations into draw calls. If something here needs to *decide*
 * anything, it belongs on the other side of the split.
 *
 * Text is drawn through the native canvas rather than Compose's text stack
 * because the halo needs a stroke pass beneath the fill at a controlled width,
 * and `Paint.Style.STROKE` expresses that directly. It also lets the anchor use
 * `Paint.Align`, so a right-aligned label is placed by measurement rather than
 * by core's character-count estimate — the estimate is good enough to lay a
 * column out, and not good enough to hang a mark off the right edge.
 */
object SlatePainter {

    private fun color(argb: Long) = Color(argb.toInt())

    private fun pathOf(points: List<Pt>, closed: Boolean, smooth: Boolean = false): Path =
        Path().apply {
            if (points.isEmpty()) return@apply
            moveTo(points.first().x, points.first().y)
            if (smooth) {
                // Flat tangents: each segment leaves one point horizontally and
                // arrives at the next horizontally. The curve therefore stays
                // between the two depths it joins — see SlateOp.Path.smooth for
                // why a nicer-looking spline is the wrong one here.
                for (index in 1 until points.size) {
                    val from = points[index - 1]
                    val to = points[index]
                    val reach = (to.x - from.x) / 2f
                    cubicTo(from.x + reach, from.y, to.x - reach, to.y, to.x, to.y)
                }
            } else {
                for (p in points.drop(1)) lineTo(p.x, p.y)
            }
            if (closed) close()
        }

    /** Draw the whole slate at its natural size, in the current coordinate space. */
    fun DrawScope.drawSlate(slate: Slate) {
        for (op in slate.ops) {
            when (op) {
                is SlateOp.Rect -> drawRectOp(op)
                is SlateOp.Line -> drawLineOp(op)
                is SlateOp.Path -> drawPathOp(op)
                is SlateOp.Circle -> drawCircleOp(op)
                is SlateOp.Text -> drawTextOp(op)
            }
        }
    }

    private fun DrawScope.drawRectOp(op: SlateOp.Rect) {
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
        op.fill?.let { fill(path, it) }
        op.strokeArgb?.let {
            drawPath(path, color(it), style = Stroke(op.strokeWidth))
        }
    }

    private fun DrawScope.drawLineOp(op: SlateOp.Line) {
        drawLine(
            color = color(op.argb),
            start = Offset(op.start.x, op.start.y),
            end = Offset(op.end.x, op.end.y),
            strokeWidth = op.strokeWidth,
            pathEffect = op.dash?.let {
                PathEffect.dashPathEffect(floatArrayOf(it.on, it.off))
            },
        )
    }

    private fun DrawScope.drawPathOp(op: SlateOp.Path) {
        val path = pathOf(op.points, op.closed, op.smooth)
        op.fill?.let { fill(path, it) }

        val strokeArgb = op.strokeArgb ?: return
        val stroke = Stroke(
            width = op.strokeWidth,
            // A stepped trace is squared off; everything else is rounded, so a
            // sharp reversal in the profile does not grow a spike.
            cap = if (op.crisp) StrokeCap.Butt else StrokeCap.Round,
            join = if (op.crisp) StrokeJoin.Miter else StrokeJoin.Round,
            pathEffect = op.dash?.let {
                PathEffect.dashPathEffect(floatArrayOf(it.on, it.off))
            },
        )

        // The glow, before the sharp line: a few wider passes at falling alpha.
        // Not a blur mask — that is the part of the platform canvas most likely
        // to differ between the on-screen preview and the software canvas the
        // export rasterises through, and a glow visible in only one of them is
        // worse than none.
        if (op.glowRadius > 0f) {
            val passes = 3
            for (pass in passes downTo 1) {
                val spread = op.glowRadius * pass / passes
                drawPath(
                    path = path,
                    color = color(strokeArgb).copy(alpha = 0.16f),
                    style = Stroke(
                        width = op.strokeWidth + spread * 2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        if (op.strokeEndArgb != null) {
            val bounds = path.getBounds()
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(color(strokeArgb), color(op.strokeEndArgb!!)),
                    startX = bounds.left,
                    endX = bounds.right,
                ),
                style = stroke,
            )
        } else {
            drawPath(path = path, color = color(strokeArgb), style = stroke)
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

            is SlateFill.Linear -> {
                val bounds = path.getBounds()
                val (start, end) = gradientEnds(bounds, fill.angleDegrees)
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(color(fill.startArgb), color(fill.endArgb)),
                        start = start,
                        end = end,
                    ),
                )
            }

            // Compose has no pattern fill, so every texture below is clipped to
            // the shape and then drawn by hand.
            is SlateFill.Dots -> {
                val bounds = path.getBounds()
                clipPath(path) {
                    val ink = color(fill.argb)
                    var y = bounds.top + fill.pitch / 2f
                    while (y <= bounds.bottom) {
                        var x = bounds.left + fill.pitch / 2f
                        while (x <= bounds.right) {
                            drawCircle(ink, fill.radius, Offset(x, y))
                            x += fill.pitch
                        }
                        y += fill.pitch
                    }
                }
            }

            is SlateFill.Grain -> {
                val bounds = path.getBounds()
                clipPath(path) {
                    // Seeded, so two exports of one dive are the same file. A
                    // global random source here would make the grain the only
                    // part of the slate that changed between saves.
                    val random = Random(fill.seed)
                    val ink = color(fill.argb)
                    var y = bounds.top
                    while (y <= bounds.bottom) {
                        var x = bounds.left
                        while (x <= bounds.right) {
                            if (random.nextFloat() < fill.density) {
                                drawCircle(
                                    ink,
                                    fill.cell * 0.34f,
                                    Offset(
                                        x + random.nextFloat() * fill.cell,
                                        y + random.nextFloat() * fill.cell,
                                    ),
                                )
                            }
                            x += fill.cell
                        }
                        y += fill.cell
                    }
                }
            }

            // Hatched rather than washed on purpose: the ceiling is a boundary
            // the diver must not cross, and hatching reads as a barrier where a
            // flat tint reads as just another series.
            is SlateFill.Hatch -> {
                val bounds = path.getBounds()
                clipPath(path) {
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
                            strokeWidth = fill.strokeWidth,
                        )
                        x += step
                    }
                }
            }
        }
    }

    /** Where a gradient at [angleDegrees] enters and leaves [bounds]. */
    private fun gradientEnds(bounds: ComposeRect, angleDegrees: Float): Pair<Offset, Offset> {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val cx = bounds.center.x
        val cy = bounds.center.y
        val reach = (abs(dx) * bounds.width + abs(dy) * bounds.height) / 2f
        return Offset(cx - dx * reach, cy - dy * reach) to
            Offset(cx + dx * reach, cy + dy * reach)
    }

    private fun DrawScope.drawTextOp(op: SlateOp.Text) {
        drawIntoCanvas { canvas ->
            val style = when {
                op.bold && op.italic -> Typeface.BOLD_ITALIC
                op.bold -> Typeface.BOLD
                op.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = op.sizePx
                typeface = Typeface.create(op.font.family, style)
                letterSpacing = op.letterSpacingEm
                textAlign = when (op.anchor) {
                    TextAnchor.START -> Paint.Align.LEFT
                    TextAnchor.MIDDLE -> Paint.Align.CENTER
                    TextAnchor.END -> Paint.Align.RIGHT
                }
            }

            // The halo first, then the fill over it. Two passes, never one: the
            // backdrop is footage this code never sees, and a single-pass label
            // disappears wherever the frame behind it matches its colour. A
            // style on an opaque card asks for width zero and gets one pass.
            if (op.haloWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.haloWidth
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = op.haloArgb.toInt()
                canvas.nativeCanvas.drawText(op.text, op.x, op.baselineY, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = op.fillArgb.toInt()
            op.gradientEndArgb?.let { end ->
                val width = paint.measureText(op.text)
                val left = when (op.anchor) {
                    TextAnchor.START -> op.x
                    TextAnchor.MIDDLE -> op.x - width / 2f
                    TextAnchor.END -> op.x - width
                }
                paint.shader = LinearGradient(
                    left, op.baselineY - op.sizePx,
                    left + width, op.baselineY,
                    op.fillArgb.toInt(), end.toInt(),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.nativeCanvas.drawText(op.text, op.x, op.baselineY, paint)
        }
    }

    /**
     * Rasterise a slate to a transparent bitmap.
     *
     * No background is ever painted. Transparency is the product: the slate is
     * composited over footage by whatever the user drops it into, and a bitmap
     * that arrived with an opaque backing would blank the frame behind it. A
     * style that paints its own card paints it as an op, inside these bounds,
     * which is a different thing from the canvas having one.
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
