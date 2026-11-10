package app.inkflow.studio.data.local

import android.content.Context
import app.inkflow.studio.domain.model.CanvasObject
import app.inkflow.studio.domain.model.InkStroke
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class StrokeFileStore(private val context: Context) {
    private val gson = Gson()
    private val strokesDir = File(context.filesDir, "strokes").apply { mkdirs() }
    private val whiteboardDir = File(context.filesDir, "whiteboards").apply { mkdirs() }

    suspend fun savePageStrokes(pageId: String, strokes: List<InkStroke>) = withContext(Dispatchers.IO) {
        val targetFile = File(strokesDir, "$pageId.json")
        val tempFile = File(strokesDir, "$pageId.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    gson.toJson(strokes, writer)
                    writer.flush()
                }
                fos.fd.sync()
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun loadPageStrokes(pageId: String): List<InkStroke> = withContext(Dispatchers.IO) {
        val targetFile = File(strokesDir, "$pageId.json")
        if (!targetFile.exists()) return@withContext emptyList()
        try {
            targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                val type = object : TypeToken<List<InkStroke>>() {}.type
                gson.fromJson<List<InkStroke>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun deletePageStrokes(pageId: String) = withContext(Dispatchers.IO) {
        val targetFile = File(strokesDir, "$pageId.json")
        if (targetFile.exists()) targetFile.delete()
        val tempFile = File(strokesDir, "$pageId.tmp")
        if (tempFile.exists()) tempFile.delete()
    }

    suspend fun saveWhiteboardObjects(documentId: String, objects: List<CanvasObject>) = withContext(Dispatchers.IO) {
        val targetFile = File(whiteboardDir, "$documentId.json")
        val tempFile = File(whiteboardDir, "$documentId.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    gson.toJson(objects, writer)
                    writer.flush()
                }
                fos.fd.sync()
            }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun loadWhiteboardObjects(documentId: String): List<CanvasObject> = withContext(Dispatchers.IO) {
        val targetFile = File(whiteboardDir, "$documentId.json")
        if (!targetFile.exists()) return@withContext emptyList()
        try {
            targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                val type = object : TypeToken<List<CanvasObject>>() {}.type
                gson.fromJson<List<CanvasObject>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

