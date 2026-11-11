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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StrokeFileStore(private val context: Context) {
    private val gson = Gson()
    private val strokesDir = File(context.filesDir, "strokes").apply { mkdirs() }
    private val textsDir = File(context.filesDir, "texts").apply { mkdirs() }
    private val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
    private val whiteboardDir = File(context.filesDir, "whiteboards").apply { mkdirs() }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun getLock(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    private suspend fun <T> saveListSafely(dir: File, key: String, data: List<T>) = withContext(Dispatchers.IO) {
        getLock("${dir.name}_$key").withLock {
            val targetFile = File(dir, "$key.json")
            val backupFile = File(dir, "$key.bak")
            val tempFile = File(dir, "${key}_${UUID.randomUUID()}.tmp")

            try {
                FileOutputStream(tempFile).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                        gson.toJson(data, writer)
                        writer.flush()
                    }
                    fos.fd.sync()
                }

                // If target file exists, keep it as backup before replacing
                if (targetFile.exists() && targetFile.length() > 0) {
                    try {
                        if (backupFile.exists()) backupFile.delete()
                        targetFile.renameTo(backupFile)
                    } catch (_: Exception) {
                        // proceed if rename to backup fails
                    }
                }

                // Move temp file to target
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    // Fallback copy if atomic rename fails
                    tempFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    tempFile.delete()
                }

                // Clean backup once target is verified
                if (targetFile.exists() && targetFile.length() > 0 && backupFile.exists()) {
                    backupFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (tempFile.exists()) tempFile.delete()
                // If target was moved to backup but temp failed, restore target from backup
                if (!targetFile.exists() && backupFile.exists()) {
                    backupFile.renameTo(targetFile)
                }
            }
        }
    }

    private suspend fun <T> loadListSafely(dir: File, key: String, type: Type): List<T> = withContext(Dispatchers.IO) {
        getLock("${dir.name}_$key").withLock {
            val targetFile = File(dir, "$key.json")
            val backupFile = File(dir, "$key.bak")

            val fileToRead = when {
                targetFile.exists() && targetFile.length() > 0 -> targetFile
                backupFile.exists() && backupFile.length() > 0 -> backupFile
                else -> null
            } ?: return@withLock emptyList()

            try {
                fileToRead.bufferedReader(Charsets.UTF_8).use { reader ->
                    gson.fromJson<List<T>>(reader, type) ?: emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (fileToRead == targetFile && backupFile.exists() && backupFile.length() > 0) {
                    try {
                        backupFile.bufferedReader(Charsets.UTF_8).use { reader ->
                            gson.fromJson<List<T>>(reader, type) ?: emptyList()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    suspend fun savePageStrokes(pageId: String, strokes: List<InkStroke>) {
        saveListSafely(strokesDir, pageId, strokes)
    }

    suspend fun loadPageStrokes(pageId: String): List<InkStroke> {
        val type = object : TypeToken<List<InkStroke>>() {}.type
        return loadListSafely(strokesDir, pageId, type)
    }

    suspend fun savePageTexts(pageId: String, texts: List<PageTextObject>) {
        saveListSafely(textsDir, pageId, texts)
    }

    suspend fun loadPageTexts(pageId: String): List<PageTextObject> {
        val type = object : TypeToken<List<PageTextObject>>() {}.type
        return loadListSafely(textsDir, pageId, type)
    }

    suspend fun copyImageFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val destFile = File(imagesDir, "img_${UUID.randomUUID()}.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        } ?: throw IllegalStateException("Cannot read image from $uri")
        destFile.absolutePath
    }

    suspend fun savePageImages(pageId: String, images: List<PageImageObject>) {
        saveListSafely(imagesDir, pageId, images)
    }

    suspend fun loadPageImages(pageId: String): List<PageImageObject> {
        val type = object : TypeToken<List<PageImageObject>>() {}.type
        return loadListSafely(imagesDir, pageId, type)
    }

    suspend fun deletePageStrokes(pageId: String) = withContext(Dispatchers.IO) {
        listOf(strokesDir, textsDir, imagesDir).forEach { dir ->
            getLock("${dir.name}_$pageId").withLock {
                File(dir, "$pageId.json").delete()
                File(dir, "$pageId.bak").delete()
                dir.listFiles { _, name -> name.startsWith(pageId) && name.endsWith(".tmp") }?.forEach { it.delete() }
            }
        }
    }

    suspend fun saveWhiteboardObjects(documentId: String, objects: List<CanvasObject>) {
        saveListSafely(whiteboardDir, documentId, objects)
    }

    suspend fun loadWhiteboardObjects(documentId: String): List<CanvasObject> {
        val type = object : TypeToken<List<CanvasObject>>() {}.type
        return loadListSafely(whiteboardDir, documentId, type)
    }
}
