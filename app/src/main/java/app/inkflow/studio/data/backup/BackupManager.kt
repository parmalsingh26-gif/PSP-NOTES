package app.inkflow.studio.data.backup

import android.content.Context
import app.inkflow.studio.data.local.AppDatabase
import app.inkflow.studio.data.local.StrokeFileStore
import app.inkflow.studio.data.local.entity.DocumentEntity
import app.inkflow.studio.data.local.entity.FolderEntity
import app.inkflow.studio.data.local.entity.PageEntity
import app.inkflow.studio.data.local.entity.StudyCardEntity
import app.inkflow.studio.domain.model.CanvasObject
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.PageImageObject
import app.inkflow.studio.domain.model.PageTextObject
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class BackupManifest(
    val version: Int = 2,
    val app: String = "InkFlow Studio Pro",
    val createdAt: Long = System.currentTimeMillis(),
    val documentCount: Int,
    val pageCount: Int,
    val cardCount: Int = 0,
    val checksum: String = ""
)

data class BackupPageData(
    val pageEntity: PageEntity,
    val strokes: List<InkStroke>,
    val texts: List<PageTextObject> = emptyList(),
    val images: List<PageImageObject> = emptyList()
)

data class BackupWhiteboardData(
    val documentId: String,
    val objects: List<CanvasObject>,
    val strokes: List<InkStroke> = emptyList()
)

data class BackupArchive(
    val manifest: BackupManifest,
    val folders: List<FolderEntity>,
    val documents: List<DocumentEntity>,
    val pagesWithStrokes: List<BackupPageData>,
    val whiteboards: List<BackupWhiteboardData> = emptyList(),
    val studyCards: List<StudyCardEntity> = emptyList()
)

