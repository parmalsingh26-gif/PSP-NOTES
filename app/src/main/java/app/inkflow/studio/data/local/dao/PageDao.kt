package app.inkflow.studio.data.local.dao

import androidx.room.*
import app.inkflow.studio.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    fun getPagesForDocument(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    suspend fun getPagesForDocumentSync(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageById(pageId: String): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePage(pageId: String)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deleteAllPagesForDocument(documentId: String)

    @Query("UPDATE pages SET isBookmarked = :isBookmarked WHERE id = :pageId")
    suspend fun setBookmark(pageId: String, isBookmarked: Boolean)

    @Query("UPDATE pages SET template = :template WHERE id = :pageId")
    suspend fun updateTemplate(pageId: String, template: String)

    @Query("SELECT COUNT(*) FROM pages WHERE documentId = :documentId")
    suspend fun getPageCount(documentId: String): Int
}

