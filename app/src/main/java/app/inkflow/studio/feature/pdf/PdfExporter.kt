package app.inkflow.studio.feature.pdf

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.Page
import app.inkflow.studio.domain.model.PageImageObject
import app.inkflow.studio.domain.model.PageTemplate
import app.inkflow.studio.domain.model.PageTextObject
import app.inkflow.studio.domain.model.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

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
                val pageWf = pageWidth.toFloat()
                val pageHf = pageHeight.toFloat()

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, page.index + 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // 1. Draw Background (PDF page or Notebook Paper Template)
                val targetPdfIndex = page.pdfPageIndex
                if (pdfRenderer != null && targetPdfIndex != null && targetPdfIndex < pdfRenderer.pageCount) {
                    val renderedBitmap = pdfRenderer.renderPage(targetPdfIndex, pageWidth, pageHeight)
                    if (renderedBitmap != null) {
                        canvas.drawBitmap(renderedBitmap, 0f, 0f, null)
                    }
                } else {
                    val bgPaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(0f, 0f, pageWf, pageHf, bgPaint)
                    drawPaperTemplate(canvas, page.template, pageWf, pageHf)
                }

                // 2. Draw Inserted Images (sorted by zIndex, with non-destructive crop & transforms)
                if (imageProvider != null) {
                    val images = imageProvider(page.id).sortedBy { it.zIndex }
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
                                val cLeft = (img.cropLeft.coerceIn(0f, 0.95f) * bmp.width).roundToInt()
                                val cTop = (img.cropTop.coerceIn(0f, 0.95f) * bmp.height).roundToInt()
                                val cRight = (img.cropRight.coerceIn(cLeft / bmp.width.toFloat() + 0.05f, 1f) * bmp.width).roundToInt()
                                val cBottom = (img.cropBottom.coerceIn(cTop / bmp.height.toFloat() + 0.05f, 1f) * bmp.height).roundToInt()

                                val srcRect = Rect(cLeft, cTop, cRight, cBottom)
                                val dstRect = RectF(0f, 0f, img.width, img.height)
                                canvas.drawBitmap(bmp, srcRect, dstRect, imgPaint)
                                canvas.restore()
                            }
                        }
                    }
                }

                // 3. Draw Text Objects (with background card & multi-line support)
                if (textProvider != null) {
                    val texts = textProvider(page.id).sortedBy { it.zIndex }
                    for (txt in texts) {
                        val lines = txt.text.split("\n")
                        val lineHeight = txt.fontSizeSp * 1.35f
                        val maxLineLen = (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(4)
                        val boxW = (maxLineLen * txt.fontSizeSp * 0.6f + 18f).coerceAtLeast(60f)
                        val boxH = lines.size * lineHeight + 12f

                        txt.backgroundColor?.let { bgCol ->
                            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = bgCol.toInt()
                                style = Paint.Style.FILL
                            }
                            canvas.drawRoundRect(
                                RectF(txt.x - 6f, txt.y - 4f, txt.x - 6f + boxW, txt.y - 4f + boxH),
                                6f,
                                6f,
                                bgPaint
                            )
                        }

                        val style = when {
                            txt.isBold && txt.isItalic -> Typeface.BOLD_ITALIC
                            txt.isBold -> Typeface.BOLD
                            txt.isItalic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        }
                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = txt.color.toInt()
                            textSize = txt.fontSizeSp
                            typeface = Typeface.create(Typeface.DEFAULT, style)
                        }
                        for ((idx, line) in lines.withIndex()) {
                            canvas.drawText(line, txt.x, txt.y + txt.fontSizeSp + idx * lineHeight, textPaint)
                        }
                    }
                }

                // 4. Draw Vector Strokes & Shapes (Quadratic Bezier smoothed)
                val strokes = strokeProvider(page.id)
                for (stroke in strokes) {
                    if (stroke.points.size < 2) continue

                    val isHighlight = stroke.tool == ToolType.HIGHLIGHTER
                    val paint = Paint().apply {
                        color = stroke.color.toInt()
                        strokeWidth = if (isHighlight) stroke.width * 2.8f else stroke.width
                        style = Paint.Style.STROKE
                        strokeCap = if (isHighlight) Paint.Cap.SQUARE else Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                        alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
                    }

                    val path = Path()
                    val pts = stroke.points
                    path.moveTo(pts[0].x, pts[0].y)

                    if (stroke.tool == ToolType.SHAPE || pts.size == 2) {
                        for (i in 1 until pts.size) {
                            path.lineTo(pts[i].x, pts[i].y)
                        }
                    } else {
                        for (i in 1 until pts.size - 1) {
                            val midX = (pts[i].x + pts[i + 1].x) / 2f
                            val midY = (pts[i].y + pts[i + 1].y) / 2f
                            path.quadTo(pts[i].x, pts[i].y, midX, midY)
                        }
                        val last = pts.last()
                        path.lineTo(last.x, last.y)
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

    private fun drawPaperTemplate(canvas: Canvas, template: PageTemplate, pageW: Float, pageH: Float) {
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFCA5A5.toInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            style = Paint.Style.FILL
        }

        when (template) {
            PageTemplate.BLANK -> {}
            PageTemplate.RULED -> {
                canvas.drawLine(64f, 0f, 64f, pageH, marginPaint)
                var y = 60f
                while (y < pageH) {
                    canvas.drawLine(0f, y, pageW, y, rulePaint)
                    y += 28f
                }
            }
            PageTemplate.COLLEGE_RULED -> {
                canvas.drawLine(54f, 0f, 54f, pageH, marginPaint)
                var y = 50f
                while (y < pageH) {
                    canvas.drawLine(0f, y, pageW, y, rulePaint)
                    y += 20f
                }
            }
            PageTemplate.DOTTED -> {
                var x = 20f
                while (x < pageW) {
                    var y = 20f
                    while (y < pageH) {
                        canvas.drawCircle(x, y, 1.2f, dotPaint)
                        y += 20f
                    }
                    x += 20f
                }
            }
            PageTemplate.GRID -> {
                var x = 0f
                while (x <= pageW) {
                    canvas.drawLine(x, 0f, x, pageH, rulePaint)
                    x += 20f
                }
                var y = 0f
                while (y <= pageH) {
                    canvas.drawLine(0f, y, pageW, y, rulePaint)
                    y += 20f
                }
            }
            PageTemplate.CORNELL -> {
                val cueW = 140f
                val sumH = 120f
                canvas.drawLine(cueW, 40f, cueW, pageH - sumH, marginPaint)
                canvas.drawLine(0f, pageH - sumH, pageW, pageH - sumH, marginPaint)
                var y = 40f
                while (y < pageH - sumH) {
                    canvas.drawLine(cueW, y, pageW, y, rulePaint)
                    y += 24f
                }
            }
            PageTemplate.CHECKLIST -> {
                val boxSize = 14f
                val boxX = 24f
                var y = 48f
                while (y < pageH - 30f) {
                    canvas.drawRect(boxX, y - boxSize + 2f, boxX + boxSize, y + 2f, rulePaint)
                    canvas.drawLine(boxX + boxSize + 16f, y + 2f, pageW - 20f, y + 2f, rulePaint)
                    y += 32f
                }
            }
        }
    }
}
