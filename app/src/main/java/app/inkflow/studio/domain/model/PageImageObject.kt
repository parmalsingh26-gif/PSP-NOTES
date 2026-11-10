package app.inkflow.studio.domain.model

data class PageImageObject(
    val id: String,
    val pageId: String,
    val filePath: String,
    val x: Float,
    val y: Float,
    val width: Float = 220f,
    val height: Float = 220f,
    val rotationDegrees: Float = 0f,
    val isFlippedHorizontal: Boolean = false,
    val isFlippedVertical: Boolean = false,
    val isLocked: Boolean = false,
    val opacity: Float = 1.0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val aspectRatioLocked: Boolean = true,
    val zIndex: Int = 0
)
