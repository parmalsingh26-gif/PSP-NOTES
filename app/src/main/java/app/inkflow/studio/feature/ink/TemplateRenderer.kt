package app.inkflow.studio.feature.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.inkflow.studio.domain.model.PageTemplate

object TemplateRenderer {
    fun drawTemplate(
        drawScope: DrawScope,
        template: PageTemplate,
        pageWidth: Float,
        pageHeight: Float,
        isDarkTheme: Boolean = false
    ) {
        val ruleLineColor = if (isDarkTheme) Color(0xFF334155) else Color(0xFFCBD5E1)
        val marginLineColor = if (isDarkTheme) Color(0xFF7F1D1D) else Color(0xFFFCA5A5)
        val dotColor = if (isDarkTheme) Color(0xFF475569) else Color(0xFF94A3B8)

        when (template) {
            PageTemplate.BLANK -> {
                // No lines, pure clean canvas
            }
            PageTemplate.RULED -> {
                val lineSpacing = 28f
                val marginX = 64f
                // Margin line
                drawScope.drawLine(
                    color = marginLineColor,
                    start = Offset(marginX, 0f),
                    end = Offset(marginX, pageHeight),
                    strokeWidth = 1.5f
                )
                // Horizontal ruled lines
                var y = 60f
                while (y < pageHeight) {
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(0f, y),
                        end = Offset(pageWidth, y),
                        strokeWidth = 1f
                    )
                    y += lineSpacing
                }
            }
            PageTemplate.COLLEGE_RULED -> {
                val lineSpacing = 20f
                val marginX = 54f
                drawScope.drawLine(
                    color = marginLineColor,
                    start = Offset(marginX, 0f),
                    end = Offset(marginX, pageHeight),
                    strokeWidth = 1.5f
                )
                var y = 50f
                while (y < pageHeight) {
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(0f, y),
                        end = Offset(pageWidth, y),
                        strokeWidth = 1f
                    )
                    y += lineSpacing
                }
            }
            PageTemplate.DOTTED -> {
                val dotSpacing = 20f
                var x = 20f
                while (x < pageWidth) {
                    var y = 20f
                    while (y < pageHeight) {
                        drawScope.drawCircle(
                            color = dotColor,
                            radius = 1.2f,
                            center = Offset(x, y)
                        )
                        y += dotSpacing
                    }
                    x += dotSpacing
                }
            }
            PageTemplate.GRID -> {
                val gridSpacing = 20f
                // Vertical lines
                var x = 0f
                while (x <= pageWidth) {
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, pageHeight),
                        strokeWidth = 0.8f
                    )
                    x += gridSpacing
                }
                // Horizontal lines
                var y = 0f
                while (y <= pageHeight) {
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(0f, y),
                        end = Offset(pageWidth, y),
                        strokeWidth = 0.8f
                    )
                    y += gridSpacing
                }
            }
            PageTemplate.CORNELL -> {
                val cueColumnWidth = 140f
                val summaryRowHeight = 120f
                val ruleSpacing = 24f

                // Cue column divider (vertical)
                drawScope.drawLine(
                    color = marginLineColor,
                    start = Offset(cueColumnWidth, 40f),
                    end = Offset(cueColumnWidth, pageHeight - summaryRowHeight),
                    strokeWidth = 2f
                )
                // Summary divider (horizontal)
                drawScope.drawLine(
                    color = marginLineColor,
                    start = Offset(0f, pageHeight - summaryRowHeight),
                    end = Offset(pageWidth, pageHeight - summaryRowHeight),
                    strokeWidth = 2f
                )
                // Ruled lines in the notes column
                var y = 40f
                while (y < pageHeight - summaryRowHeight) {
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(cueColumnWidth, y),
                        end = Offset(pageWidth, y),
                        strokeWidth = 1f
                    )
                    y += ruleSpacing
                }
            }
            PageTemplate.CHECKLIST -> {
                val lineSpacing = 32f
                val boxSize = 14f
                val boxX = 24f
                var y = 48f
                while (y < pageHeight - 30f) {
                    // Checkbox
                    drawScope.drawRect(
                        color = ruleLineColor,
                        topLeft = Offset(boxX, y - boxSize + 2f),
                        size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                    )
                    // Line
                    drawScope.drawLine(
                        color = ruleLineColor,
                        start = Offset(boxX + boxSize + 16f, y + 2f),
                        end = Offset(pageWidth - 20f, y + 2f),
                        strokeWidth = 1f
                    )
                    y += lineSpacing
                }
            }
        }
    }
}

