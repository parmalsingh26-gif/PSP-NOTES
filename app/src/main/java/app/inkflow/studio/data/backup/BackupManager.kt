package app.inkflow.studio.data.backup

import android.content.Context
import app.inkflow.studio.data.local.AppDatabase
import app.inkflow.studio.data.local.StrokeFileStore
import app.inkflow.studio.data.local.entity.DocumentEntity
import app.inkflow.studio.data.local.entity.FolderEntity
import app.inkflow.studio.data.local.entity.PageEntity
import app.inkflow.studio.domain.model.InkStroke
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class BackupManifest(
    val version: Int = 1,
    val app: String = "InkFlow Studio",
    val createdAt: Long = System.currentTimeMillis(),
    val documentCount: Int,
    val pageCount: Int,
    val checksum: String = ""
)

data class BackupPageData(
    val pageEntity: PageEntity,
    val strokes: List<InkStroke>
)

data class BackupArchive(
    val manifest: BackupManifest,
    val folders: List<FolderEntity>,
    val documents: List<DocumentEntity>,
    val pagesWithStrokes: List<BackupPageData>
)

class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val strokeFileStore: StrokeFileStore
) {
    private val gson = Gson()
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val documentDao = database.documentDao()
        val folderDao = database.folderDao()
        val pageDao = database.pageDao()

        // Gather all entities
        val documents = mutableListOf<DocumentEntity>()
        val folders = mutableListOf<FolderEntity>()
        val pagesWithStrokes = mutableListOf<BackupPageData>()

        // Use direct queries for backup
        val allDocs = database.openHelper.readableDatabase.query("SELECT * FROM documents").use { cursor ->
            val list = mutableListOf<DocumentEntity>()
            val idIdx = cursor.getColumnIndex("id")
            val titleIdx = cursor.getColumnIndex("title")
            val typeIdx = cursor.getColumnIndex("type")
            val folderIdIdx = cursor.getColumnIndex("folderId")
            val createdIdx = cursor.getColumnIndex("createdAt")
            val updatedIdx = cursor.getColumnIndex("updatedAt")
            val favIdx = cursor.getColumnIndex("isFavorite")
            val trashIdx = cursor.getColumnIndex("isTrashed")
            val trashAtIdx = cursor.getColumnIndex("trashedAt")
            val pageCountIdx = cursor.getColumnIndex("pageCount")
            val lastPageIdx = cursor.getColumnIndex("lastOpenedPageIndex")
            val tagsIdx = cursor.getColumnIndex("tags")
            val pdfPathIdx = cursor.getColumnIndex("pdfFilePath")
            val audioPathIdx = cursor.getColumnIndex("audioFilePath")

            while (cursor.moveToNext()) {
                list.add(
                    DocumentEntity(
                        id = cursor.getString(idIdx),
                        title = cursor.getString(titleIdx),
                        type = cursor.getString(typeIdx),
                        folderId = if (folderIdIdx >= 0 && !cursor.isNull(folderIdIdx)) cursor.getString(folderIdIdx) else null,
                        createdAt = cursor.getLong(createdIdx),
                        updatedAt = cursor.getLong(updatedIdx),
                        isFavorite = cursor.getInt(favIdx) == 1,
                        isTrashed = cursor.getInt(trashIdx) == 1,
                        trashedAt = if (trashAtIdx >= 0 && !cursor.isNull(trashAtIdx)) cursor.getLong(trashAtIdx) else null,
                        pageCount = cursor.getInt(pageCountIdx),
                        lastOpenedPageIndex = cursor.getInt(lastPageIdx),
                        tags = cursor.getString(tagsIdx) ?: "",
                        pdfFilePath = if (pdfPathIdx >= 0 && !cursor.isNull(pdfPathIdx)) cursor.getString(pdfPathIdx) else null,
                        audioFilePath = if (audioPathIdx >= 0 && !cursor.isNull(audioPathIdx)) cursor.getString(audioPathIdx) else null
                    )
                )
            }
            list
        }
        documents.addAll(allDocs)

        for (doc in documents) {
            val pages = pageDao.getPagesForDocumentSync(doc.id)
            for (p in pages) {
                val strokes = strokeFileStore.loadPageStrokes(p.id)
                pagesWithStrokes.add(
                    BackupPageData(
                        pageEntity = p,
                        strokes = strokes
                    )
                )
            }
        }

        val manifest = BackupManifest(
            documentCount = documents.size,
            pageCount = pagesWithStrokes.size,
            checksum = calculateSha256("${documents.size}_${pagesWithStrokes.size}")
        )

        val archive = BackupArchive(
            manifest = manifest,
            folders = folders,
            documents = documents,
            pagesWithStrokes = pagesWithStrokes
        )

        val timestamp = System.currentTimeMillis()
        val backupFile = File(backupDir, "inkflow_backup_$timestamp.json")
        FileOutputStream(backupFile).use { fos ->
            fos.write(gson.toJson(archive).toByteArray(Charsets.UTF_8))
            fos.flush()
        }
        backupFile
    }

    suspend fun restoreBackup(backupFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!backupFile.exists() || backupFile.length() == 0L) return@withContext false

        try {
            val json = backupFile.readText(Charsets.UTF_8)
            val archive = gson.fromJson(json, BackupArchive::class.java) ?: return@withContext false

            val documentDao = database.documentDao()
            val folderDao = database.folderDao()
            val pageDao = database.pageDao()

            for (f in archive.folders) {
                folderDao.insertOrUpdate(f)
            }

            for (doc in archive.documents) {
                documentDao.insertOrUpdate(doc)
            }

            for (pData in archive.pagesWithStrokes) {
                pageDao.insertOrUpdate(pData.pageEntity)
                strokeFileStore.savePageStrokes(pData.pageEntity.id, pData.strokes)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun calculateSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