class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val strokeFileStore: StrokeFileStore
) {
    private val gson = Gson()
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val pageDao = database.pageDao()

        val documents = mutableListOf<DocumentEntity>()
        val folders = mutableListOf<FolderEntity>()
        val pagesWithStrokes = mutableListOf<BackupPageData>()
        val whiteboards = mutableListOf<BackupWhiteboardData>()
        val studyCards = mutableListOf<StudyCardEntity>()

        // 1. Query all folders
        database.openHelper.readableDatabase.query("SELECT * FROM folders").use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val parentIdx = cursor.getColumnIndex("parentId")
            val nameIdx = cursor.getColumnIndex("name")
            val colorIdx = cursor.getColumnIndex("color")
            val createdIdx = cursor.getColumnIndex("createdAt")
            val trashIdx = cursor.getColumnIndex("isTrashed")
            while (cursor.moveToNext()) {
                folders.add(
                    FolderEntity(
                        id = cursor.getString(idIdx),
                        parentId = if (parentIdx >= 0 && !cursor.isNull(parentIdx)) cursor.getString(parentIdx) else null,
                        name = cursor.getString(nameIdx),
                        color = if (colorIdx >= 0) cursor.getLong(colorIdx) else 0xFF3B82F6,
                        createdAt = if (createdIdx >= 0) cursor.getLong(createdIdx) else System.currentTimeMillis(),
                        isTrashed = trashIdx >= 0 && cursor.getInt(trashIdx) == 1
                    )
                )
            }
        }

        // 2. Query all documents
        database.openHelper.readableDatabase.query("SELECT * FROM documents").use { cursor ->
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
                documents.add(
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
        }

        // 3. Gather all pages, strokes, texts, images, and whiteboard objects
        for (doc in documents) {
            val pages = pageDao.getPagesForDocumentSync(doc.id)
            for (p in pages) {
                val strokes = strokeFileStore.loadPageStrokes(p.id)
                val texts = strokeFileStore.loadPageTexts(p.id)
                val images = strokeFileStore.loadPageImages(p.id)
                pagesWithStrokes.add(
                    BackupPageData(
                        pageEntity = p,
                        strokes = strokes,
                        texts = texts,
                        images = images
                    )
                )
            }

            val wbObjs = strokeFileStore.loadWhiteboardObjects(doc.id)
            val wbStrokes = strokeFileStore.loadPageStrokes("wb_${doc.id}")
            if (wbObjs.isNotEmpty() || wbStrokes.isNotEmpty()) {
                whiteboards.add(BackupWhiteboardData(doc.id, wbObjs, wbStrokes))
            }
        }

        // 4. Query all study cards
        database.openHelper.readableDatabase.query("SELECT * FROM study_cards").use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val docIdIdx = cursor.getColumnIndex("documentId")
            val frontIdx = cursor.getColumnIndex("front")
            val backIdx = cursor.getColumnIndex("back")
            val repIdx = cursor.getColumnIndex("repetitionCount")
            val intIdx = cursor.getColumnIndex("intervalDays")
            val easeIdx = cursor.getColumnIndex("easeFactor")
            val nextIdx = cursor.getColumnIndex("nextReviewTimestamp")
            val lastIdx = cursor.getColumnIndex("lastReviewTimestamp")
            while (cursor.moveToNext()) {
                studyCards.add(
                    StudyCardEntity(
                        id = cursor.getString(idIdx),
                        documentId = cursor.getString(docIdIdx),
                        front = cursor.getString(frontIdx),
                        back = cursor.getString(backIdx),
                        repetitionCount = cursor.getInt(repIdx),
                        intervalDays = cursor.getInt(intIdx),
                        easeFactor = cursor.getFloat(easeIdx),
                        nextReviewTimestamp = cursor.getLong(nextIdx),
                        lastReviewTimestamp = if (lastIdx >= 0 && !cursor.isNull(lastIdx)) cursor.getLong(lastIdx) else null
                    )
                )
            }
        }

        val manifest = BackupManifest(
            documentCount = documents.size,
            pageCount = pagesWithStrokes.size,
            cardCount = studyCards.size,
            checksum = calculateSha256("${documents.size}_${pagesWithStrokes.size}")
        )

        val archive = BackupArchive(
            manifest = manifest,
            folders = folders,
            documents = documents,
            pagesWithStrokes = pagesWithStrokes,
            whiteboards = whiteboards,
            studyCards = studyCards
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

            // Validate SHA-256 manifest checksum if present
            val expectedChecksum = calculateSha256("${archive.documents.size}_${archive.pagesWithStrokes.size}")
            if (archive.manifest.checksum.isNotBlank() && archive.manifest.checksum != expectedChecksum) {
                return@withContext false
            }

            val documentDao = database.documentDao()
            val folderDao = database.folderDao()
            val pageDao = database.pageDao()
            val studyDao = database.studyDao()

            for (f in archive.folders) {
                folderDao.insertOrUpdate(f)
            }

            for (doc in archive.documents) {
                documentDao.insertOrUpdate(doc)
            }

            for (pData in archive.pagesWithStrokes) {
                pageDao.insertOrUpdate(pData.pageEntity)
                strokeFileStore.savePageStrokes(pData.pageEntity.id, pData.strokes)
                val texts = pData.texts
                if (!texts.isNullOrEmpty()) {
                    strokeFileStore.savePageTexts(pData.pageEntity.id, texts)
                }
                val images = pData.images
                if (!images.isNullOrEmpty()) {
                    strokeFileStore.savePageImages(pData.pageEntity.id, images)
                }
            }

            archive.whiteboards?.forEach { wb ->
                strokeFileStore.saveWhiteboardObjects(wb.documentId, wb.objects)
                if (!wb.strokes.isNullOrEmpty()) {
                    strokeFileStore.savePageStrokes("wb_${wb.documentId}", wb.strokes)
                }
            }

            archive.studyCards?.let { cards ->
                if (cards.isNotEmpty()) {
                    studyDao.insertAll(cards)
                }
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
