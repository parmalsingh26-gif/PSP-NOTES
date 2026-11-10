package app.inkflow.studio

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.ink.InkEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class InkEngineTest {
    private lateinit var inkEngine: InkEngine

    @Before
    fun setUp() {
        inkEngine = InkEngine()
    }

    @Test
    fun testCoordinateTransform_normal() {
        // screen = (100, 200), pan = (0, 0), zoom = 1.0 -> doc = (100, 200)
        val (docX, docY) = inkEngine.screenToDocument(100f, 200f, 0f, 0f, 1.0f)
        assertEquals(100f, docX, 0.001f)
        assertEquals(200f, docY, 0.001f)
    }

    @Test
    fun testCoordinateTransform_withZoomAndPan() {
        // screen = (300, 400), pan = (100, 100), zoom = 2.0 -> doc = ((300-100)/2, (400-100)/2) = (100, 150)
        val (docX, docY) = inkEngine.screenToDocument(300f, 400f, 100f, 100f, 2.0f)
        assertEquals(100f, docX, 0.001f)
        assertEquals(150f, docY, 0.001f)
    }

    @Test
    fun testStrokeBoundingBoxCalculation() {
        val points = listOf(
            InkPoint(10f, 20f, 1.0f),
            InkPoint(50f, 80f, 1.0f)
        )
        val stroke = InkStroke(
            id = "test-1",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 4f,
            points = points
        )
        val bounds = stroke.bounds
        // left: 10 - 2 = 8, right: 50 + 2 = 52, top: 20 - 2 = 18, bottom: 80 + 2 = 82
        assertEquals(8f, bounds.left, 0.01f)
        assertEquals(52f, bounds.right, 0.01f)
        assertEquals(18f, bounds.top, 0.01f)
        assertEquals(82f, bounds.bottom, 0.01f)
    }

    @Test
    fun testStrokeEraserIntersection_hit() {
        val points = listOf(
            InkPoint(0f, 0f, 1.0f),
            InkPoint(100f, 0f, 1.0f)
        )
        val stroke = InkStroke(
            id = "line-1",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )
        // Erase at (50, 2) with radius 5 -> should hit line from (0,0) to (100,0)
        assertTrue(stroke.intersects(50f, 2f, 5f))
    }

    @Test
    fun testStrokeEraserIntersection_miss() {
        val points = listOf(
            InkPoint(0f, 0f, 1.0f),
            InkPoint(100f, 0f, 1.0f)
        )
        val stroke = InkStroke(
            id = "line-1",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )
        // Erase at (50, 50) with radius 5 -> far away, should miss
        assertFalse(stroke.intersects(50f, 50f, 5f))
    }

    @Test
    fun testUndoRedo_stack() {
        inkEngine.startStroke(10f, 10f)
        inkEngine.continueStroke(20f, 20f)
        val stroke1 = inkEngine.finishStroke()
        assertNotNull(stroke1)

        val strokes = listOf(stroke1!!)
        assertTrue(inkEngine.canUndo)
        assertFalse(inkEngine.canRedo)

        val undone = inkEngine.undo(strokes)
        assertEquals(0, undone.size)
        assertTrue(inkEngine.canRedo)

        val redone = inkEngine.redo(undone)
        assertEquals(1, redone.size)
        assertEquals(stroke1.id, redone[0].id)
    }
}

