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
    @Volatile
    private var isClosed = false

    // Cache up to 10 rendered bitmaps to prevent excessive memory usage while keeping navigation snappy
    private val bitmapCache = object : LruCache<Int, Bitmap>(10) {
        override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC handle bitmap recycling safely
        }
    }

    init {
        openRendererSafely()
    }

    private fun openRendererSafely() = synchronized(this) {
        if (isClosed) return@synchronized
        try {
            if (pdfFile.exists() && pdfFile.length() > 0) {
                fileDescriptor?.close()
                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let {
                    pdfRenderer?.close()
                    pdfRenderer = PdfRenderer(it)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            pdfRenderer = null
            fileDescriptor = null
        }
    }

    val pageCount: Int
        get() = synchronized(this) {
            try {
                if (pdfRenderer == null && !isClosed) {
                    openRendererSafely()
                }
                pdfRenderer?.pageCount ?: 0
            } catch (t: Throwable) {
                0
            }
        }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1080, targetHeight: Int = 1528): Bitmap? =
        withContext(Dispatchers.IO) {
            if (isClosed) return@withContext null
            bitmapCache.get(pageIndex)?.let { return@withContext it }

            renderMutex.withLock {
                if (isClosed) return@withLock null
                // Re-check cache under lock
                bitmapCache.get(pageIndex)?.let { return@withLock it }

                synchronized(this@PdfPageRenderer) {
                    if (pdfRenderer == null && !isClosed) {
                        openRendererSafely()
                    }
                }

                val renderer = synchronized(this@PdfPageRenderer) { pdfRenderer } ?: return@withLock null
                val totalCount = try { renderer.pageCount } catch (_: Throwable) { 0 }
                if (pageIndex < 0 || pageIndex >= totalCount) return@withLock null

                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val pWidth = page.width.coerceAtLeast(1)
                    val pHeight = page.height.coerceAtLeast(1)
                    val aspect = pWidth.toFloat() / pHeight.toFloat()
                    val outW = targetWidth.coerceIn(400, 1920)
                    val outH = (outW / aspect).roundToInt().coerceIn(400, 2800)

                    val bitmap = try {
                        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    } catch (oom: OutOfMemoryError) {
                        bitmapCache.evictAll()
                        System.gc()
                        // Fallback to half resolution to conserve memory
                        Bitmap.createBitmap((outW / 2).coerceAtLeast(300), (outH / 2).coerceAtLeast(400), Bitmap.Config.RGB_565)
                    }

                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    page = null

                    bitmapCache.put(pageIndex, bitmap)
                    bitmap
                } catch (t: Throwable) {
                    t.printStackTrace()
                    try { page?.close() } catch (_: Throwable) {}
                    null
                }
            }
        }

    fun close() {
        isClosed = true
        bitmapCache.evictAll()
        synchronized(this) {
            try {
                pdfRenderer?.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                pdfRenderer = null
            }
            try {
                fileDescriptor?.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                fileDescriptor = null
            }
        }
    }
}


