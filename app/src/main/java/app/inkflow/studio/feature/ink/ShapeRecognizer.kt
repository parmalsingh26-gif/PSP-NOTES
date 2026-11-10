package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RecognizedShapeType {
    LINE,
    CIRCLE,
    RECTANGLE,
    TRIANGLE,
    NONE
}

data class RecognizedShape(
    val type: RecognizedShapeType,
    val snappedStroke: InkStroke
)

object ShapeRecognizer {

    fun recognize(stroke: InkStroke): RecognizedShape {
        val points = stroke.points
        if (points.size < 4) {
            return RecognizedShape(RecognizedShapeType.NONE, stroke)
        }

        val first = points.first()
        val last = points.last()

        // 1. Check for Straight Line first (open path with minimal perpendicular deviation)
        val lineLen = dist(first.x, first.y, last.x, last.y)
        if (lineLen > 25f) {
            var maxDev = 0f
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

        if (points.size < 5) {
            return RecognizedShape(RecognizedShapeType.NONE, stroke)
        }

        // 2. Check for Closed Loop shapes (Circle, Rectangle, Triangle)
        val loopClosureDist = dist(first.x, first.y, last.x, last.y)
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val w = maxX - minX
        val h = maxY - minY
        val approxDiameter = (w + h) / 2f

        if (approxDiameter > 30f && loopClosureDist < approxDiameter * 0.38f) {
            // Build dense sample points including segment midpoints so zig-zag interior crossings are penalized
            val densePoints = mutableListOf<InkPoint>()
            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                densePoints.add(p1)
                densePoints.add(InkPoint((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f))
            }
            densePoints.add(points.last())

            val centerX = densePoints.map { it.x }.average().toFloat()
            val centerY = densePoints.map { it.y }.average().toFloat()

            val radii = densePoints.map { dist(it.x, it.y, centerX, centerY) }
            val avgRadius = radii.average().toFloat()
            val variance = radii.map { (it - avgRadius) * (it - avgRadius) }.average()
            val stdDev = sqrt(variance).toFloat()

            // 2a. Circle check: radius standard deviation is small (< 16% of avg radius)
            if (avgRadius > 15f && stdDev / avgRadius < 0.16f) {
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

            // 2b & 2c. Compare Rectangle perimeter fit vs Triangle perimeter fit using densePoints
            if (w > 20f && h > 20f) {
                var totalRectDist = 0f
                var maxRectDist = 0f
                for (p in densePoints) {
                    val distLeft = abs(p.x - minX)
                    val distRight = abs(p.x - maxX)
                    val distTop = abs(p.y - minY)
                    val distBottom = abs(p.y - maxY)
                    val minEdgeDist = minOf(distLeft, distRight, distTop, distBottom)
                    totalRectDist += minEdgeDist
                    if (minEdgeDist > maxRectDist) maxRectDist = minEdgeDist
                }
                val avgRectDist = totalRectDist / densePoints.size

                val apexX = (minX + maxX) / 2f
                val apexY = minY
                var totalTriDist = 0f
                var maxTriDist = 0f
                for (p in densePoints) {
                    val d1 = distToSegment(p.x, p.y, apexX, apexY, maxX, maxY)
                    val d2 = distToSegment(p.x, p.y, maxX, maxY, minX, maxY)
                    val d3 = distToSegment(p.x, p.y, minX, maxY, apexX, apexY)
                    val minTri = minOf(d1, d2, d3)
                    totalTriDist += minTri
                    if (minTri > maxTriDist) maxTriDist = minTri
                }
                val avgTriDist = totalTriDist / densePoints.size

                if (avgTriDist < avgRectDist && avgTriDist / approxDiameter < 0.10f && maxTriDist / approxDiameter < 0.22f) {
                    val triPoints = listOf(
                        InkPoint(apexX, apexY, 1.0f),
                        InkPoint(maxX, maxY, 1.0f),
                        InkPoint(minX, maxY, 1.0f),
                        InkPoint(apexX, apexY, 1.0f)
                    )
                    val snapped = stroke.copy(points = triPoints)
                    return RecognizedShape(RecognizedShapeType.TRIANGLE, snapped)
                }

                if (avgRectDist / approxDiameter < 0.10f && maxRectDist / approxDiameter < 0.22f) {
                    val rectPoints = listOf(
                        InkPoint(minX, minY, 1.0f),
                        InkPoint(maxX, minY, 1.0f),
                        InkPoint(maxX, maxY, 1.0f),
                        InkPoint(minX, maxY, 1.0f),
                        InkPoint(minX, minY, 1.0f)
                    )
                    val snapped = stroke.copy(points = rectPoints)
                    return RecognizedShape(RecognizedShapeType.RECTANGLE, snapped)
                }
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

    private fun distToSegment(px: Float, py: Float, vx: Float, vy: Float, wx: Float, wy: Float): Float {
        val l2 = (wx - vx) * (wx - vx) + (wy - vy) * (wy - vy)
        if (l2 == 0f) return dist(px, py, vx, vy)
        val t = (((px - vx) * (wx - vx) + (py - vy) * (wy - vy)) / l2).coerceIn(0f, 1f)
        val projX = vx + t * (wx - vx)
        val projY = vy + t * (wy - vy)
        return dist(px, py, projX, projY)
    }
}
