package app.inkflow.studio.domain.model

data class Document(
    val id: String,
    val title: String,
    val type: DocumentType,
    val folderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val pageCount: Int = 1,
    val lastOpenedPageIndex: Int = 0,
    val tags: List<String> = emptyList(),
    val pdfFilePath: String? = null,
    val audioFilePath: String? = null
)

