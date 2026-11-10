package app.inkflow.studio

import app.inkflow.studio.domain.model.InkPoint
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class StrokeSerializationTest {
    private val gson = Gson()

    @Test
    fun testStrokeSerializationAndDeserialization() {
        val originalStrokes = listOf(
            InkStroke(
                id = "stroke-abc-123",
                tool = ToolType.HIGHLIGHTER,
                color = 0xFFFDE047,
                width = 8.0f,
                opacity = 0.35f,
                points = listOf(
                    InkPoint(12.5f, 45.2f, 0.85f, 1794320000),
                    InkPoint(18.3f, 50.1f, 0.92f, 1794320016)
                ),
                audioTimestampMs = 2450
            )
        )

        val json = gson.toJson(originalStrokes)
        assertNotNull(json)
        assertTrue(json.contains("stroke-abc-123"))
        assertTrue(json.contains("HIGHLIGHTER"))

        val type = object : TypeToken<List<InkStroke>>() {}.type
        val deserialized = gson.fromJson<List<InkStroke>>(json, type)

        assertEquals(1, deserialized.size)
        val stroke = deserialized[0]
        assertEquals("stroke-abc-123", stroke.id)
        assertEquals(ToolType.HIGHLIGHTER, stroke.tool)
        assertEquals(8.0f, stroke.width, 0.001f)
        assertEquals(0.35f, stroke.opacity, 0.001f)
        assertEquals(2, stroke.points.size)
        assertEquals(12.5f, stroke.points[0].x, 0.001f)
        assertEquals(45.2f, stroke.points[0].y, 0.001f)
        assertEquals(2450L, stroke.audioTimestampMs)
    }
}

