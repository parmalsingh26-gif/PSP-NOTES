package app.inkflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.inkflow.studio.domain.model.Page
import app.inkflow.studio.domain.model.PageTemplate

@Entity(tableName = "pages")
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageIndex: Int,
    val width: Float,
    val height: Float,
    val template: String,
    val isBookmarked: Boolean
) {
    fun toDomain(): Page = Page(
        id = id,
        documentId = documentId,
        index = pageIndex,
        width = width,
        height = height,
        template = try { PageTemplate.valueOf(template) } catch (e: Exception) { PageTemplate.RULED },
        isBookmarked = isBookmarked,
        strokes = emptyList() // Loaded on-demand from StrokeFileStore
    )

    companion object {
        fun fromDomain(page: Page): PageEntity = PageEntity(
            id = page.id,
            documentId = page.documentId,
            pageIndex = page.index,
            width = page.width,
            height = page.height,
            template = page.template.name,
            isBookmarked = page.isBookmarked
        )
    }
}

