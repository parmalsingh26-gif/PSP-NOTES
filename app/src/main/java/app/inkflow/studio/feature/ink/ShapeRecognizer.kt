package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RecognizedShapeType {
    LINE,
    CIRCLE,
    RECTANGLE,
    NONE
}

data class RecognizedShape(
    val type: RecognizedShapeType,
    val snappedStroke: InkStroke
)

object ShapeRecognizer {

    fun recognize(stroke: InkStroke): RecognizedShape {
        val points = stroke.points
        if (points.size < 5) {
            return RecognizedShape(RecognizedShapeType.NONE, stroke)
        }

        // 1. Check for Circle / Closed Loop
        val first = points.first()
        val last = points.last()
        val loopClosureDist = dist(first.x, first.y, last.x, last.y)
        val bounds = stroke.bounds
        val approxDiameter = (bounds.right - bounds.left + bounds.bottom - bounds.top) / 2f

        if (approxDiameter > 30f && loopClosureDist < approxDiameter * 0.35f) {
            // Potential circle or ellipse: compute centroid
            val centerX = points.map { it.x }.average().toFloat()
            val centerY = points.map { it.y }.average().toFloat()

            val radii = points.map { dist(it.x, it.y, centerX, centerY) }
            val avgRadius = radii.average().toFloat()
            val variance = radii.map { (it - avgRadius) * (it - avgRadius) }.average()
            val stdDev = sqrt(variance).toFloat()

            // If radius deviation is small (< 22% of avg radius), it's a circle!
            if (avgRadius > 15f && stdDev / avgRadius < 0.22f) {
                val circlePoints = mutableListOf<InkPoint>()
                val numSamples = 36
                for (i in 0..numSamples) {
                    val angle = (i.toFloat() / numSamples) * (2 * Math.PI)
                    val cx = centerX + avgRadius * cos(angle).toFloat()
                    val cy = centerY + avgRadius * sin(angle).toFloat()
                    circlePoints.add(InkPoint(cx, cy, 1.0f))
                }
                val snapped = stroke.copy(points = circlePoints)
                return RecognizedShape(RecognizedShapeType.CIRCLE, snapped)
            }
        }

        // 2. Check for Straight Line
        var maxDev = 0f
        val lineLen = dist(first.x, first.y, last.x, last.y)
        if (lineLen > 25f) {
            for (p in points) {
                val dev = perpendicularDistance(p.x, p.y, first.x, first.y, last.x, last.y)
                if (dev > maxDev) maxDev = dev
            }
            if (maxDev < lineLen * 0.08f || maxDev < 12f) {
                val snapped = stroke.copy(
                    points = listOf(
                        InkPoint(first.x, first.y, 1.0f),
                        InkPoint(last.x, last.y, 1.0f)
                    )
                )
                return RecognizedShape(RecognizedShapeType.LINE, snapped)
            }
        }

        return RecognizedShape(RecognizedShapeType.NONE, stroke)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun perpendicularDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0f) return dist(px, py, x1, y1)
        return abs(dy * px - dx * py + x2 * y1 - y2 * x1) / len
    }
}
