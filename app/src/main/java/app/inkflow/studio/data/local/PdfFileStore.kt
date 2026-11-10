package app.inkflow.studio.data.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfFileStore(private val context: Context) {
    private val pdfsDir = File(context.filesDir, "pdfs").apply { mkdirs() }

    suspend fun copyPdfFromUri(documentId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val destFile = File(pdfsDir, "$documentId.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream from URI: $uri")
        destFile.absolutePath
    }

    suspend fun deletePdf(documentId: String) = withContext(Dispatchers.IO) {
        val file = File(pdfsDir, "$documentId.pdf")
        if (file.exists()) file.delete()
    }

    fun getPdfFile(documentId: String): File {
        return File(pdfsDir, "$documentId.pdf")
    }
}

