package app.inkflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.inkflow.studio.domain.model.Folder

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val color: Long,
    val createdAt: Long,
    val isTrashed: Boolean
) {
    fun toDomain(): Folder = Folder(
        id = id,
        parentId = parentId,
        name = name,
        color = color,
        createdAt = createdAt,
        isTrashed = isTrashed
    )

    companion object {
        fun fromDomain(folder: Folder): FolderEntity = FolderEntity(
            id = folder.id,
            parentId = folder.parentId,
            name = folder.name,
            color = folder.color,
            createdAt = folder.createdAt,
            isTrashed = folder.isTrashed
        )
    }
}

