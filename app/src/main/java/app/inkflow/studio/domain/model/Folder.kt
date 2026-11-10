package app.inkflow.studio.domain.model

data class Folder(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val color: Long = 0xFF2563EB,
    val createdAt: Long = System.currentTimeMillis(),
    val isTrashed: Boolean = false
)

