package app.inkflow.studio

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.ink.RecognizedShapeType
import app.inkflow.studio.feature.ink.ShapeRecognizer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {

    @Test
    fun testStraightLineRecognition() {
        // Points roughly on a line from (10, 10) to (100, 100) with minor jitter
        val points = listOf(
            InkPoint(10f, 10f),
            InkPoint(25f, 24.5f),
            InkPoint(50f, 50.8f),
            InkPoint(75f, 74.2f),
            InkPoint(100f, 100f)
        )
        val stroke = InkStroke(
            id = "test-line",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )

        val result = ShapeRecognizer.recognize(stroke)
        assertEquals(RecognizedShapeType.LINE, result.type)
        assertEquals(2, result.snappedStroke.points.size)
        assertEquals(10f, result.snappedStroke.points.first().x, 0.01f)
        assertEquals(100f, result.snappedStroke.points.last().x, 0.01f)
    }

    @Test
    fun testCircleRecognition() {
        // Generate points roughly on a circle of radius 50 centered at (100, 100)
        val points = mutableListOf<InkPoint>()
        val numSamples = 24
        for (i in 0..numSamples) {
            val angle = (i.toFloat() / numSamples) * (2 * Math.PI)
            val jitter = if (i % 2 == 0) 1.5f else -1.5f
            val r = 50f + jitter
            val x = 100f + r * cos(angle).toFloat()
            val y = 100f + r * sin(angle).toFloat()
            points.add(InkPoint(x, y))
        }

        val stroke = InkStroke(
            id = "test-circle",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )

        val result = ShapeRecognizer.recognize(stroke)
        assertEquals(RecognizedShapeType.CIRCLE, result.type)
        assertTrue(result.snappedStroke.points.size > 20)
    }

    @Test
    fun testRectangleRecognition() {
        // Points roughly on a rectangle with corners (20, 20) and (120, 80)
        val points = mutableListOf<InkPoint>()
        // Top edge: (20, 20) to (120, 20)
        points.add(InkPoint(20f, 20f))
        points.add(InkPoint(70f, 20.5f))
        points.add(InkPoint(120f, 20f))
        // Right edge: (120, 20) to (120, 80)
        points.add(InkPoint(119.5f, 50f))
        points.add(InkPoint(120f, 80f))
        // Bottom edge: (120, 80) to (20, 80)
        points.add(InkPoint(70f, 79.5f))
        points.add(InkPoint(20f, 80f))
        // Left edge: (20, 80) to (20, 20)
        points.add(InkPoint(20.5f, 50f))
        points.add(InkPoint(20f, 20f))

        val stroke = InkStroke(
            id = "test-rect",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )

        val result = ShapeRecognizer.recognize(stroke)
        assertEquals(RecognizedShapeType.RECTANGLE, result.type)
        assertEquals(5, result.snappedStroke.points.size)
        assertEquals(20f, result.snappedStroke.points[0].x, 0.5f)
        assertEquals(20f, result.snappedStroke.points[0].y, 0.5f)
        assertEquals(120f, result.snappedStroke.points[1].x, 0.5f)
    }

    @Test
    fun testRandomSquiggleIsNotRecognized() {
        val points = listOf(
            InkPoint(10f, 10f),
            InkPoint(80f, 20f),
            InkPoint(15f, 90f),
            InkPoint(70f, 100f),
            InkPoint(5f, 40f)
        )
        val stroke = InkStroke(
            id = "test-random",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )

        val result = ShapeRecognizer.recognize(stroke)
        assertEquals(RecognizedShapeType.NONE, result.type)
    }

    @Test
    fun testTriangleRecognition() {
        // Points along a triangle with vertices (100, 20), (180, 160), (20, 160)
        val points = listOf(
            InkPoint(100f, 20f),
            InkPoint(140f, 90f),
            InkPoint(180f, 160f),
            InkPoint(100f, 160f),
            InkPoint(20f, 160f),
            InkPoint(60f, 90f),
            InkPoint(100f, 20f)
        )
        val stroke = InkStroke(
            id = "test-triangle",
            tool = ToolType.PEN,
            color = 0xFF000000,
            width = 2f,
            points = points
        )
        val result = ShapeRecognizer.recognize(stroke)
        assertEquals(RecognizedShapeType.TRIANGLE, result.type)
        assertEquals(4, result.snappedStroke.points.size)
    }
}

