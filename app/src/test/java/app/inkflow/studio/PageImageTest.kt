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

    @Test
    fun testPageImageCropParametersAndAspectRatio() {
        val original = PageImageObject(
            id = "img-crop-1",
            pageId = "p1",
            filePath = "/path/test.png",
            x = 50f,
            y = 50f,
            width = 200f,
            height = 150f,
            cropLeft = 0.1f,
            cropTop = 0.15f,
            cropRight = 0.9f,
            cropBottom = 0.85f,
            aspectRatioLocked = true
        )

        val json = gson.toJson(listOf(original))
        val type = object : TypeToken<List<PageImageObject>>() {}.type
        val loaded = gson.fromJson<List<PageImageObject>>(json, type)[0]

        assertEquals(0.1f, loaded.cropLeft, 0.001f)
        assertEquals(0.15f, loaded.cropTop, 0.001f)
        assertEquals(0.9f, loaded.cropRight, 0.001f)
        assertEquals(0.85f, loaded.cropBottom, 0.001f)
        assertTrue(loaded.aspectRatioLocked)
    }

    @Test
    fun testPageImagePageIdSeparation() {
        // Verify images on different pages maintain separate pageIds
        val imgPage1 = PageImageObject(id = "i1", pageId = "page-1", filePath = "/p1.png", x = 10f, y = 10f)
        val imgPage2 = PageImageObject(id = "i2", pageId = "page-2", filePath = "/p2.png", x = 20f, y = 20f)

        val allImages = listOf(imgPage1, imgPage2)
        val p1Images = allImages.filter { it.pageId == "page-1" }
        val p2Images = allImages.filter { it.pageId == "page-2" }

        assertEquals(1, p1Images.size)
        assertEquals("i1", p1Images[0].id)
        assertEquals(1, p2Images.size)
        assertEquals("i2", p2Images[0].id)
    }
}

