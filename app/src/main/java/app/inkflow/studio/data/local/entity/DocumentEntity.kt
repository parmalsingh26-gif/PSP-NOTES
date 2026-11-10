package app.inkflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String, // DocumentType name
    val folderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val trashedAt: Long?,
    val pageCount: Int,
    val lastOpenedPageIndex: Int,
    val tags: String, // Comma-separated
    val pdfFilePath: String?,
    val audioFilePath: String?
) {
    fun toDomain(): Document = Document(
        id = id,
        title = title,
        type = try { DocumentType.valueOf(type) } catch (e: Exception) { DocumentType.NOTEBOOK },
        folderId = folderId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFavorite = isFavorite,
        isTrashed = isTrashed,
        trashedAt = trashedAt,
        pageCount = pageCount,
        lastOpenedPageIndex = lastOpenedPageIndex,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        pdfFilePath = pdfFilePath,
        audioFilePath = audioFilePath
    )

    companion object {
        fun fromDomain(doc: Document): DocumentEntity = DocumentEntity(
            id = doc.id,
            title = doc.title,
            type = doc.type.name,
            folderId = doc.folderId,
            createdAt = doc.createdAt,
            updatedAt = doc.updatedAt,
            isFavorite = doc.isFavorite,
            isTrashed = doc.isTrashed,
            trashedAt = doc.trashedAt,
            pageCount = doc.pageCount,
            lastOpenedPageIndex = doc.lastOpenedPageIndex,
            tags = doc.tags.joinToString(","),
            pdfFilePath = doc.pdfFilePath,
            audioFilePath = doc.audioFilePath
        )
    }
}

