package app.inkflow.studio.data.local

import android.content.Context
import android.net.Uri
import app.inkflow.studio.domain.model.CanvasObject
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.PageImageObject
import app.inkflow.studio.domain.model.PageTextObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

class StrokeFileStore(private val context: Context) {
    private val gson = Gson()
    private val strokesDir = File(context.filesDir, "strokes").apply { mkdirs() }
    private val textsDir = File(context.filesDir, "texts").apply { mkdirs() }
    private val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
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

    suspend fun savePageTexts(pageId: String, texts: List<PageTextObject>) = withContext(Dispatchers.IO) {
        val targetFile = File(textsDir, "$pageId.json")
        val tempFile = File(textsDir, "$pageId.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    gson.toJson(texts, writer)
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

    suspend fun loadPageTexts(pageId: String): List<PageTextObject> = withContext(Dispatchers.IO) {
        val targetFile = File(textsDir, "$pageId.json")
        if (!targetFile.exists()) return@withContext emptyList()
        try {
            targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                val type = object : TypeToken<List<PageTextObject>>() {}.type
                gson.fromJson<List<PageTextObject>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun copyImageFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val destFile = File(imagesDir, "img_${UUID.randomUUID()}.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot read image from $uri")
        destFile.absolutePath
    }

    suspend fun savePageImages(pageId: String, images: List<PageImageObject>) = withContext(Dispatchers.IO) {
        val targetFile = File(imagesDir, "$pageId.json")
        val tempFile = File(imagesDir, "$pageId.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    gson.toJson(images, writer)
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

    suspend fun loadPageImages(pageId: String): List<PageImageObject> = withContext(Dispatchers.IO) {
        val targetFile = File(imagesDir, "$pageId.json")
        if (!targetFile.exists()) return@withContext emptyList()
        try {
            targetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                val type = object : TypeToken<List<PageImageObject>>() {}.type
                gson.fromJson<List<PageImageObject>>(reader, type) ?: emptyList()
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

        val textTarget = File(textsDir, "$pageId.json")
        if (textTarget.exists()) textTarget.delete()
        val textTemp = File(textsDir, "$pageId.tmp")
        if (textTemp.exists()) textTemp.delete()

        val imgTarget = File(imagesDir, "$pageId.json")
        if (imgTarget.exists()) imgTarget.delete()
        val imgTemp = File(imagesDir, "$pageId.tmp")
        if (imgTemp.exists()) imgTemp.delete()
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
