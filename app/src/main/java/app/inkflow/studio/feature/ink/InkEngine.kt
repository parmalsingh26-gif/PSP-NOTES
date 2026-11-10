package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.StrokeBounds
import app.inkflow.studio.domain.model.ToolType
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed class InkCommand {
    data class AddStroke(val stroke: InkStroke) : InkCommand()
    data class AddStrokes(val strokes: List<InkStroke>) : InkCommand()
    data class RemoveStrokes(val strokes: List<InkStroke>) : InkCommand()
    data class ModifyStrokes(val oldStrokes: List<InkStroke>, val newStrokes: List<InkStroke>) : InkCommand()
    data class ReplaceStrokes(val oldAll: List<InkStroke>, val newAll: List<InkStroke>) : InkCommand()
}

data class PenPreset(
    val id: String,
    val name: String,
    val tool: ToolType,
    val color: Long,
    val width: Float
)

class InkEngine {
    var currentTool: ToolType = ToolType.PEN
    var currentColor: Long = 0xFF1E293B // Dark slate default
    var currentWidth: Float = 2.5f

    val currentOpacity: Float
        get() = when (currentTool) {
            ToolType.HIGHLIGHTER -> 0.35f
            ToolType.PENCIL -> 0.78f
            else -> 1.0f
        }

    private val undoStack = ArrayDeque<InkCommand>()
    private val redoStack = ArrayDeque<InkCommand>()

    private val currentPoints = mutableListOf<InkPoint>()
    var activeStroke: InkStroke? = null
        private set

    // Current lasso selection
    val selectedStrokeIds = mutableSetOf<String>()

    fun screenToDocument(
        screenX: Float,
        screenY: Float,
        originX: Float = 0f,
        originY: Float = 0f,
        panX: Float = 0f,
        panY: Float = 0f,
        totalScale: Float = 1.0f
    ): Pair<Float, Float> {
        val scale = if (totalScale <= 0f) 1f else totalScale
        val docX = (screenX - originX - panX) / scale
        val docY = (screenY - originY - panY) / scale
        return Pair(docX, docY)
    }

    // Convenience method for backwards compatibility with tests and callers that pass (screenX, screenY, panX, panY, zoom)
    fun screenToDocument(screenX: Float, screenY: Float, panX: Float, panY: Float, zoom: Float): Pair<Float, Float> {
        return screenToDocument(screenX, screenY, 0f, 0f, panX, panY, zoom)
    }

    fun documentToScreen(
        docX: Float,
        docY: Float,
        originX: Float = 0f,
        originY: Float = 0f,
        panX: Float = 0f,
        panY: Float = 0f,
        totalScale: Float = 1.0f
    ): Pair<Float, Float> {
        val screenX = originX + panX + docX * totalScale
        val screenY = originY + panY + docY * totalScale
        return Pair(screenX, screenY)
    }

    fun startStroke(
        docX: Float,
        docY: Float,
        pressure: Float = 1.0f,
        audioTimestampMs: Long? = null
    ) {
        currentPoints.clear()
        val firstPoint = InkPoint(docX, docY, pressure.coerceIn(0.1f, 1.0f), System.currentTimeMillis())
        currentPoints.add(firstPoint)

        activeStroke = InkStroke(
            id = UUID.randomUUID().toString(),
            tool = currentTool,
            color = currentColor,
            width = currentWidth,
            opacity = currentOpacity,
            points = currentPoints.toList(),
            audioTimestampMs = audioTimestampMs
        )
    }

    fun continueStroke(docX: Float, docY: Float, pressure: Float = 1.0f) {
        val point = InkPoint(docX, docY, pressure.coerceIn(0.1f, 1.0f), System.currentTimeMillis())
        currentPoints.add(point)
        activeStroke = activeStroke?.copy(points = currentPoints.toList())
    }

    fun finishStroke(): InkStroke? {
        val stroke = activeStroke
        currentPoints.clear()
        activeStroke = null

        if (stroke != null && stroke.points.isNotEmpty()) {
            undoStack.addLast(InkCommand.AddStroke(stroke))
            redoStack.clear()
            return stroke
        }
        return null
    }

    fun recordAddedStroke(stroke: InkStroke) {
        undoStack.addLast(InkCommand.AddStroke(stroke))
        redoStack.clear()
    }

    fun replaceLastAddedStroke(snappedStroke: InkStroke) {
        if (undoStack.isNotEmpty() && undoStack.last() is InkCommand.AddStroke) {
            undoStack.removeLast()
        }
        undoStack.addLast(InkCommand.AddStroke(snappedStroke))
        redoStack.clear()
    }

    fun cancelStroke() {
        currentPoints.clear()
        activeStroke = null
    }

