package app.inkflow.studio.domain.model

data class InkStroke(
    val id: String,
    val tool: ToolType,
    val color: Long, // 0xAARRGGBB
    val width: Float,
    val opacity: Float = 1.0f,
    val points: List<InkPoint>,
    val audioTimestampMs: Long? = null
) {
    val bounds: StrokeBounds
        get() {
            if (points.isEmpty()) {
                return StrokeBounds(0f, 0f, 0f, 0f)
            }
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val halfW = width / 2f
            return StrokeBounds(minX - halfW, minY - halfW, maxX + halfW, maxY + halfW)
        }

    fun intersects(x: Float, y: Float, threshold: Float): Boolean {
        // Quick bounding box check
        if (x < bounds.left - threshold || x > bounds.right + threshold ||
            y < bounds.top - threshold || y > bounds.bottom + threshold
        ) {
            return false
        }
        val effThreshold = threshold + width / 2f
        val sqThreshold = effThreshold * effThreshold

        // Point-to-segment distance check
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (distToSegmentSquared(x, y, p1.x, p1.y, p2.x, p2.y) <= sqThreshold) {
                return true
            }
        }
        return false
    }

    companion object {
        private fun distToSegmentSquared(px: Float, py: Float, vx: Float, vy: Float, wx: Float, wy: Float): Float {
            val l2 = (wx - vx) * (wx - vx) + (wy - vy) * (wy - vy)
            if (l2 == 0f) return (px - vx) * (px - vx) + (py - vy) * (py - vy)
            val t = ((px - vx) * (wx - vx) + (py - vy) * (wy - vy)) / l2
            val clampedT = t.coerceIn(0f, 1f)
            val projX = vx + clampedT * (wx - vx)
            val projY = vy + clampedT * (wy - vy)
            val dx = px - projX
            val dy = py - projY
            return dx * dx + dy * dy
        }
    }
}

data class StrokeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

