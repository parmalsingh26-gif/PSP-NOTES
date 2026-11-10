package app.inkflow.studio.feature.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.Page
import app.inkflow.studio.domain.model.PageImageObject
import app.inkflow.studio.domain.model.PageTextObject
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
        imageProvider: (suspend (String) -> List<PageImageObject>)? = null,
        textProvider: (suspend (String) -> List<PageTextObject>)? = null,
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

                // 2. Draw Inserted Images (underneath handwriting)
                if (imageProvider != null) {
                    val images = imageProvider(page.id)
                    for (img in images) {
                        val file = File(img.filePath)
                        if (file.exists()) {
                            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) {
                                canvas.save()
                                val cx = img.x + img.width / 2f
                                val cy = img.y + img.height / 2f
                                canvas.translate(cx, cy)
                                if (img.rotationDegrees != 0f) {
                                    canvas.rotate(img.rotationDegrees)
                                }
                                val scaleX = if (img.isFlippedHorizontal) -1f else 1f
                                val scaleY = if (img.isFlippedVertical) -1f else 1f
                                if (scaleX != 1f || scaleY != 1f) {
                                    canvas.scale(scaleX, scaleY)
                                }
                                canvas.translate(-img.width / 2f, -img.height / 2f)

                                val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                                    alpha = (img.opacity * 255).toInt().coerceIn(0, 255)
                                }
                                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                                val dstRect = RectF(0f, 0f, img.width, img.height)
                                canvas.drawBitmap(bmp, srcRect, dstRect, imgPaint)
                                canvas.restore()
                            }
                        }
                    }
                }

                // 3. Draw Text Objects
                if (textProvider != null) {
                    val texts = textProvider(page.id)
                    for (txt in texts) {
                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = txt.color.toInt()
                            textSize = txt.fontSizeSp
                        }
                        canvas.drawText(txt.text, txt.x, txt.y + txt.fontSizeSp, textPaint)
                    }
                }

                // 4. Draw Vector Strokes & Shapes
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
                        alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
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

