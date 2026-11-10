package app.inkflow.studio.feature.ink

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import java.util.UUID

sealed class InkCommand {
    data class AddStroke(val stroke: InkStroke) : InkCommand()
    data class RemoveStrokes(val strokes: List<InkStroke>) : InkCommand()
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

    fun screenToDocument(screenX: Float, screenY: Float, panX: Float, panY: Float, zoom: Float): Pair<Float, Float> {
        val safeZoom = if (zoom == 0f) 1f else zoom
        val docX = (screenX - panX) / safeZoom
        val docY = (screenY - panY) / safeZoom
        return Pair(docX, docY)
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
        }
        return result
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }
}

