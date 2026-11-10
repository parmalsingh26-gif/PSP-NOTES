package app.inkflow.studio.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfPageRenderer(private val pdfFile: File) {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    // Cache up to 10 rendered bitmaps to prevent excessive memory usage while keeping navigation snappy
    private val bitmapCache = object : LruCache<Int, Bitmap>(10) {
        override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC handle bitmap recycling safely
        }
    }

    init {
        try {
            if (pdfFile.exists() && pdfFile.length() > 0) {
                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let {
                    pdfRenderer = PdfRenderer(it)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val pageCount: Int
        get() = pdfRenderer?.pageCount ?: 0

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1190, targetHeight: Int = 1684): Bitmap? =
        withContext(Dispatchers.IO) {
            val renderer = pdfRenderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            bitmapCache.get(pageIndex)?.let { return@withContext it }

            try {
                val page = renderer.openPage(pageIndex)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                bitmapCache.put(pageIndex, bitmap)
                bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    fun close() {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
            bitmapCache.evictAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

