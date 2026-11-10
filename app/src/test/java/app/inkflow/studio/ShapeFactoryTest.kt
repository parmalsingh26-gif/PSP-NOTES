package app.inkflow.studio

import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.ink.PresetShapeType
import app.inkflow.studio.feature.ink.ShapeFactory
import org.junit.Assert.*
import org.junit.Test

class ShapeFactoryTest {

    @Test
    fun testCreateRectangle() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.RECTANGLE,
            startX = 50f,
            startY = 50f,
            endX = 250f,
            endY = 150f,
            color = 0xFF2563EB,
            width = 3f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        assertEquals(5, stroke.points.size)
        // Closed loop: first and last points match
        assertEquals(stroke.points.first().x, stroke.points.last().x, 0.01f)
        assertEquals(stroke.points.first().y, stroke.points.last().y, 0.01f)
        assertEquals(50f, stroke.points.minOf { it.x }, 0.01f)
        assertEquals(250f, stroke.points.maxOf { it.x }, 0.01f)
        assertEquals(50f, stroke.points.minOf { it.y }, 0.01f)
        assertEquals(150f, stroke.points.maxOf { it.y }, 0.01f)
    }

    @Test
    fun testCreateCircle() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.CIRCLE,
            startX = 100f,
            startY = 100f,
            endX = 200f,
            endY = 200f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        assertTrue(stroke.points.size >= 36)
        assertEquals(100f, stroke.points.minOf { it.x }, 1.0f)
        assertEquals(200f, stroke.points.maxOf { it.x }, 1.0f)
    }

    @Test
    fun testCreateLine() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.LINE,
            startX = 10f,
            startY = 20f,
            endX = 150f,
            endY = 80f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        assertEquals(2, stroke.points.size)
        assertEquals(10f, stroke.points[0].x, 0.01f)
        assertEquals(150f, stroke.points[1].x, 0.01f)
    }

    @Test
    fun testCreateArrow() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.ARROW,
            startX = 0f,
            startY = 0f,
            endX = 100f,
            endY = 0f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        // Contains shaft plus arrowhead barb points
        assertTrue(stroke.points.size >= 4)
        assertEquals(0f, stroke.points[0].x, 0.01f)
        assertEquals(100f, stroke.points[1].x, 0.01f)
    }

    @Test
    fun testCreateTriangle() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.TRIANGLE,
            startX = 50f,
            startY = 50f,
            endX = 150f,
            endY = 150f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        assertEquals(4, stroke.points.size)
        // Apex is centered at x = 100
        assertEquals(100f, stroke.points[0].x, 0.01f)
        assertEquals(50f, stroke.points[0].y, 0.01f)
    }

    @Test
    fun testCreateStar() {
        val stroke = ShapeFactory.createPresetShape(
            type = PresetShapeType.STAR,
            startX = 0f,
            startY = 0f,
            endX = 100f,
            endY = 100f
        )
        assertEquals(ToolType.SHAPE, stroke.tool)
        // 5 outer points + 5 inner points + 1 closing point = 11 points
        assertEquals(11, stroke.points.size)
    }
}
