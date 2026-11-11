package app.inkflow.studio

import app.inkflow.studio.domain.model.PageImageObject
import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.editor.applyImageCrop
import app.inkflow.studio.feature.editor.computeUncroppedFrame
import app.inkflow.studio.feature.editor.isPointInsideImage
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
    fun testTapInsideImageSelectsForReEditing() {
        val image = PageImageObject(
            id = "img-reedit",
            pageId = "p1",
            filePath = "/img.png",
            x = 100f,
            y = 150f,
            width = 200f,
            height = 180f
        )
        val images = listOf(image)

        // Point inside image
        val insideX = 150f
        val insideY = 200f
        val tappedInside = images.lastOrNull { img ->
            insideX in img.x..(img.x + img.width) && insideY in img.y..(img.y + img.height)
        }
        assertNotNull(tappedInside)
        assertEquals("img-reedit", tappedInside?.id)

        // Point outside image
        val outsideX = 50f
        val outsideY = 50f
        val tappedOutside = images.lastOrNull { img ->
            outsideX in img.x..(img.x + img.width) && outsideY in img.y..(img.y + img.height)
        }
        assertNull(tappedOutside)
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

    @Test
    fun testNonDistortingInteractiveCropAndReEditFrame() {
        val original = PageImageObject(
            id = "img-crop-real",
            pageId = "p1",
            filePath = "/sample.png",
            x = 100f,
            y = 100f,
            width = 200f,
            height = 100f
        )

        val cropped = applyImageCrop(
            img = original,
            liveLeft = 0.25f,
            liveTop = 0.10f,
            liveRight = 0.75f,
            liveBottom = 0.90f
        )

        // Displayed box shrinks proportionally to cropped fractions (no stretching)
        assertEquals(150f, cropped.x, 0.05f)
        assertEquals(110f, cropped.y, 0.05f)
        assertEquals(100f, cropped.width, 0.05f)
        assertEquals(80f, cropped.height, 0.05f)
        assertEquals(0.25f, cropped.cropLeft, 0.001f)
        assertEquals(0.75f, cropped.cropRight, 0.001f)

        // Re-entering crop mode reconstructs exact original uncropped outer bounds
        val reconstructed = computeUncroppedFrame(cropped)
        assertEquals(100f, reconstructed.fullX, 0.05f)
        assertEquals(100f, reconstructed.fullY, 0.05f)
        assertEquals(200f, reconstructed.fullW, 0.05f)
        assertEquals(100f, reconstructed.fullH, 0.05f)
    }

    @Test
    fun testRotatedImageHitDetectionAndLaserToolAvailability() {
        // Wide box 200x60 rotated 90 degrees becomes tall 60x200 around center (200, 130)
        val rotated = PageImageObject(
            id = "img-rot",
            pageId = "p1",
            filePath = "/rot.png",
            x = 100f,
            y = 100f,
            width = 200f,
            height = 60f,
            rotationDegrees = 90f
        )

        // Center (200, 130): tip at (200, 210) is inside rotated height, outside unrotated 100..160
        assertTrue(isPointInsideImage(200f, 210f, rotated, pad = 2f))
        // Far wing (280, 130) was inside unrotated width, now outside rotated bounds
        assertFalse(isPointInsideImage(280f, 130f, rotated, pad = 2f))

        assertTrue(ToolType.entries.contains(ToolType.LASER))
    }
}

