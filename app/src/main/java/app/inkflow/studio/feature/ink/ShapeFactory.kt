package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import java.util.UUID
import kotlin.math.*

enum class PresetShapeType {
    RECTANGLE,
    ROUNDED_RECTANGLE,
    CIRCLE,
    LINE,
    ARROW,
    TRIANGLE,
    STAR
}

object ShapeFactory {

    fun createPresetShape(
        type: PresetShapeType,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        color: Long = 0xFF0F172AL,
        width: Float = 3.0f,
        opacity: Float = 1.0f
    ): InkStroke {
        val left = min(startX, endX)
        val right = max(startX, endX)
        val top = min(startY, endY)
        val bottom = max(startY, endY)
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)

        val points = mutableListOf<InkPoint>()

        when (type) {
            PresetShapeType.RECTANGLE -> {
                points.add(InkPoint(left, top, 1.0f))
                points.add(InkPoint(right, top, 1.0f))
                points.add(InkPoint(right, bottom, 1.0f))
                points.add(InkPoint(left, bottom, 1.0f))
                points.add(InkPoint(left, top, 1.0f))
            }

            PresetShapeType.ROUNDED_RECTANGLE -> {
                val r = min(16f, min(w, h) / 4f)
                val steps = 6
                // Top-right corner
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (Math.PI / 2.0) - (Math.PI / 2.0)
                    points.add(InkPoint((right - r + r * cos(a)).toFloat(), (top + r + r * sin(a)).toFloat(), 1.0f))
                }
                // Bottom-right corner
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (Math.PI / 2.0)
                    points.add(InkPoint((right - r + r * cos(a)).toFloat(), (bottom - r + r * sin(a)).toFloat(), 1.0f))
                }
                // Bottom-left corner
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (Math.PI / 2.0) + (Math.PI / 2.0)
                    points.add(InkPoint((left + r + r * cos(a)).toFloat(), (bottom - r + r * sin(a)).toFloat(), 1.0f))
                }
                // Top-left corner
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (Math.PI / 2.0) + Math.PI
                    points.add(InkPoint((left + r + r * cos(a)).toFloat(), (top + r + r * sin(a)).toFloat(), 1.0f))
                }
                // Close path
                points.add(points.first())
            }

            PresetShapeType.CIRCLE -> {
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val steps = 36
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * (2 * Math.PI)
                    points.add(InkPoint((cx + rx * cos(a)).toFloat(), (cy + ry * sin(a)).toFloat(), 1.0f))
                }
            }

            PresetShapeType.LINE -> {
                points.add(InkPoint(startX, startY, 1.0f))
                points.add(InkPoint(endX, endY, 1.0f))
            }

            PresetShapeType.ARROW -> {
                points.add(InkPoint(startX, startY, 1.0f))
                points.add(InkPoint(endX, endY, 1.0f))

                val dx = endX - startX
                val dy = endY - startY
                val angle = atan2(dy, dx)
                val dist = sqrt(dx * dx + dy * dy)
                val arrowHeadLen = min(24f, dist * 0.35f).coerceAtLeast(8f)

                val head1X = endX - arrowHeadLen * cos(angle - Math.PI / 6.0).toFloat()
                val head1Y = endY - arrowHeadLen * sin(angle - Math.PI / 6.0).toFloat()
                val head2X = endX - arrowHeadLen * cos(angle + Math.PI / 6.0).toFloat()
                val head2Y = endY - arrowHeadLen * sin(angle + Math.PI / 6.0).toFloat()

                points.add(InkPoint(head1X, head1Y, 1.0f))
                points.add(InkPoint(endX, endY, 1.0f))
                points.add(InkPoint(head2X, head2Y, 1.0f))
            }

            PresetShapeType.TRIANGLE -> {
                val cx = (left + right) / 2f
                points.add(InkPoint(cx, top, 1.0f))
                points.add(InkPoint(right, bottom, 1.0f))
                points.add(InkPoint(left, bottom, 1.0f))
                points.add(InkPoint(cx, top, 1.0f))
            }

            PresetShapeType.STAR -> {
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val outerR = min(w, h) / 2f
                val innerR = outerR * 0.45f
                val numPoints = 5
                for (i in 0 until (numPoints * 2)) {
                    val r = if (i % 2 == 0) outerR else innerR
                    val a = (i.toFloat() / (numPoints * 2)) * (2 * Math.PI) - (Math.PI / 2.0)
                    points.add(InkPoint((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), 1.0f))
                }
                points.add(points.first())
            }
        }

        return InkStroke(
            id = UUID.randomUUID().toString(),
            tool = ToolType.SHAPE,
            color = color,
            width = width,
            opacity = opacity,
            points = points
        )
    }
}
