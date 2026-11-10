package app.inkflow.studio

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.ink.InkEngine
import org.junit.Assert.*
import org.junit.Test

class LassoSelectionTest {

    @Test
    fun testLassoSelectionAndMove() {
        val inkEngine = InkEngine()

        val strokeInside = InkStroke(
            id = "inside-1",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = listOf(InkPoint(50f, 50f), InkPoint(60f, 60f))
        )
        val strokeOutside = InkStroke(
            id = "outside-1",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = listOf(InkPoint(500f, 500f), InkPoint(510f, 510f))
        )
        val strokes = listOf(strokeInside, strokeOutside)

        // Lasso polygon enclosing (0,0) to (100,100)
        val lassoPolygon = listOf(
            Pair(0f, 0f),
            Pair(100f, 0f),
            Pair(100f, 100f),
            Pair(0f, 100f)
        )

        val selectedIds = inkEngine.selectWithLasso(lassoPolygon, strokes)
        assertEquals(1, selectedIds.size)
        assertTrue(selectedIds.contains("inside-1"))

        // Move selection by (20, 30)
        val moved = inkEngine.moveSelectedStrokes(20f, 30f, strokes)
        val movedStroke = moved.first { it.id == "inside-1" }
        assertEquals(70f, movedStroke.points[0].x, 0.01f)
        assertEquals(80f, movedStroke.points[0].y, 0.01f)

        // Delete selection
        val remaining = inkEngine.deleteSelectedStrokes(moved)
        assertEquals(1, remaining.size)
        assertEquals("outside-1", remaining[0].id)
    }
}
