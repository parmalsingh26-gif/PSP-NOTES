package app.inkflow.studio

import app.inkflow.studio.domain.model.PageImageObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class PageImageTest {
    private val gson = Gson()

    @Test
    fun testPageImageSerializationAndTransform() {
        val original = PageImageObject(
            id = "img-test-123",
            pageId = "page-456",
            filePath = "/data/user/0/app.inkflow.studio/files/images/diagram.png",
            x = 80f,
            y = 120f,
            width = 300f,
            height = 200f,
            rotationDegrees = 90f,
            isFlippedHorizontal = true,
            isFlippedVertical = false,
            isLocked = false,
            opacity = 0.95f
        )

        val json = gson.toJson(listOf(original))
        assertNotNull(json)
        assertTrue(json.contains("img-test-123"))
        assertTrue(json.contains("diagram.png"))

        val type = object : TypeToken<List<PageImageObject>>() {}.type
        val deserialized = gson.fromJson<List<PageImageObject>>(json, type)

        assertEquals(1, deserialized.size)
        val loaded = deserialized[0]
        assertEquals("img-test-123", loaded.id)
        assertEquals("page-456", loaded.pageId)
        assertEquals(80f, loaded.x, 0.01f)
        assertEquals(120f, loaded.y, 0.01f)
        assertEquals(300f, loaded.width, 0.01f)
        assertEquals(200f, loaded.height, 0.01f)
        assertEquals(90f, loaded.rotationDegrees, 0.01f)
        assertTrue(loaded.isFlippedHorizontal)
        assertFalse(loaded.isFlippedVertical)
        assertFalse(loaded.isLocked)
        assertEquals(0.95f, loaded.opacity, 0.01f)
    }

    @Test
    fun testPageImageLockAndDuplicate() {
        val original = PageImageObject(
            id = "img-1",
            pageId = "p1",
            filePath = "/path/test.png",
            x = 50f,
            y = 50f,
            width = 100f,
            height = 100f
        )

        // Lock toggle
        val locked = original.copy(isLocked = true)
        assertTrue(locked.isLocked)

        // Duplicate with offset
        val duplicate = original.copy(
            id = "img-2",
            x = original.x + 20f,
            y = original.y + 20f
        )
        assertEquals("img-2", duplicate.id)
        assertEquals(70f, duplicate.x, 0.01f)
        assertEquals(70f, duplicate.y, 0.01f)
        assertEquals(original.width, duplicate.width, 0.01f)
    }
}
