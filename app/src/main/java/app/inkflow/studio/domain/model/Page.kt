package app.inkflow.studio.domain.model

data class Page(
    val id: String,
    val documentId: String,
    val index: Int,
    val width: Float = 595f,  // Standard A4 point width
    val height: Float = 842f, // Standard A4 point height
    val template: PageTemplate = PageTemplate.RULED,
    val isBookmarked: Boolean = false,
    val strokes: List<InkStroke> = emptyList()
)