    fun eraseAt(docX: Float, docY: Float, radius: Float, existingStrokes: List<InkStroke>): Pair<List<InkStroke>, List<InkStroke>> {
        val erased = mutableListOf<InkStroke>()
        val remaining = mutableListOf<InkStroke>()

        for (stroke in existingStrokes) {
            if (stroke.intersects(docX, docY, radius)) {
                erased.add(stroke)
            } else {
                remaining.add(stroke)
            }
        }

        if (erased.isNotEmpty()) {
            undoStack.addLast(InkCommand.RemoveStrokes(erased))
            redoStack.clear()
        }

        return Pair(remaining, erased)
    }

    /**
     * Partial / Pixel Eraser: splits intersecting strokes into sub-strokes outside the eraser circle.
     * Long segments are densified before splitting so shapes and lines erase smoothly.
     */
    fun erasePartialAt(docX: Float, docY: Float, radius: Float, existingStrokes: List<InkStroke>): List<InkStroke> {
        var anyModified = false
        val newStrokeList = mutableListOf<InkStroke>()
        val rSq = radius * radius

        for (stroke in existingStrokes) {
            if (!stroke.intersects(docX, docY, radius)) {
                newStrokeList.add(stroke)
                continue
            }

            anyModified = true
            // Densify segments longer than radius/2 so straight lines & shapes split cleanly
            val stepLen = (radius * 0.45f).coerceAtLeast(3f)
            val densePoints = mutableListOf<InkPoint>()
            for (i in stroke.points.indices) {
                val p1 = stroke.points[i]
                densePoints.add(p1)
                if (i < stroke.points.size - 1) {
                    val p2 = stroke.points[i + 1]
                    val dist = hypot(p2.x - p1.x, p2.y - p1.y)
                    if (dist > stepLen) {
                        val steps = (dist / stepLen).toInt()
                        for (s in 1 until steps) {
                            val t = s.toFloat() / steps
                            densePoints.add(
                                InkPoint(
                                    x = p1.x + (p2.x - p1.x) * t,
                                    y = p1.y + (p2.y - p1.y) * t,
                                    pressure = p1.pressure + (p2.pressure - p1.pressure) * t,
                                    timestamp = p1.timestamp
                                )
                            )
                        }
                    }
                }
            }

            // Split densePoints into contiguous chunks outside the eraser circle
            val currentChunk = mutableListOf<InkPoint>()
            for (pt in densePoints) {
                val dx = pt.x - docX
                val dy = pt.y - docY
                val inside = (dx * dx + dy * dy) <= rSq
                if (!inside) {
                    currentChunk.add(pt)
                } else {
                    if (currentChunk.size >= 2) {
                        newStrokeList.add(
                            stroke.copy(
                                id = UUID.randomUUID().toString(),
                                points = currentChunk.toList()
                            )
                        )
                    }
                    currentChunk.clear()
                }
            }
            if (currentChunk.size >= 2) {
                newStrokeList.add(
                    stroke.copy(
                        id = UUID.randomUUID().toString(),
                        points = currentChunk.toList()
                    )
                )
            }
        }

        if (anyModified) {
            undoStack.addLast(InkCommand.ReplaceStrokes(existingStrokes, newStrokeList))
            redoStack.clear()
        }
        return if (anyModified) newStrokeList else existingStrokes
    }

    // --- Lasso Selection Operations ---
    fun selectWithLasso(lassoPolygon: List<Pair<Float, Float>>, allStrokes: List<InkStroke>): Set<String> {
        selectedStrokeIds.clear()
        if (lassoPolygon.size < 3) return emptySet()

        for (stroke in allStrokes) {
            val midX = (stroke.bounds.left + stroke.bounds.right) / 2f
            val midY = (stroke.bounds.top + stroke.bounds.bottom) / 2f

            if (isPointInPolygon(midX, midY, lassoPolygon) ||
                stroke.points.any { isPointInPolygon(it.x, it.y, lassoPolygon) }
            ) {
                selectedStrokeIds.add(stroke.id)
            }
        }
        return selectedStrokeIds.toSet()
    }

    fun getSelectionBounds(allStrokes: List<InkStroke>): StrokeBounds? {
        val selected = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        if (selected.isEmpty()) return null
        var minL = Float.MAX_VALUE
        var minT = Float.MAX_VALUE
        var maxR = -Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        for (s in selected) {
            val b = s.bounds
            minL = min(minL, b.left)
            minT = min(minT, b.top)
            maxR = max(maxR, b.right)
            maxB = max(maxB, b.bottom)
        }
        return StrokeBounds(minL, minT, maxR, maxB)
    }

