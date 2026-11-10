package app.inkflow.studio.data.repository

import android.net.Uri
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
        pageCount: Int,
        folderId: String? = null
    ): Document = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()
        val savedPath = pdfFileStore.copyPdfFromUri(docId, uri)
        val now = System.currentTimeMillis()
        val doc = Document(
            id = docId,
            title = title.ifBlank { "Imported PDF" },
            type = DocumentType.PDF,
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            pageCount = pageCount.coerceAtLeast(1),
            pdfFilePath = savedPath
        )
        documentDao.insertOrUpdate(DocumentEntity.fromDomain(doc))

        // Create page entries for all PDF pages
        val pages = (0 until doc.pageCount).map { idx ->
            PageEntity(
                id = UUID.randomUUID().toString(),
                documentId = docId,
                pageIndex = idx,
                width = 595f,
                height = 842f,
                template = PageTemplate.BLANK.name,
                isBookmarked = false
            )
        }
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
        pageDao.deleteAllPagesForDocument(id)
        pdfFileStore.deletePdf(id)
        documentDao.permanentDelete(id)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        // Collect all trashed docs
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

    suspend fun setPageBookmark(pageId: String, isBookmarked: Boolean) = withContext(Dispatchers.IO) {
        pageDao.setBookmark(pageId, isBookmarked)
    }

    suspend fun updatePageTemplate(pageId: String, template: PageTemplate) = withContext(Dispatchers.IO) {
        pageDao.updateTemplate(pageId, template.name)
    }

    // --- Vector Strokes and Objects ---
    suspend fun savePageStrokes(pageId: String, strokes: List<InkStroke>) = withContext(Dispatchers.IO) {
        strokeFileStore.savePageStrokes(pageId, strokes)
    }

    suspend fun loadPageStrokes(pageId: String): List<InkStroke> = withContext(Dispatchers.IO) {
        strokeFileStore.loadPageStrokes(pageId)
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

    suspend fun createFolder(name: String, parentId: String? = null): Folder = withContext(Dispatchers.IO) {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            parentId = parentId,
            name = name.ifBlank { "New Folder" }
        )
        folderDao.insertOrUpdate(FolderEntity.fromDomain(folder))
        folder
    }

    suspend fun trashFolder(id: String) = withContext(Dispatchers.IO) {
        folderDao.moveToTrash(id)
    }
}

