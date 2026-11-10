package app.inkflow.studio.data.repository

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.inkflow.studio.data.local.AppDatabase
import app.inkflow.studio.data.local.PdfFileStore
import app.inkflow.studio.data.local.StrokeFileStore
import app.inkflow.studio.data.local.entity.DocumentEntity
import app.inkflow.studio.data.local.entity.FolderEntity
import app.inkflow.studio.data.local.entity.PageEntity
import app.inkflow.studio.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DocumentRepository(
    private val database: AppDatabase,
    private val strokeFileStore: StrokeFileStore,
    private val pdfFileStore: PdfFileStore
) {
    private val documentDao = database.documentDao()
    private val folderDao = database.folderDao()
    private val pageDao = database.pageDao()

    // --- Document Queries ---
    fun getAllActiveDocuments(): Flow<List<Document>> =
        documentDao.getAllActiveDocuments().map { list -> list.map { it.toDomain() } }

    fun getDocumentsInFolder(folderId: String?): Flow<List<Document>> =
        documentDao.getDocumentsInFolder(folderId).map { list -> list.map { it.toDomain() } }

    fun getFavoriteDocuments(): Flow<List<Document>> =
        documentDao.getFavoriteDocuments().map { list -> list.map { it.toDomain() } }

    fun getTrashedDocuments(): Flow<List<Document>> =
        documentDao.getTrashedDocuments().map { list -> list.map { it.toDomain() } }

    fun getDocument(id: String): Flow<Document?> =
        documentDao.getDocumentByIdFlow(id).map { it?.toDomain() }

    suspend fun getDocumentSync(id: String): Document? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(id)?.toDomain()
    }

    fun searchDocuments(query: String): Flow<List<Document>> =
        documentDao.searchDocuments(query).map { list -> list.map { it.toDomain() } }

    // --- Document Mutations ---
    suspend fun createNotebook(
        title: String,
        template: PageTemplate = PageTemplate.RULED,
        folderId: String? = null
    ): Document = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val doc = Document(
            id = docId,
            title = title.ifBlank { "Untitled Notebook" },
            type = DocumentType.NOTEBOOK,
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            pageCount = 1
        )
        documentDao.insertOrUpdate(DocumentEntity.fromDomain(doc))

        // Create initial page
        val pageId = UUID.randomUUID().toString()
        val firstPage = Page(
            id = pageId,
            documentId = docId,
            index = 0,
            template = template
        )
        pageDao.insertOrUpdate(PageEntity.fromDomain(firstPage))
        doc
    }

    suspend fun createPdfDocument(
        title: String,
        uri: Uri,
        folderId: String? = null
    ): Document = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()
        val savedPath = pdfFileStore.copyPdfFromUri(docId, uri)
        val file = File(savedPath)
        val now = System.currentTimeMillis()

        var actualPageCount = 1
        val pages = mutableListOf<PageEntity>()

        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                actualPageCount = renderer.pageCount.coerceAtLeast(1)

                for (idx in 0 until actualPageCount) {
                    val pdfPage = renderer.openPage(idx)
                    val w = pdfPage.width.toFloat()
                    val h = pdfPage.height.toFloat()
                    pdfPage.close()

                    pages.add(
                        PageEntity(
                            id = UUID.randomUUID().toString(),
                            documentId = docId,
                            pageIndex = idx,
                            width = if (w > 0f) w else 595f,
                            height = if (h > 0f) h else 842f,
                            template = PageTemplate.BLANK.name,
                            isBookmarked = false
                        )
                    )
                }
                renderer.close()
                pfd.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback for empty list
        if (pages.isEmpty()) {
            pages.add(
                PageEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = docId,
                    pageIndex = 0,
                    width = 595f,
                    height = 842f,
                    template = PageTemplate.BLANK.name,
                    isBookmarked = false
                )
            )
        }

        val doc = Document(
            id = docId,
            title = title.ifBlank { "Imported PDF" },
            type = DocumentType.PDF,
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            pageCount = actualPageCount,
            pdfFilePath = savedPath
        )
        documentDao.insertOrUpdate(DocumentEntity.fromDomain(doc))
        pageDao.insertAll(pages)
        doc
    }

    suspend fun createWhiteboard(
        title: String,
        folderId: String? = null
    ): Document = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val doc = Document(
            id = docId,
            title = title.ifBlank { "Untitled Whiteboard" },
            type = DocumentType.WHITEBOARD,
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            pageCount = 1
        )
        documentDao.insertOrUpdate(DocumentEntity.fromDomain(doc))

        val pageId = UUID.randomUUID().toString()
        val firstPage = Page(
            id = pageId,
            documentId = docId,
            index = 0,
            template = PageTemplate.GRID
        )
        pageDao.insertOrUpdate(PageEntity.fromDomain(firstPage))
        doc
    }

    suspend fun renameDocument(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        documentDao.rename(id, newTitle)
    }

    suspend fun updateDocumentTags(id: String, tags: List<String>) = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(id) ?: return@withContext
        val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(",")
        documentDao.update(doc.copy(tags = cleanTags, updatedAt = System.currentTimeMillis()))
    }

    suspend fun moveDocumentToFolder(id: String, folderId: String?) = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(id) ?: return@withContext
        documentDao.update(doc.copy(folderId = folderId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun duplicateDocument(id: String): Document? = withContext(Dispatchers.IO) {
        val originalDoc = documentDao.getDocumentById(id) ?: return@withContext null
        val newDocId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val duplicatedDocEntity = originalDoc.copy(
            id = newDocId,
            title = "${originalDoc.title} (Copy)",
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isTrashed = false,
            trashedAt = null
        )
        documentDao.insertOrUpdate(duplicatedDocEntity)

        val originalPages = pageDao.getPagesForDocumentSync(id)
        for (p in originalPages) {
            val newPageId = UUID.randomUUID().toString()
            val newPageEntity = p.copy(id = newPageId, documentId = newDocId)
            pageDao.insertOrUpdate(newPageEntity)

            val strokes = strokeFileStore.loadPageStrokes(p.id).map { it.copy(id = UUID.randomUUID().toString()) }
            if (strokes.isNotEmpty()) strokeFileStore.savePageStrokes(newPageId, strokes)

            val texts = strokeFileStore.loadPageTexts(p.id).map { it.copy(id = UUID.randomUUID().toString(), pageId = newPageId) }
            if (texts.isNotEmpty()) strokeFileStore.savePageTexts(newPageId, texts)

            val images = strokeFileStore.loadPageImages(p.id).map { it.copy(id = UUID.randomUUID().toString(), pageId = newPageId) }
            if (images.isNotEmpty()) strokeFileStore.savePageImages(newPageId, images)
        }

        val wbObjects = strokeFileStore.loadWhiteboardObjects(id).map { it.copy(id = UUID.randomUUID().toString()) }
        if (wbObjects.isNotEmpty()) {
            strokeFileStore.saveWhiteboardObjects(newDocId, wbObjects)
        }
        val wbStrokes = strokeFileStore.loadPageStrokes("wb_$id").map { it.copy(id = UUID.randomUUID().toString()) }
        if (wbStrokes.isNotEmpty()) {
            strokeFileStore.savePageStrokes("wb_$newDocId", wbStrokes)
        }

        duplicatedDocEntity.toDomain()
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        documentDao.setFavorite(id, isFavorite)
    }

    suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) {
        documentDao.moveToTrash(id)
    }

    suspend fun restoreFromTrash(id: String) = withContext(Dispatchers.IO) {
        documentDao.restoreFromTrash(id)
    }

    suspend fun permanentDelete(id: String) = withContext(Dispatchers.IO) {
        val pages = pageDao.getPagesForDocumentSync(id)
        for (p in pages) {
            strokeFileStore.deletePageStrokes(p.id)
        }
        strokeFileStore.deletePageStrokes("wb_$id")
        pageDao.deleteAllPagesForDocument(id)
        pdfFileStore.deletePdf(id)
        documentDao.permanentDelete(id)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        documentDao.emptyTrash()
    }

    suspend fun updateLastOpenedPage(documentId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        documentDao.updateLastOpenedPage(documentId, pageIndex)
    }

    // --- Page Management ---
    fun getPagesForDocument(documentId: String): Flow<List<Page>> =
        pageDao.getPagesForDocument(documentId).map { list -> list.map { it.toDomain() } }

    suspend fun getPagesForDocumentSync(documentId: String): List<Page> = withContext(Dispatchers.IO) {
        pageDao.getPagesForDocumentSync(documentId).map { it.toDomain() }
    }

    suspend fun addPage(documentId: String, template: PageTemplate = PageTemplate.RULED): Page = withContext(Dispatchers.IO) {
        val currentCount = pageDao.getPageCount(documentId)
        val newPage = Page(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            index = currentCount,
            template = template
        )
        pageDao.insertOrUpdate(PageEntity.fromDomain(newPage))
        
        // Update document page count
        documentDao.getDocumentById(documentId)?.let { doc ->
            documentDao.update(doc.copy(pageCount = currentCount + 1, updatedAt = System.currentTimeMillis()))
        }
        newPage
    }

    /**
     * Inserts a new page with the specified template immediately after [afterIndex] (0-based).
     * Shifts subsequent page indices by +1 to maintain continuous sequential ordering.
     */
    suspend fun insertPageAt(
        documentId: String,
        afterIndex: Int,
        template: PageTemplate = PageTemplate.RULED
    ): Page = withContext(Dispatchers.IO) {
        val allPages = pageDao.getPagesForDocumentSync(documentId)
        val targetInsertIndex = (afterIndex + 1).coerceIn(0, allPages.size)

        // Shift all pages that come at or after the target insertion index
        for (page in allPages.filter { it.pageIndex >= targetInsertIndex }) {
            pageDao.insertOrUpdate(page.copy(pageIndex = page.pageIndex + 1))
        }

        val newPage = Page(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            index = targetInsertIndex,
            template = template
        )
        pageDao.insertOrUpdate(PageEntity.fromDomain(newPage))

        // Update document page count
        documentDao.getDocumentById(documentId)?.let { doc ->
            documentDao.update(doc.copy(pageCount = allPages.size + 1, updatedAt = System.currentTimeMillis()))
        }
        newPage
    }

    suspend fun setPageBookmark(pageId: String, isBookmarked: Boolean) = withContext(Dispatchers.IO) {
        pageDao.setBookmark(pageId, isBookmarked)
    }

    suspend fun updatePageTemplate(pageId: String, template: PageTemplate) = withContext(Dispatchers.IO) {
        pageDao.updateTemplate(pageId, template.name)
    }

    suspend fun deletePage(pageId: String, documentId: String): Boolean = withContext(Dispatchers.IO) {
        val pages = pageDao.getPagesForDocumentSync(documentId)
        if (pages.size <= 1) {
            return@withContext false
        }
        pageDao.deletePage(pageId)
        strokeFileStore.deletePageStrokes(pageId)

        val remaining = pageDao.getPagesForDocumentSync(documentId)
        for ((idx, p) in remaining.withIndex()) {
            pageDao.insertOrUpdate(p.copy(pageIndex = idx))
        }

        documentDao.getDocumentById(documentId)?.let { doc ->
            documentDao.update(doc.copy(pageCount = remaining.size, updatedAt = System.currentTimeMillis()))
        }
        true
    }

    suspend fun duplicatePage(pageId: String, documentId: String): Page? = withContext(Dispatchers.IO) {
        val originalEntity = pageDao.getPageById(pageId) ?: return@withContext null
        val originalStrokes = strokeFileStore.loadPageStrokes(pageId)
        val originalTexts = strokeFileStore.loadPageTexts(pageId)
        val originalImages = strokeFileStore.loadPageImages(pageId)

        val newPageId = UUID.randomUUID().toString()
        val currentCount = pageDao.getPageCount(documentId)
        val duplicatedPage = PageEntity(
            id = newPageId,
            documentId = documentId,
            pageIndex = currentCount,
            width = originalEntity.width,
            height = originalEntity.height,
            template = originalEntity.template,
            isBookmarked = false
        )
        pageDao.insertOrUpdate(duplicatedPage)

        val duplicatedStrokes = originalStrokes.map { it.copy(id = UUID.randomUUID().toString()) }
        strokeFileStore.savePageStrokes(newPageId, duplicatedStrokes)

        if (originalTexts.isNotEmpty()) {
            val duplicatedTexts = originalTexts.map { it.copy(id = UUID.randomUUID().toString(), pageId = newPageId) }
            strokeFileStore.savePageTexts(newPageId, duplicatedTexts)
        }

        if (originalImages.isNotEmpty()) {
            val duplicatedImages = originalImages.map { it.copy(id = UUID.randomUUID().toString(), pageId = newPageId) }
            strokeFileStore.savePageImages(newPageId, duplicatedImages)
        }

        documentDao.getDocumentById(documentId)?.let { doc ->
            documentDao.update(doc.copy(pageCount = currentCount + 1, updatedAt = System.currentTimeMillis()))
        }

        duplicatedPage.toDomain().copy(strokes = duplicatedStrokes)
    }

    // --- Vector Strokes and Objects ---
    suspend fun savePageStrokes(pageId: String, strokes: List<InkStroke>) = withContext(Dispatchers.IO) {
        strokeFileStore.savePageStrokes(pageId, strokes)
    }

    suspend fun loadPageStrokes(pageId: String): List<InkStroke> = withContext(Dispatchers.IO) {
        strokeFileStore.loadPageStrokes(pageId)
    }

    suspend fun savePageTexts(pageId: String, texts: List<PageTextObject>) = withContext(Dispatchers.IO) {
        strokeFileStore.savePageTexts(pageId, texts)
    }

    suspend fun loadPageTexts(pageId: String): List<PageTextObject> = withContext(Dispatchers.IO) {
        strokeFileStore.loadPageTexts(pageId)
    }

    suspend fun savePageImages(pageId: String, images: List<PageImageObject>) = withContext(Dispatchers.IO) {
        strokeFileStore.savePageImages(pageId, images)
    }

    suspend fun loadPageImages(pageId: String): List<PageImageObject> = withContext(Dispatchers.IO) {
        strokeFileStore.loadPageImages(pageId)
    }

    suspend fun copyImageFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        strokeFileStore.copyImageFromUri(uri)
    }

    suspend fun saveWhiteboardObjects(documentId: String, objects: List<CanvasObject>) = withContext(Dispatchers.IO) {
        strokeFileStore.saveWhiteboardObjects(documentId, objects)
    }

    suspend fun loadWhiteboardObjects(documentId: String): List<CanvasObject> = withContext(Dispatchers.IO) {
        strokeFileStore.loadWhiteboardObjects(documentId)
    }

    // --- Folder Operations ---
    fun getFolders(parentId: String?): Flow<List<Folder>> =
        folderDao.getFolders(parentId).map { list -> list.map { it.toDomain() } }

    fun getAllFolders(): Flow<List<Folder>> =
        folderDao.getAllFolders().map { list -> list.map { it.toDomain() } }

    suspend fun createFolder(name: String, parentId: String? = null, color: Long = 0xFF2563EB): Folder = withContext(Dispatchers.IO) {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            parentId = parentId,
            name = name.ifBlank { "New Folder" },
            color = color
        )
        folderDao.insertOrUpdate(FolderEntity.fromDomain(folder))
        folder
    }

    suspend fun trashFolder(id: String) = withContext(Dispatchers.IO) {
        folderDao.moveToTrash(id)
    }
}