    fun moveSelectedStrokes(dx: Float, dy: Float, allStrokes: List<InkStroke>): List<InkStroke> {
        if (selectedStrokeIds.isEmpty()) return allStrokes
        val oldStrokes = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        val newStrokes = oldStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { pt -> pt.copy(x = pt.x + dx, y = pt.y + dy) }
            )
        }
        val newMap = newStrokes.associateBy { it.id }
        val updatedList = allStrokes.map { newMap[it.id] ?: it }

        undoStack.addLast(InkCommand.ModifyStrokes(oldStrokes, newStrokes))
        redoStack.clear()
        return updatedList
    }

    fun scaleSelectedStrokes(
        scaleFactor: Float,
        allStrokes: List<InkStroke>
    ): List<InkStroke> {
        val bounds = getSelectionBounds(allStrokes) ?: return allStrokes
        val factor = scaleFactor.coerceIn(0.4f, 2.5f)
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f

        val oldStrokes = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        val newStrokes = oldStrokes.map { stroke ->
            stroke.copy(
                width = (stroke.width * factor).coerceIn(0.5f, 24f),
                points = stroke.points.map { pt ->
                    pt.copy(
                        x = cx + (pt.x - cx) * factor,
                        y = cy + (pt.y - cy) * factor
                    )
                }
            )
        }
        val newMap = newStrokes.associateBy { it.id }
        val updatedList = allStrokes.map { newMap[it.id] ?: it }

        undoStack.addLast(InkCommand.ModifyStrokes(oldStrokes, newStrokes))
        redoStack.clear()
        return updatedList
    }

    fun duplicateSelectedStrokes(dx: Float, dy: Float, allStrokes: List<InkStroke>): List<InkStroke> {
        if (selectedStrokeIds.isEmpty()) return allStrokes
        val selected = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        val duplicated = selected.map { stroke ->
            stroke.copy(
                id = UUID.randomUUID().toString(),
                points = stroke.points.map { pt -> pt.copy(x = pt.x + dx, y = pt.y + dy) }
            )
        }
        selectedStrokeIds.clear()
        selectedStrokeIds.addAll(duplicated.map { it.id })
        undoStack.addLast(InkCommand.AddStrokes(duplicated))
        redoStack.clear()
        return allStrokes + duplicated
    }

    fun deleteSelectedStrokes(allStrokes: List<InkStroke>): List<InkStroke> {
        if (selectedStrokeIds.isEmpty()) return allStrokes
        val toDelete = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        val remaining = allStrokes.filter { !selectedStrokeIds.contains(it.id) }

        undoStack.addLast(InkCommand.RemoveStrokes(toDelete))
        redoStack.clear()
        selectedStrokeIds.clear()
        return remaining
    }

    fun recolorSelectedStrokes(newColor: Long, allStrokes: List<InkStroke>): List<InkStroke> {
        if (selectedStrokeIds.isEmpty()) return allStrokes
        val oldStrokes = allStrokes.filter { selectedStrokeIds.contains(it.id) }
        val newStrokes = oldStrokes.map { it.copy(color = newColor) }
        val newMap = newStrokes.associateBy { it.id }
        val updatedList = allStrokes.map { newMap[it.id] ?: it }

        undoStack.addLast(InkCommand.ModifyStrokes(oldStrokes, newStrokes))
        redoStack.clear()
        return updatedList
    }

    fun clearSelection() {
        selectedStrokeIds.clear()
    }

    private fun isPointInPolygon(px: Float, py: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].first
            val yi = polygon[i].second
            val xj = polygon[j].first
            val yj = polygon[j].second
            val intersect = ((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun undo(currentStrokes: List<InkStroke>): List<InkStroke> {
        if (undoStack.isEmpty()) return currentStrokes
        val command = undoStack.removeLast()
        redoStack.addLast(command)

        val result = currentStrokes.toMutableList()
        when (command) {
            is InkCommand.AddStroke -> {
                result.removeAll { it.id == command.stroke.id }
            }
            is InkCommand.AddStrokes -> {
                val ids = command.strokes.map { it.id }.toSet()
                result.removeAll { ids.contains(it.id) }
            }
            is InkCommand.RemoveStrokes -> {
                result.addAll(command.strokes)
            }
            is InkCommand.ModifyStrokes -> {
                val oldMap = command.oldStrokes.associateBy { it.id }
                return currentStrokes.map { oldMap[it.id] ?: it }
            }
            is InkCommand.ReplaceStrokes -> {
                return command.oldAll
            }
        }
        return result
    }

    fun redo(currentStrokes: List<InkStroke>): List<InkStroke> {
        if (redoStack.isEmpty()) return currentStrokes
        val command = redoStack.removeLast()
        undoStack.addLast(command)

        val result = currentStrokes.toMutableList()
        when (command) {
            is InkCommand.AddStroke -> {
                result.add(command.stroke)
            }
            is InkCommand.AddStrokes -> {
                result.addAll(command.strokes)
            }
            is InkCommand.RemoveStrokes -> {
                val toRemoveIds = command.strokes.map { it.id }.toSet()
                result.removeAll { toRemoveIds.contains(it.id) }
            }
            is InkCommand.ModifyStrokes -> {
                val newMap = command.newStrokes.associateBy { it.id }
                return currentStrokes.map { newMap[it.id] ?: it }
            }
            is InkCommand.ReplaceStrokes -> {
                return command.newAll
            }
        }
        return result
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        selectedStrokeIds.clear()
    }
}
