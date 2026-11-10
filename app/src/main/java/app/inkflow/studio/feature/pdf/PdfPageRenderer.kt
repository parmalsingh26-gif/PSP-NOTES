package app.inkflow.studio.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class PdfPageRenderer(private val pdfFile: File) {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val renderMutex = Mutex()

    // Cache up to 10 rendered bitmaps to prevent excessive memory usage while keeping navigation snappy
    private val bitmapCache = object : LruCache<Int, Bitmap>(10) {
        override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC handle bitmap recycling safely
        }
    }

    init {
        openRendererSafely()
    }

    private fun openRendererSafely() {
        try {
            if (pdfFile.exists() && pdfFile.length() > 0) {
                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let {
                    pdfRenderer = PdfRenderer(it)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            pdfRenderer = null
            fileDescriptor = null
        }
    }

    val pageCount: Int
        get() = pdfRenderer?.pageCount ?: 0

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1190, targetHeight: Int = 1684): Bitmap? =
        withContext(Dispatchers.IO) {
            bitmapCache.get(pageIndex)?.let { return@withContext it }

            renderMutex.withLock {
                // Re-check cache under lock
                bitmapCache.get(pageIndex)?.let { return@withLock it }

                if (pdfRenderer == null) {
                    openRendererSafely()
                }

                val renderer = pdfRenderer ?: return@withLock null
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val pWidth = page.width.coerceAtLeast(1)
                    val pHeight = page.height.coerceAtLeast(1)
                    val aspect = pWidth.toFloat() / pHeight.toFloat()
                    val outW = targetWidth.coerceIn(400, 2400)
                    val outH = (outW / aspect).roundToInt().coerceIn(400, 3400)

                    val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    page = null

                    bitmapCache.put(pageIndex, bitmap)
                    bitmap
                } catch (e: Exception) {
                    e.printStackTrace()
                    page?.close()
                    null
                }
            }
        }

    fun close() {
        try {
            bitmapCache.evictAll()
            pdfRenderer?.close()
            pdfRenderer = null
            fileDescriptor?.close()
            fileDescriptor = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}


