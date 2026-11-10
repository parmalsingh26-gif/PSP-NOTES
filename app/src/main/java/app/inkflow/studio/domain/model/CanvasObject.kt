package app.inkflow.studio.domain.model

enum class CanvasObjectType {
    STICKY_NOTE,
    SHAPE_RECTANGLE,
    SHAPE_CIRCLE,
    SHAPE_DIAMOND,
    SHAPE_ARROW,
    TEXT_CARD
}

data class CanvasObject(
    val id: String,
    val type: CanvasObjectType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val content: String = "",
    val color: Long = 0xFFFEF08A, // Default pastel yellow
    val strokeColor: Long = 0xFF334155,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val zIndex: Int = 0
)
