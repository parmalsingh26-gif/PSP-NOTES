package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import java.util.UUID

sealed class InkCommand {
    data class AddStroke(val stroke: InkStroke) : InkCommand()
    data class RemoveStrokes(val strokes: List<InkStroke>) : InkCommand()
    data class ModifyStrokes(val oldStrokes: List<InkStroke>, val newStrokes: List<InkStroke>) : InkCommand()
}

class InkEngine {
    var currentTool: ToolType = ToolType.PEN
    var currentColor: Long = 0xFF1E293B // Dark slate default
    var currentWidth: Float = 2.5f

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
        val firstPoint = InkPoint(docX, docY, pressure, System.currentTimeMillis())
        currentPoints.add(firstPoint)

        val opacity = when (currentTool) {
            ToolType.HIGHLIGHTER -> 0.35f
            ToolType.PENCIL -> 0.75f
            else -> 1.0f
        }

        activeStroke = InkStroke(
            id = UUID.randomUUID().toString(),
            tool = currentTool,
            color = currentColor,
            width = currentWidth,
            opacity = opacity,
            points = currentPoints.toList(),
            audioTimestampMs = audioTimestampMs
        )
    }

    fun continueStroke(docX: Float, docY: Float, pressure: Float = 1.0f) {
        val point = InkPoint(docX, docY, pressure, System.currentTimeMillis())
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
            is InkCommand.RemoveStrokes -> {
                result.addAll(command.strokes)
            }
            is InkCommand.ModifyStrokes -> {
                val oldMap = command.oldStrokes.associateBy { it.id }
                return currentStrokes.map { oldMap[it.id] ?: it }
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
            is InkCommand.RemoveStrokes -> {
                val toRemoveIds = command.strokes.map { it.id }.toSet()
                result.removeAll { toRemoveIds.contains(it.id) }
            }
            is InkCommand.ModifyStrokes -> {
                val newMap = command.newStrokes.associateBy { it.id }
                return currentStrokes.map { newMap[it.id] ?: it }
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
