package app.inkflow.studio.feature.whiteboard

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.core.design.InkCanvasDark
import app.inkflow.studio.core.design.InkCanvasLight
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.domain.model.CanvasObject
import app.inkflow.studio.domain.model.CanvasObjectType
import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import app.inkflow.studio.feature.ink.InkEngine
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.*

private enum class WhiteboardTool {
    SELECT_PAN,
    PEN,
    HIGHLIGHTER,
    ERASER,
    CONNECTOR_ARROW
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WhiteboardScreen(
    documentId: String,
    repository: DocumentRepository,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val document by repository.getDocument(documentId).collectAsState(initial = null)
    val wbStrokeKey = remember(documentId) { "wb_$documentId" }

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var isPinching by remember { mutableStateOf(false) }
    var lastPinchDist by remember { mutableFloatStateOf(0f) }
    var lastPinchMid by remember { mutableStateOf(Offset.Zero) }

    val inkEngine = remember { InkEngine() }
    var activeWbTool by remember { mutableStateOf(WhiteboardTool.SELECT_PAN) }
    var penColor by remember { mutableLongStateOf(0xFF0F172AL) }

    var objects by remember { mutableStateOf<List<CanvasObject>>(emptyList()) }
    var boardStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var activeStrokeState by remember { mutableStateOf<InkStroke?>(null) }

    // Object selection, drag & resize state
    var selectedObjectId by remember { mutableStateOf<String?>(null) }
    val selectedObject = objects.find { it.id == selectedObjectId }
    var isDraggingObject by remember { mutableStateOf(false) }
    var isResizingObject by remember { mutableStateOf(false) }
    var dragStartWorld by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var initialObjectSnapshot by remember { mutableStateOf<CanvasObject?>(null) }
    var panDragStartScreen by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Connector Arrow drag state
    var arrowStartWorld by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var arrowCurrentWorld by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Sticky / Object Text Dialog
    var showObjectTextDialog by remember { mutableStateOf(false) }
    var editingObject by remember { mutableStateOf<CanvasObject?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var newObjectTypeToCreate by remember { mutableStateOf(CanvasObjectType.STICKY_NOTE) }

    // Load whiteboard objects and freehand strokes
    LaunchedEffect(documentId) {
        objects = repository.loadWhiteboardObjects(documentId)
        boardStrokes = repository.loadPageStrokes(wbStrokeKey)
    }

    val stickyColors = listOf(
        0xFFFEF08AL to 0xFFCA8A04L, // Pastel Yellow
        0xFFBBF7D0L to 0xFF16A34AL, // Mint Green
        0xFFBAE6FDL to 0xFF0284C7L, // Sky Blue
        0xFFFBCFE8L to 0xFFDB2777L, // Rose Pink
        0xFFDDD6FEL to 0xFF7C3AEDL  // Lavender
    )

    val inkPalette = listOf(
        0xFF0F172AL,
        0xFF2563EBL,
        0xFF0D9488L,
        0xFFDC2626L,
        0xFF7C3AEDL,
        0xFFF59E0BL
    )

    fun screenToWorld(sx: Float, sy: Float): Pair<Float, Float> {
        val wx = (sx - panOffset.x) / zoomScale
        val wy = (sy - panOffset.y) / zoomScale
        return Pair(wx, wy)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = document?.title ?: "Whiteboard",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Infinite 2D Canvas · ${objects.size} cards · ${boardStrokes.size} strokes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                enabled = inkEngine.canUndo,
                                onClick = {
                                    boardStrokes = inkEngine.undo(boardStrokes)
                                    coroutineScope.launch { repository.savePageStrokes(wbStrokeKey, boardStrokes) }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo Ink")
                            }
                            IconButton(
                                enabled = inkEngine.canRedo,
                                onClick = {
                                    boardStrokes = inkEngine.redo(boardStrokes)
                                    coroutineScope.launch { repository.savePageStrokes(wbStrokeKey, boardStrokes) }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo Ink")
                            }

                            FilledTonalButton(
                                onClick = {
                                    panOffset = Offset.Zero
                                    zoomScale = 1.0f
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset (${(zoomScale * 100).toInt()}%)", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(if (isSystemInDarkTheme()) InkCanvasDark else InkCanvasLight)
                    .pointerInteropFilter { motionEvent ->
                        // 1. Multi-touch pinch-to-zoom and two-finger pan
                        if (motionEvent.pointerCount >= 2) {
                            val x0 = motionEvent.getX(0)
                            val y0 = motionEvent.getY(0)
                            val x1 = motionEvent.getX(1)
                            val y1 = motionEvent.getY(1)
                            val dist = hypot(x1 - x0, y1 - y0)
                            val mid = Offset((x0 + x1) / 2f, (y0 + y1) / 2f)

                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    isPinching = true
                                    lastPinchDist = dist
                                    lastPinchMid = mid
                                    inkEngine.cancelStroke()
                                    activeStrokeState = null
                                    arrowStartWorld = null
                                    arrowCurrentWorld = null
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (isPinching && lastPinchDist > 10f) {
                                        val factor = dist / lastPinchDist
                                        zoomScale = (zoomScale * factor).coerceIn(0.25f, 4.0f)
                                        panOffset += (mid - lastPinchMid)
                                        lastPinchDist = dist
                                        lastPinchMid = mid
                                    }
                                }
                                MotionEvent.ACTION_POINTER_UP -> {
                                    if (motionEvent.pointerCount <= 2) {
                                        isPinching = false
                                    }
                                }
                            }
                            return@pointerInteropFilter true
                        }

                        if (isPinching) {
                            if (motionEvent.actionMasked == MotionEvent.ACTION_UP || motionEvent.actionMasked == MotionEvent.ACTION_CANCEL) {
                                isPinching = false
                            }
                            return@pointerInteropFilter true
                        }

                        val sx = motionEvent.x
                        val sy = motionEvent.y
                        val (wx, wy) = screenToWorld(sx, sy)

                        when (motionEvent.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                when (activeWbTool) {
                                    WhiteboardTool.SELECT_PAN -> {
                                        // Check resize handle on currently selected object first
                                        if (selectedObject != null && selectedObject.type != CanvasObjectType.SHAPE_ARROW) {
                                            val handleX = selectedObject.x + selectedObject.width
                                            val handleY = selectedObject.y + selectedObject.height
                                            if (hypot(wx - handleX, wy - handleY) < 28f / zoomScale) {
                                                isResizingObject = true
                                                dragStartWorld = Pair(wx, wy)
                                                initialObjectSnapshot = selectedObject
                                                return@pointerInteropFilter true
                                            }
                                        }

                                        // Check if user tapped an existing object
                                        val hit = objects.lastOrNull { obj ->
                                            if (obj.type == CanvasObjectType.SHAPE_ARROW) {
                                                val minX = min(obj.x, obj.endX) - 16f
                                                val maxX = max(obj.x, obj.endX) + 16f
                                                val minY = min(obj.y, obj.endY) - 16f
                                                val maxY = max(obj.y, obj.endY) + 16f
                                                wx in minX..maxX && wy in minY..maxY
                                            } else {
                                                wx in obj.x..(obj.x + obj.width) && wy in obj.y..(obj.y + obj.height)
                                            }
                                        }
                                        if (hit != null) {
                                            selectedObjectId = hit.id
                                            isDraggingObject = true
                                            dragStartWorld = Pair(wx, wy)
                                            initialObjectSnapshot = hit
                                        } else {
                                            selectedObjectId = null
                                            panDragStartScreen = Pair(sx, sy)
                                        }
                                    }
                                    WhiteboardTool.PEN -> {
                                        selectedObjectId = null
                                        inkEngine.currentTool = ToolType.PEN
                                        inkEngine.currentColor = penColor
                                        inkEngine.currentWidth = 3f
                                        inkEngine.startStroke(wx, wy, motionEvent.pressure)
                                        activeStrokeState = inkEngine.activeStroke
                                    }
                                    WhiteboardTool.HIGHLIGHTER -> {
                                        selectedObjectId = null
                                        inkEngine.currentTool = ToolType.HIGHLIGHTER
                                        inkEngine.currentColor = penColor
                                        inkEngine.currentWidth = 9f
                                        inkEngine.startStroke(wx, wy, motionEvent.pressure)
                                        activeStrokeState = inkEngine.activeStroke
                                    }
                                    WhiteboardTool.ERASER -> {
                                        val (remaining, _) = inkEngine.eraseAt(wx, wy, 22f / zoomScale, boardStrokes)
                                        boardStrokes = remaining
                                        coroutineScope.launch { repository.savePageStrokes(wbStrokeKey, remaining) }
                                    }
                                    WhiteboardTool.CONNECTOR_ARROW -> {
                                        selectedObjectId = null
                                        arrowStartWorld = Pair(wx, wy)
                                        arrowCurrentWorld = Pair(wx, wy)
                                    }
                                }
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (isResizingObject && selectedObject != null && initialObjectSnapshot != null && dragStartWorld != null) {
                                    val snap = initialObjectSnapshot!!
                                    val dx = wx - dragStartWorld!!.first
                                    val dy = wy - dragStartWorld!!.second
                                    val newW = (snap.width + dx).coerceAtLeast(80f)
                                    val newH = (snap.height + dy).coerceAtLeast(60f)
                                    objects = objects.map { if (it.id == snap.id) snap.copy(width = newW, height = newH) else it }
                                    return@pointerInteropFilter true
                                }

                                if (isDraggingObject && selectedObject != null && initialObjectSnapshot != null && dragStartWorld != null) {
                                    val snap = initialObjectSnapshot!!
                                    val dx = wx - dragStartWorld!!.first
                                    val dy = wy - dragStartWorld!!.second
                                    val moved = if (snap.type == CanvasObjectType.SHAPE_ARROW) {
                                        snap.copy(x = snap.x + dx, y = snap.y + dy, endX = snap.endX + dx, endY = snap.endY + dy)
                                    } else {
                                        snap.copy(x = snap.x + dx, y = snap.y + dy)
                                    }
                                    objects = objects.map { if (it.id == snap.id) moved else it }
                                    return@pointerInteropFilter true
                                }

                                when (activeWbTool) {
                                    WhiteboardTool.SELECT_PAN -> {
                                        panDragStartScreen?.let { start ->
                                            panOffset += Offset(sx - start.first, sy - start.second)
                                            panDragStartScreen = Pair(sx, sy)
                                        }
                                    }
                                    WhiteboardTool.PEN, WhiteboardTool.HIGHLIGHTER -> {
                                        inkEngine.continueStroke(wx, wy, motionEvent.pressure)
                                        activeStrokeState = inkEngine.activeStroke
                                    }
                                    WhiteboardTool.ERASER -> {
                                        val (remaining, _) = inkEngine.eraseAt(wx, wy, 22f / zoomScale, boardStrokes)
                                        boardStrokes = remaining
                                    }
                                    WhiteboardTool.CONNECTOR_ARROW -> {
                                        arrowCurrentWorld = Pair(wx, wy)
                                    }
                                }
                                true
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (isResizingObject || isDraggingObject) {
                                    isResizingObject = false
                                    isDraggingObject = false
                                    dragStartWorld = null
                                    initialObjectSnapshot = null
                                    coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                                    return@pointerInteropFilter true
                                }

                                panDragStartScreen = null

                                when (activeWbTool) {
                                    WhiteboardTool.PEN, WhiteboardTool.HIGHLIGHTER -> {
                                        val finished = inkEngine.finishStroke()
                                        activeStrokeState = null
                                        if (finished != null) {
                                            boardStrokes = boardStrokes + finished
                                            coroutineScope.launch { repository.savePageStrokes(wbStrokeKey, boardStrokes) }
                                        }
                                    }
                                    WhiteboardTool.ERASER -> {
                                        coroutineScope.launch { repository.savePageStrokes(wbStrokeKey, boardStrokes) }
                                    }
                                    WhiteboardTool.CONNECTOR_ARROW -> {
                                        arrowStartWorld?.let { start ->
                                            if (hypot(wx - start.first, wy - start.second) > 15f) {
                                                val newArrow = CanvasObject(
                                                    id = UUID.randomUUID().toString(),
                                                    type = CanvasObjectType.SHAPE_ARROW,
                                                    x = start.first,
                                                    y = start.second,
                                                    endX = wx,
                                                    endY = wy,
                                                    width = abs(wx - start.first),
                                                    height = abs(wy - start.second),
                                                    color = penColor,
                                                    strokeColor = penColor
                                                )
                                                objects = objects + newArrow
                                                selectedObjectId = newArrow.id
                                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                                            }
                                        }
                                        arrowStartWorld = null
                                        arrowCurrentWorld = null
                                    }
                                    WhiteboardTool.SELECT_PAN -> {}
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = panOffset.x
                            translationY = panOffset.y
                            scaleX = zoomScale
                            scaleY = zoomScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                ) {
                    // 1. Infinite Dot Grid Pattern
                    val gridSize = 40f
                    val dotColor = Color(0xFF94A3B8).copy(alpha = 0.45f)
                    var gx = -2400f
                    while (gx <= 4800f) {
                        var gy = -2400f
                        while (gy <= 4800f) {
                            drawCircle(color = dotColor, radius = 1.6f, center = Offset(gx, gy))
                            gy += gridSize
                        }
                        gx += gridSize
                    }

                    // 2. Render Whiteboard Objects (Sticky Notes, Boxes, Circles, Diamonds, Connector Arrows)
                    val titlePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF0F172A.toInt()
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val bodyPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF1E293B.toInt()
                        textSize = 13.5f
                    }

                    for (obj in objects) {
                        val isSel = obj.id == selectedObjectId
                        when (obj.type) {
                            CanvasObjectType.STICKY_NOTE, CanvasObjectType.TEXT_CARD -> {
                                // Drop shadow
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = 0.12f),
                                    topLeft = Offset(obj.x + 4f, obj.y + 5f),
                                    size = Size(obj.width, obj.height),
                                    cornerRadius = CornerRadius(10f, 10f)
                                )
                                // Main card body
                                drawRoundRect(
                                    color = Color(obj.color.toInt()),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    cornerRadius = CornerRadius(10f, 10f)
                                )
                                // Top accent header strip
                                drawRoundRect(
                                    color = Color(obj.strokeColor.toInt()).copy(alpha = 0.22f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, 22f),
                                    cornerRadius = CornerRadius(10f, 10f)
                                )
                                // Border
                                drawRoundRect(
                                    color = if (isSel) Color(0xFF2563EB) else Color(obj.strokeColor.toInt()).copy(alpha = 0.45f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    cornerRadius = CornerRadius(10f, 10f),
                                    style = Stroke(width = if (isSel) 2.5f / zoomScale else 1.2f)
                                )

                                // Multi-line word-wrapped text inside sticky note
                                val textToDraw = obj.content.ifBlank { "Tap 'Edit Text' to write…" }
                                val maxCharsPerLine = ((obj.width - 24f) / 7.5f).toInt().coerceAtLeast(8)
                                val wrappedLines = mutableListOf<String>()
                                for (rawLine in textToDraw.split("\n")) {
                                    var rem = rawLine
                                    while (rem.length > maxCharsPerLine) {
                                        val splitIdx = rem.lastIndexOf(' ', maxCharsPerLine).takeIf { it > 0 } ?: maxCharsPerLine
                                        wrappedLines.add(rem.substring(0, splitIdx).trim())
                                        rem = rem.substring(splitIdx).trim()
                                    }
                                    if (rem.isNotEmpty()) wrappedLines.add(rem)
                                }
                                var ty = obj.y + 42f
                                for (line in wrappedLines) {
                                    if (ty < obj.y + obj.height - 8f) {
                                        drawContext.canvas.nativeCanvas.drawText(line, obj.x + 12f, ty, bodyPaint)
                                        ty += 18f
                                    }
                                }
                            }

                            CanvasObjectType.SHAPE_RECTANGLE -> {
                                drawRoundRect(
                                    color = Color(obj.color.toInt()).copy(alpha = 0.18f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    cornerRadius = CornerRadius(12f, 12f)
                                )
                                drawRoundRect(
                                    color = if (isSel) Color(0xFF2563EB) else Color(obj.strokeColor.toInt()),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    cornerRadius = CornerRadius(12f, 12f),
                                    style = Stroke(width = 2.2f)
                                )
                                if (obj.content.isNotBlank()) {
                                    titlePaint.textAlign = AndroidPaint.Align.CENTER
                                    drawContext.canvas.nativeCanvas.drawText(
                                        obj.content,
                                        obj.x + obj.width / 2f,
                                        obj.y + obj.height / 2f + 5f,
                                        titlePaint
                                    )
                                }
                            }

                            CanvasObjectType.SHAPE_CIRCLE -> {
                                val rx = obj.width / 2f
                                val ry = obj.height / 2f
                                val center = Offset(obj.x + rx, obj.y + ry)
                                drawOval(
                                    color = Color(obj.color.toInt()).copy(alpha = 0.18f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height)
                                )
                                drawOval(
                                    color = if (isSel) Color(0xFF2563EB) else Color(obj.strokeColor.toInt()),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    style = Stroke(width = 2.2f)
                                )
                                if (obj.content.isNotBlank()) {
                                    titlePaint.textAlign = AndroidPaint.Align.CENTER
                                    drawContext.canvas.nativeCanvas.drawText(
                                        obj.content,
                                        center.x,
                                        center.y + 5f,
                                        titlePaint
                                    )
                                }
                            }

                            CanvasObjectType.SHAPE_DIAMOND -> {
                                val cx = obj.x + obj.width / 2f
                                val cy = obj.y + obj.height / 2f
                                val dPath = Path().apply {
                                    moveTo(cx, obj.y)
                                    lineTo(obj.x + obj.width, cy)
                                    lineTo(cx, obj.y + obj.height)
                                    lineTo(obj.x, cy)
                                    close()
                                }
                                drawPath(path = dPath, color = Color(obj.color.toInt()).copy(alpha = 0.18f))
                                drawPath(
                                    path = dPath,
                                    color = if (isSel) Color(0xFF2563EB) else Color(obj.strokeColor.toInt()),
                                    style = Stroke(width = 2.2f, join = StrokeJoin.Round)
                                )
                                if (obj.content.isNotBlank()) {
                                    titlePaint.textAlign = AndroidPaint.Align.CENTER
                                    drawContext.canvas.nativeCanvas.drawText(obj.content, cx, cy + 5f, titlePaint)
                                }
                            }

                            CanvasObjectType.SHAPE_ARROW -> {
                                val arrowCol = if (isSel) Color(0xFF2563EB) else Color(obj.strokeColor.toInt())
                                drawLine(
                                    color = arrowCol,
                                    start = Offset(obj.x, obj.y),
                                    end = Offset(obj.endX, obj.endY),
                                    strokeWidth = 3f,
                                    cap = StrokeCap.Round
                                )
                                val angle = atan2(obj.endY - obj.y, obj.endX - obj.x)
                                val headLen = 18f
                                val h1 = Offset(
                                    obj.endX - headLen * cos(angle - Math.PI / 6.0).toFloat(),
                                    obj.endY - headLen * sin(angle - Math.PI / 6.0).toFloat()
                                )
                                val h2 = Offset(
                                    obj.endX - headLen * cos(angle + Math.PI / 6.0).toFloat(),
                                    obj.endY - headLen * sin(angle + Math.PI / 6.0).toFloat()
                                )
                                drawLine(color = arrowCol, start = Offset(obj.endX, obj.endY), end = h1, strokeWidth = 3f, cap = StrokeCap.Round)
                                drawLine(color = arrowCol, start = Offset(obj.endX, obj.endY), end = h2, strokeWidth = 3f, cap = StrokeCap.Round)
                            }
                        }

                        // Resize handle for selected card/shape
                        if (isSel && obj.type != CanvasObjectType.SHAPE_ARROW) {
                            val hr = 7f / zoomScale
                            val br = Offset(obj.x + obj.width, obj.y + obj.height)
                            drawCircle(color = Color.White, radius = hr, center = br)
                            drawCircle(color = Color(0xFF2563EB), radius = hr, center = br, style = Stroke(width = 2f / zoomScale))
                        }
                    }

                    // 3. Live Connector Arrow Preview
                    if (arrowStartWorld != null && arrowCurrentWorld != null) {
                        val (sx, sy) = arrowStartWorld!!
                        val (ex, ey) = arrowCurrentWorld!!
                        drawLine(
                            color = Color(penColor.toInt()),
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                    }

                    // 4. Render Committed Freehand Strokes on Whiteboard
                    for (stroke in boardStrokes) {
                        if (stroke.points.size < 2) continue
                        val path = Path()
                        val pts = stroke.points
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size - 1) {
                            val mx = (pts[i].x + pts[i + 1].x) / 2f
                            val my = (pts[i].y + pts[i + 1].y) / 2f
                            path.quadraticTo(pts[i].x, pts[i].y, mx, my)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                        val isHl = stroke.tool == ToolType.HIGHLIGHTER
                        drawPath(
                            path = path,
                            color = Color(stroke.color.toInt()).copy(alpha = stroke.opacity),
                            blendMode = if (isHl) BlendMode.Darken else BlendMode.SrcOver,
                            style = Stroke(
                                width = if (isHl) stroke.width * 2.4f else stroke.width,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // 5. Render Active Live Stroke
                    activeStrokeState?.let { active ->
                        if (active.points.size >= 2) {
                            val path = Path()
                            path.moveTo(active.points[0].x, active.points[0].y)
                            for (i in 1 until active.points.size) {
                                path.lineTo(active.points[i].x, active.points[i].y)
                            }
                            val isHl = active.tool == ToolType.HIGHLIGHTER
                            drawPath(
                                path = path,
                                color = Color(active.color.toInt()).copy(alpha = active.opacity),
                                style = Stroke(
                                    width = if (isHl) active.width * 2.4f else active.width,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                // --- FLOATING SELECTED OBJECT CONTEXTUAL BAR ---
                selectedObject?.let { obj ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (obj.type != CanvasObjectType.SHAPE_ARROW) {
                                FilledTonalButton(
                                    onClick = {
                                        editingObject = obj
                                        dialogText = obj.content
                                        showObjectTextDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit Text", fontSize = 11.sp)
                                }

                                // Color swatches for card/shape
                                stickyColors.forEach { (fillCol, borderCol) ->
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(Color(fillCol.toInt()))
                                            .border(
                                                width = if (obj.color == fillCol) 2.5.dp else 1.dp,
                                                color = Color(borderCol.toInt()),
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                objects = objects.map {
                                                    if (it.id == obj.id) it.copy(color = fillCol, strokeColor = borderCol) else it
                                                }
                                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                                            }
                                    )
                                }
                            }

                            // Duplicate Object
                            IconButton(onClick = {
                                val dup = obj.copy(
                                    id = UUID.randomUUID().toString(),
                                    x = obj.x + 28f,
                                    y = obj.y + 28f,
                                    endX = obj.endX + 28f,
                                    endY = obj.endY + 28f
                                )
                                objects = objects + dup
                                selectedObjectId = dup.id
                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(16.dp))
                            }

                            // Delete Object
                            IconButton(onClick = {
                                objects = objects.filter { it.id != obj.id }
                                selectedObjectId = null
                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }

                            IconButton(onClick = { selectedObjectId = null }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // --- FLOATING WHITEBOARD TOOL DOCK ---
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .shadow(14.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Mode buttons: Select/Pan, Pen, Highlighter, Eraser, Connector Arrow
                        listOf(
                            Triple(WhiteboardTool.SELECT_PAN, Icons.Default.PanTool, "Select / Move"),
                            Triple(WhiteboardTool.PEN, Icons.Default.Edit, "Freehand Pen"),
                            Triple(WhiteboardTool.HIGHLIGHTER, Icons.Default.BorderColor, "Highlighter"),
                            Triple(WhiteboardTool.ERASER, Icons.Default.AutoFixNormal, "Eraser"),
                            Triple(WhiteboardTool.CONNECTOR_ARROW, Icons.AutoMirrored.Filled.TrendingFlat, "Connector Arrow")
                        ).forEach { (tool, icon, label) ->
                            val isSel = activeWbTool == tool
                            IconButton(
                                onClick = { activeWbTool = tool },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        VerticalDivider(modifier = Modifier.height(26.dp))

                        // Add Sticky Note
                        Button(
                            onClick = {
                                editingObject = null
                                dialogText = ""
                                newObjectTypeToCreate = CanvasObjectType.STICKY_NOTE
                                showObjectTextDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sticky", fontSize = 12.sp)
                        }

                        // Add Labeled Box
                        OutlinedButton(
                            onClick = {
                                editingObject = null
                                dialogText = ""
                                newObjectTypeToCreate = CanvasObjectType.SHAPE_RECTANGLE
                                showObjectTextDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CropSquare, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Box", fontSize = 12.sp)
                        }

                        // Add Labeled Circle
                        OutlinedButton(
                            onClick = {
                                editingObject = null
                                dialogText = ""
                                newObjectTypeToCreate = CanvasObjectType.SHAPE_CIRCLE
                                showObjectTextDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Node", fontSize = 12.sp)
                        }

                        // Add Decision Diamond
                        OutlinedButton(
                            onClick = {
                                editingObject = null
                                dialogText = ""
                                newObjectTypeToCreate = CanvasObjectType.SHAPE_DIAMOND
                                showObjectTextDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ChangeHistory, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Decision", fontSize = 12.sp)
                        }

                        VerticalDivider(modifier = Modifier.height(26.dp))

                        // Ink colors
                        inkPalette.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(c.toInt()))
                                    .border(
                                        width = if (penColor == c) 2.5.dp else 1.dp,
                                        color = if (penColor == c) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { penColor = c }
                            )
                        }
                    }
                }
            }
        }

        // --- ADD OR EDIT WHITEBOARD OBJECT DIALOG ---
        if (showObjectTextDialog) {
            AlertDialog(
                onDismissRequest = {
                    showObjectTextDialog = false
                    editingObject = null
                },
                title = {
                    Text(
                        if (editingObject != null) "Edit Card Content"
                        else when (newObjectTypeToCreate) {
                            CanvasObjectType.STICKY_NOTE -> "Add Sticky Note"
                            CanvasObjectType.SHAPE_RECTANGLE -> "Add Process Box"
                            CanvasObjectType.SHAPE_CIRCLE -> "Add Concept Node"
                            CanvasObjectType.SHAPE_DIAMOND -> "Add Decision Diamond"
                            else -> "Add Card"
                        }
                    )
                },
                text = {
                    OutlinedTextField(
                        value = dialogText,
                        onValueChange = { dialogText = it },
                        label = { Text("Text / Label") },
                        placeholder = { Text("Type concept, step, or brainstorm idea…") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showObjectTextDialog = false
                            val existing = editingObject
                            if (existing != null) {
                                objects = objects.map { if (it.id == existing.id) it.copy(content = dialogText.trim()) else it }
                            } else {
                                val offsetCount = (objects.size % 6) * 24f
                                val baseX = -panOffset.x / zoomScale + 140f + offsetCount
                                val baseY = -panOffset.y / zoomScale + 120f + offsetCount
                                val (fillC, strokeC) = when (newObjectTypeToCreate) {
                                    CanvasObjectType.STICKY_NOTE -> 0xFFFEF08AL to 0xFFCA8A04L
                                    CanvasObjectType.SHAPE_RECTANGLE -> 0xFFBAE6FDL to 0xFF0284C7L
                                    CanvasObjectType.SHAPE_CIRCLE -> 0xFFBBF7D0L to 0xFF16A34AL
                                    CanvasObjectType.SHAPE_DIAMOND -> 0xFFDDD6FEL to 0xFF7C3AEDL
                                    else -> 0xFFFEF08AL to 0xFFCA8A04L
                                }
                                val newObj = CanvasObject(
                                    id = UUID.randomUUID().toString(),
                                    type = newObjectTypeToCreate,
                                    x = baseX,
                                    y = baseY,
                                    width = if (newObjectTypeToCreate == CanvasObjectType.STICKY_NOTE) 190f else 170f,
                                    height = if (newObjectTypeToCreate == CanvasObjectType.STICKY_NOTE) 150f else 110f,
                                    content = dialogText.trim(),
                                    color = fillC,
                                    strokeColor = strokeC
                                )
                                objects = objects + newObj
                                selectedObjectId = newObj.id
                                activeWbTool = WhiteboardTool.SELECT_PAN
                            }
                            coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            dialogText = ""
                            editingObject = null
                        }
                    ) {
                        Text(if (editingObject != null) "Save" else "Add to Board")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showObjectTextDialog = false
                        editingObject = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
