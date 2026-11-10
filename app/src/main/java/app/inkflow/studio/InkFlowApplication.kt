package app.inkflow.studio

import android.app.Application
import app.inkflow.studio.data.local.AppDatabase
import app.inkflow.studio.data.local.PdfFileStore
import app.inkflow.studio.data.local.StrokeFileStore
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.data.repository.StudyRepository

class InkFlowApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var strokeFileStore: StrokeFileStore
        private set
    lateinit var pdfFileStore: PdfFileStore
        private set
    lateinit var documentRepository: DocumentRepository
        private set
    lateinit var studyRepository: StudyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        strokeFileStore = StrokeFileStore(this)
        pdfFileStore = PdfFileStore(this)
        documentRepository = DocumentRepository(database, strokeFileStore, pdfFileStore)
        studyRepository = StudyRepository(database)
    }
}

