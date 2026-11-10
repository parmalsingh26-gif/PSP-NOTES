package app.inkflow.studio.feature.pdf

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.Page
import app.inkflow.studio.domain.model.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfExporter {
    suspend fun exportDocumentToPdf(
        document: Document,
        pages: List<Page>,
        strokeProvider: suspend (String) -> List<InkStroke>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()

        var pdfRenderer: PdfPageRenderer? = null
        if (document.type == DocumentType.PDF && document.pdfFilePath != null) {
            val sourceFile = File(document.pdfFilePath)
            if (sourceFile.exists()) {
                pdfRenderer = PdfPageRenderer(sourceFile)
            }
        }

        try {
            for (page in pages) {
                val pageWidth = page.width.toInt().coerceAtLeast(595)
                val pageHeight = page.height.toInt().coerceAtLeast(842)

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, page.index + 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // 1. Draw Background
                if (pdfRenderer != null && page.index < pdfRenderer.pageCount) {
                    val renderedBitmap = pdfRenderer.renderPage(page.index, pageWidth, pageHeight)
                    if (renderedBitmap != null) {
                        canvas.drawBitmap(renderedBitmap, 0f, 0f, null)
                    }
                } else {
                    // White notebook paper background
                    val bgPaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)
                }

                // 2. Draw Vector Strokes
                val strokes = strokeProvider(page.id)
                for (stroke in strokes) {
                    if (stroke.points.size < 2) continue

                    val paint = Paint().apply {
                        color = stroke.color.toInt()
                        strokeWidth = stroke.width
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true

                        if (stroke.tool == ToolType.HIGHLIGHTER) {
                            alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
                        } else {
                            alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
                        }
                    }

                    val path = Path()
                    val first = stroke.points[0]
                    path.moveTo(first.x, first.y)

                    for (i in 1 until stroke.points.size) {
                        val p = stroke.points[i]
                        path.lineTo(p.x, p.y)
                    }

                    canvas.drawPath(path, paint)
                }

                pdfDoc.finishPage(pdfPage)
            }

            // Write output file
            FileOutputStream(outputFile).use { fos ->
                pdfDoc.writeTo(fos)
                fos.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            pdfDoc.close()
            pdfRenderer?.close()
        }
    }
}

