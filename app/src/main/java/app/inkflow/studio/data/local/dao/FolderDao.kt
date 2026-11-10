package app.inkflow.studio.data.local.dao

import androidx.room.*
import app.inkflow.studio.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE isTrashed = 0 AND (parentId = :parentId OR (:parentId IS NULL AND parentId IS NULL)) ORDER BY name ASC")
    fun getFolders(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE isTrashed = 0 ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(folder: FolderEntity)

    @Query("UPDATE folders SET isTrashed = 1 WHERE id = :id")
    suspend fun moveToTrash(id: String)

    @Query("UPDATE folders SET isTrashed = 0 WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun permanentDelete(id: String)
}

