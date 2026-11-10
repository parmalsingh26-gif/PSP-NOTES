package app.inkflow.studio.domain.model

data class PageTextObject(
    val id: String,
    val pageId: String,
    val x: Float,
    val y: Float,
    val text: String,
    val fontSizeSp: Float = 16f,
    val color: Long = 0xFF0F172A,
    val backgroundColor: Long? = 0x80FEF08A // Translucent soft yellow
)
