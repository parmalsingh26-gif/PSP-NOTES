package app.inkflow.studio.feature.editor

import android.graphics.Bitmap
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.core.design.*
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.data.repository.StudyRepository
import app.inkflow.studio.domain.model.*
import app.inkflow.studio.feature.ai.LocalAiProvider
import app.inkflow.studio.feature.audio.AudioNotePlayer
import app.inkflow.studio.feature.audio.AudioNoteRecorder
import app.inkflow.studio.feature.audio.RecordingState
import app.inkflow.studio.feature.ink.InkEngine
import app.inkflow.studio.feature.ink.RecognizedShapeType
import app.inkflow.studio.feature.ink.ShapeRecognizer
import app.inkflow.studio.feature.ink.TemplateRenderer
import app.inkflow.studio.feature.pdf.PdfExporter
import app.inkflow.studio.feature.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.min

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotebookEditorScreen(
    documentId: String,
    repository: DocumentRepository,
    studyRepository: StudyRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Document & Pages State
    val document by repository.getDocument(documentId).collectAsState(initial = null)
    val pages by repository.getPagesForDocument(documentId).collectAsState(initial = emptyList())
    var currentPageIndex by remember { mutableIntStateOf(0) }

    val currentPage = pages.getOrNull(currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

    // Ink Engine & Canvas State
    val inkEngine = remember { InkEngine() }
    var currentStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var pageTexts by remember { mutableStateOf<List<PageTextObject>>(emptyList()) }
    var activeStrokeState by remember { mutableStateOf<InkStroke?>(null) }

    // Canvas Transform (Pan & Zoom)
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Settings & Toggles
    var isStylusOnlyMode by remember { mutableStateOf(false) }
    var isShapeSnapEnabled by remember { mutableStateOf(false) }

    // Lasso Selection State
    var lassoPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var isLassoActive by remember { mutableStateOf(false) }
    var selectedStrokes by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Dialog & Sheet States
    var showPageManagerSheet by remember { mutableStateOf(false) }
    var showToolPropertiesDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showInsertTextDialog by remember { mutableStateOf(false) }
    var pendingTextInsertDocCoords by remember { mutableStateOf(Pair(100f, 100f)) }
    var insertedTextContent by remember { mutableStateOf("") }

    // Audio Subsystem
    val audioRecorder = remember { AudioNoteRecorder(context) }
    val audioPlayer = remember { AudioNotePlayer() }
    val recordingState by audioRecorder.recordingState.collectAsState()
    val recordingDurationMs by audioRecorder.durationMs.collectAsState()
    val isAudioPlaying by audioPlayer.isPlaying.collectAsState()
    val audioPlayPositionMs by audioPlayer.currentPositionMs.collectAsState()

    // AI Workspace Side-panel State
    var showAiPanel by remember { mutableStateOf(false) }
    val aiProvider = remember { LocalAiProvider() }
    var aiSummary by remember { mutableStateOf<String?>(null) }
    var aiFlashcards by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var aiQueryText by remember { mutableStateOf("") }
    var aiAnswerText by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }

    // PDF Renderer (if document type is PDF)
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val pdfRenderer = remember(document?.pdfFilePath) {
        document?.pdfFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) PdfPageRenderer(file) else null
        }
    }

    // Load strokes and text notes whenever currentPage changes
    LaunchedEffect(currentPage?.id) {
        currentPage?.id?.let { pageId ->
            val loadedStrokes = repository.loadPageStrokes(pageId)
            currentStrokes = loadedStrokes
            val loadedTexts = repository.loadPageTexts(pageId)
            pageTexts = loadedTexts
            inkEngine.clearHistory()
            selectedStrokes = emptySet()
        }
    }

    // Render PDF page bitmap whenever currentPageIndex or pdfRenderer changes
    LaunchedEffect(currentPageIndex, pdfRenderer) {
        pdfRenderer?.let { renderer ->
            val bitmap = renderer.renderPage(currentPageIndex)
            pdfBitmap = bitmap
        }
    }

    // Palette Colors (10 curated colors)
    val palette = listOf(
        0xFF0F172A, // Slate Black
        0xFF2563EB, // Royal Blue
        0xFF0D9488, // Muted Teal
        0xFFDC2626, // Crimson Red
        0xFF7C3AED, // Deep Purple
        0xFF16A34A, // Forest Green
        0xFFD97706, // Amber
        0xFFEC4899, // Pink
        0xFF475569, // Muted Slate
        0xFFF59E0B  // Gold
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // Top Action & Navigation Bar
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
                        // Left: Back & Title
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = document?.title ?: "Note",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Saved on device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        // Center: Page Navigator & Audio Bar
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Audio Record / Play Controls
                            if (recordingState == RecordingState.RECORDING) {
                                Button(
                                    onClick = { audioRecorder.stopRecording() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val sec = (recordingDurationMs / 1000) % 60
                                    val min = (recordingDurationMs / 1000) / 60
                                    Text(String.format(Locale.ROOT, "%02d:%02d", min, sec), fontSize = 12.sp)
                                }
                            } else {
                                val audioFile = File(context.filesDir, "audio/${documentId}.m4a")
                                if (audioFile.exists()) {
                                    IconButton(
                                        onClick = {
                                            if (isAudioPlaying) audioPlayer.pause() else audioPlayer.play(audioFile)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isAudioPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                            contentDescription = "Play Audio",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { audioRecorder.startRecording(documentId) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Record Audio",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            VerticalDivider(modifier = Modifier.height(24.dp))

                            // Page Flip Controls & Page Manager Button
                            IconButton(
                                enabled = currentPageIndex > 0,
                                onClick = {
                                    currentPageIndex -= 1
                                    coroutineScope.launch { repository.updateLastOpenedPage(documentId, currentPageIndex) }
                                }
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                            }

                            // Tapping page count opens Page Manager Sheet!
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showPageManagerSheet = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(
                                enabled = currentPageIndex < pages.size - 1,
                                onClick = {
                                    currentPageIndex += 1
                                    coroutineScope.launch { repository.updateLastOpenedPage(documentId, currentPageIndex) }
                                }
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
                            }

                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        repository.addPage(documentId, currentPage?.template ?: PageTemplate.RULED)
                                        currentPageIndex = pages.size // Jump to new page
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Add Page")
                            }
                        }

                        // Right: PDF Export & AI Assistant
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val exportFile = File(context.cacheDir, "${document?.title ?: "Note"}_Export.pdf")
                                        val success = PdfExporter().exportDocumentToPdf(
                                            document = document ?: return@launch,
                                            pages = pages,
                                            strokeProvider = { pageId -> repository.loadPageStrokes(pageId) },
                                            outputFile = exportFile
                                        )
                                        withContext(Dispatchers.Main) {
                                            if (success) {
                                                Toast.makeText(context, "Exported PDF: ${exportFile.name}", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Export PDF")
                            }

                            FilledTonalButton(
                                onClick = { showAiPanel = !showAiPanel },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ask InkFlow", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            // Responsive Canvas Viewport with BoxWithConstraints
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(if (isSystemInDarkTheme()) InkCanvasDark else InkCanvasLight)
            ) {
                val availableW = constraints.maxWidth.toFloat()
                val availableH = constraints.maxHeight.toFloat()

                val pageW = currentPage?.width ?: 595f
                val pageH = currentPage?.height ?: 842f

                // Compute adaptive fitScale so the page fills the screen gracefully with 24dp margin
                val margin = 24f
                val fitScaleX = (availableW - margin * 2) / pageW
                val fitScaleY = (availableH - margin * 2) / pageH
                val fitScale = min(fitScaleX, fitScaleY).coerceAtLeast(0.1f)

                val totalScale = fitScale * zoomScale

                // Centered page origin
                val originX = (availableW - pageW * totalScale) / 2f
                val originY = (availableH - pageH * totalScale) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (inkEngine.currentTool == ToolType.PAN) {
                                    zoomScale = (zoomScale * zoom).coerceIn(0.5f, 4.0f)
                                    panOffset += pan
                                }
                            }
                        }
                        .pointerInteropFilter { motionEvent ->
                            val isStylus = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            // If stylus-only mode is active and pointer is a finger, do not draw
                            if (isStylusOnlyMode && !isStylus && inkEngine.currentTool != ToolType.PAN) {
                                return@pointerInteropFilter false
                            }

                            val touchX = motionEvent.x
                            val touchY = motionEvent.y
                            val pressure = motionEvent.pressure

                            val (docX, docY) = inkEngine.screenToDocument(
                                touchX, touchY, originX, originY, panOffset.x, panOffset.y, totalScale
                            )

                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    when (inkEngine.currentTool) {
                                        ToolType.ERASER_STROKE -> {
                                            val (remaining, _) = inkEngine.eraseAt(docX, docY, 18f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, remaining) }
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            isLassoActive = true
                                            lassoPoints = listOf(Pair(docX, docY))
                                        }
                                        ToolType.TEXT -> {
                                            pendingTextInsertDocCoords = Pair(docX, docY)
                                            showInsertTextDialog = true
                                        }
                                        ToolType.PAN -> {
                                            // Managed by gesture detector
                                        }
                                        else -> {
                                            val currentAudioTime = if (recordingState == RecordingState.RECORDING) {
                                                audioRecorder.currentTimestampMs
                                            } else null

                                            inkEngine.startStroke(docX, docY, pressure, currentAudioTime)
                                            activeStrokeState = inkEngine.activeStroke
                                        }
                                    }
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    when (inkEngine.currentTool) {
                                        ToolType.ERASER_STROKE -> {
                                            val (remaining, _) = inkEngine.eraseAt(docX, docY, 18f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                lassoPoints = lassoPoints + Pair(docX, docY)
                                            }
                                        }
                                        ToolType.PAN -> {
                                            // Pan gesture
                                        }
                                        else -> {
                                            inkEngine.continueStroke(docX, docY, pressure)
                                            activeStrokeState = inkEngine.activeStroke
                                        }
                                    }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    when (inkEngine.currentTool) {
                                        ToolType.ERASER_STROKE -> {
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, currentStrokes) }
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                val selected = inkEngine.selectWithLasso(lassoPoints, currentStrokes)
                                                selectedStrokes = selected
                                                isLassoActive = false
                                            }
                                        }
                                        ToolType.PAN, ToolType.TEXT -> {
                                            // No stroke action
                                        }
                                        else -> {
                                            var completed = inkEngine.finishStroke()
                                            activeStrokeState = null

                                            if (completed != null) {
                                                // If Shape Auto-Recognition is enabled, snap shapes!
                                                if (isShapeSnapEnabled) {
                                                    val recognized = ShapeRecognizer.recognize(completed)
                                                    if (recognized.type != RecognizedShapeType.NONE) {
                                                        completed = recognized.snappedStroke
                                                    }
                                                }

                                                val updated = currentStrokes + completed
                                                currentStrokes = updated
                                                coroutineScope.launch {
                                                    currentPage?.id?.let { repository.savePageStrokes(it, updated) }
                                                }
                                            }
                                        }
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
                                translationX = originX + panOffset.x
                                translationY = originY + panOffset.y
                                scaleX = totalScale
                                scaleY = totalScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                    ) {
                        // 1. Draw Page Canvas Surface with Border & Shadow
                        drawRect(
                            color = Color.White,
                            topLeft = Offset.Zero,
                            size = Size(pageW, pageH)
                        )

                        // Page outline
                        drawRect(
                            color = Color(0xFFE2E8F0),
                            topLeft = Offset.Zero,
                            size = Size(pageW, pageH),
                            style = Stroke(width = 1f)
                        )

                        // 2. Draw PDF Background Bitmap (if available)
                        pdfBitmap?.let { bmp ->
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                                dstSize = androidx.compose.ui.unit.IntSize(pageW.toInt(), pageH.toInt())
                            )
                        } ?: run {
                            // 3. Draw Paper Rule / Template Lines
                            currentPage?.template?.let { tmpl ->
                                TemplateRenderer.drawTemplate(this, tmpl, pageW, pageH)
                            }
                        }

                        // 4. Draw Committed Vector Strokes
                        for (stroke in currentStrokes) {
                            if (stroke.points.size < 2) continue

                            val strokePath = androidx.compose.ui.graphics.Path()
                            val first = stroke.points[0]
                            strokePath.moveTo(first.x, first.y)
                            for (i in 1 until stroke.points.size) {
                                val pt = stroke.points[i]
                                strokePath.lineTo(pt.x, pt.y)
                            }

                            val isHighlight = stroke.tool == ToolType.HIGHLIGHTER
                            val isAudioActive = isAudioPlaying && stroke.audioTimestampMs != null &&
                                    kotlin.math.abs(stroke.audioTimestampMs - audioPlayPositionMs) < 1500
                            val isSelected = selectedStrokes.contains(stroke.id)

                            val drawColor = when {
                                isSelected -> Color(0xFF3B82F6) // Bright blue for lasso selection
                                isAudioActive -> Color(0xFFEAB308) // Yellow highlight for audio sync
                                else -> Color(stroke.color.toInt()).copy(alpha = stroke.opacity)
                            }

                            drawPath(
                                path = strokePath,
                                color = drawColor,
                                style = Stroke(
                                    width = if (isHighlight) stroke.width * 2.8f else stroke.width,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // 5. Draw Active Live Stroke
                        activeStrokeState?.let { active ->
                            if (active.points.size >= 2) {
                                val livePath = androidx.compose.ui.graphics.Path()
                                livePath.moveTo(active.points[0].x, active.points[0].y)
                                for (i in 1 until active.points.size) {
                                    val pt = active.points[i]
                                    livePath.lineTo(pt.x, pt.y)
                                }
                                val isHighlight = active.tool == ToolType.HIGHLIGHTER
                                drawPath(
                                    path = livePath,
                                    color = Color(active.color.toInt()).copy(alpha = active.opacity),
                                    style = Stroke(
                                        width = if (isHighlight) active.width * 2.8f else active.width,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // 6. Draw Lasso Selection Outline
                        if (isLassoActive && lassoPoints.size >= 2) {
                            val lassoPath = androidx.compose.ui.graphics.Path()
                            lassoPath.moveTo(lassoPoints[0].first, lassoPoints[0].second)
                            for (i in 1 until lassoPoints.size) {
                                lassoPath.lineTo(lassoPoints[i].first, lassoPoints[i].second)
                            }
                            drawPath(
                                path = lassoPath,
                                color = Color(0xFF2563EB),
                                style = Stroke(
                                    width = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )
                            )
                        }
                    }

                    // Render Placed Page Text Objects
                    for (textObj in pageTexts) {
                        Surface(
                            modifier = Modifier
                                .offset(
                                    x = ((originX + panOffset.x) / totalScale + textObj.x).dp,
                                    y = ((originY + panOffset.y) / totalScale + textObj.y).dp
                                )
                                .shadow(2.dp, RoundedCornerShape(6.dp)),
                            shape = RoundedCornerShape(6.dp),
                            color = textObj.backgroundColor?.let { Color(it.toInt()) } ?: Color.White
                        ) {
                            Text(
                                text = textObj.text,
                                fontSize = textObj.fontSizeSp.sp,
                                color = Color(textObj.color.toInt()),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Lasso Selection Action Bar (if strokes are selected)
                if (selectedStrokes.isNotEmpty()) {
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${selectedStrokes.size} selected",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )

                            // Move Left
                            IconButton(onClick = {
                                val updated = inkEngine.moveSelectedStrokes(-20f, 0f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Move Left", modifier = Modifier.size(18.dp))
                            }
                            // Move Right
                            IconButton(onClick = {
                                val updated = inkEngine.moveSelectedStrokes(20f, 0f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Move Right", modifier = Modifier.size(18.dp))
                            }
                            // Move Up
                            IconButton(onClick = {
                                val updated = inkEngine.moveSelectedStrokes(0f, -20f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                            }
                            // Move Down
                            IconButton(onClick = {
                                val updated = inkEngine.moveSelectedStrokes(0f, 20f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                            }

                            // Delete Selection
                            IconButton(onClick = {
                                val updated = inkEngine.deleteSelectedStrokes(currentStrokes)
                                currentStrokes = updated
                                selectedStrokes = emptySet()
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Selection", tint = MaterialTheme.colorScheme.error)
                            }

                            // Deselect
                            IconButton(onClick = {
                                inkEngine.clearSelection()
                                selectedStrokes = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect")
                            }
                        }
                    }
                }

                // Floating Inking Toolbar Dock
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .shadow(16.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Tool Buttons: Pen, Ballpoint, Pencil, Highlighter, Eraser, Lasso, Text, Pan
                        listOf(
                            Triple(ToolType.PEN, Icons.Default.Edit, "Fountain Pen"),
                            Triple(ToolType.BALLPOINT, Icons.Default.DriveFileRenameOutline, "Ballpoint"),
                            Triple(ToolType.PENCIL, Icons.Default.Create, "Pencil"),
                            Triple(ToolType.HIGHLIGHTER, Icons.Default.BorderColor, "Highlighter"),
                            Triple(ToolType.ERASER_STROKE, Icons.Default.AutoFixNormal, "Eraser"),
                            Triple(ToolType.LASSO, Icons.Default.Gesture, "Lasso Select"),
                            Triple(ToolType.TEXT, Icons.Default.TextFields, "Text Note"),
                            Triple(ToolType.PAN, Icons.Default.PanTool, "Pan & Zoom")
                        ).forEach { (tool, icon, label) ->
                            val isSelected = inkEngine.currentTool == tool
                            IconButton(
                                onClick = {
                                    if (isSelected && (tool == ToolType.PEN || tool == ToolType.BALLPOINT || tool == ToolType.HIGHLIGHTER || tool == ToolType.PENCIL)) {
                                        showToolPropertiesDialog = true
                                    } else {
                                        inkEngine.currentTool = tool
                                    }
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        VerticalDivider(modifier = Modifier.height(28.dp))

                        // Color Palette Swatches (first 6 colors)
                        palette.take(6).forEach { c ->
                            val isColorSelected = inkEngine.currentColor == c
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(c.toInt()))
                                    .border(
                                        width = if (isColorSelected) 3.dp else 1.dp,
                                        color = if (isColorSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { inkEngine.currentColor = c }
                            )
                        }

                        // More Colors / Custom Color Button
                        IconButton(
                            onClick = { showCustomColorDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = "Custom Color", modifier = Modifier.size(18.dp))
                        }

                        VerticalDivider(modifier = Modifier.height(28.dp))

                        // Tool Properties Dialog button (Width, Palm Rejection, Shape Snapping)
                        IconButton(
                            onClick = { showToolPropertiesDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Tool Settings", modifier = Modifier.size(18.dp))
                        }

                        VerticalDivider(modifier = Modifier.height(28.dp))

                        // Undo & Redo Controls
                        IconButton(
                            enabled = inkEngine.canUndo,
                            onClick = {
                                val undone = inkEngine.undo(currentStrokes)
                                currentStrokes = undone
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageStrokes(it, undone) }
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }

                        IconButton(
                            enabled = inkEngine.canRedo,
                            onClick = {
                                val redone = inkEngine.redo(currentStrokes)
                                currentStrokes = redone
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageStrokes(it, redone) }
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                        }
                    }
                }
            }
        }

        // --- PAGE MANAGER BOTTOM SHEET ---
        if (showPageManagerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPageManagerSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Page Manager (${pages.size} pages)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Duplicate Page
                            OutlinedButton(
                                onClick = {
                                    currentPage?.let { p ->
                                        coroutineScope.launch {
                                            val dup = repository.duplicatePage(p.id, documentId)
                                            if (dup != null) {
                                                Toast.makeText(context, "Page duplicated", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Duplicate")
                            }

                            // Delete Page (if > 1 page)
                            OutlinedButton(
                                enabled = pages.size > 1,
                                onClick = {
                                    currentPage?.let { p ->
                                        coroutineScope.launch {
                                            val deleted = repository.deletePage(p.id, documentId)
                                            if (deleted) {
                                                currentPageIndex = currentPageIndex.coerceAtMost(pages.size - 2)
                                                Toast.makeText(context, "Page deleted", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }

                            // Add New Page
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        repository.addPage(documentId, currentPage?.template ?: PageTemplate.RULED)
                                        currentPageIndex = pages.size
                                        showPageManagerSheet = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Page")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Change Template Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Template: ", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterVertically))
                        PageTemplate.values().forEach { tmpl ->
                            FilterChip(
                                selected = currentPage?.template == tmpl,
                                onClick = {
                                    currentPage?.let { p ->
                                        coroutineScope.launch {
                                            repository.updatePageTemplate(p.id, tmpl)
                                        }
                                    }
                                },
                                label = { Text(tmpl.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Grid of All Pages
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.height(280.dp)
                    ) {
                        items(pages, key = { it.id }) { p ->
                            val isCurrent = p.index == currentPageIndex
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clickable {
                                        currentPageIndex = p.index
                                        showPageManagerSheet = false
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Page ${p.index + 1}",
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = p.template.name.take(6),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Bookmark Star
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                repository.setPageBookmark(p.id, !p.isBookmarked)
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (p.isBookmarked) Icons.Default.Star else Icons.Outlined.StarBorder,
                                            contentDescription = "Bookmark",
                                            tint = if (p.isBookmarked) Color(0xFFEAB308) else Color.LightGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- TOOL PROPERTIES DIALOG ---
        if (showToolPropertiesDialog) {
            AlertDialog(
                onDismissRequest = { showToolPropertiesDialog = false },
                title = { Text("Pen & Canvas Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Stroke Width Slider with Numeric Display
                        Text("Stroke Width: ${String.format(Locale.ROOT, "%.1f pt", inkEngine.currentWidth)}", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = inkEngine.currentWidth,
                                onValueChange = { inkEngine.currentWidth = it },
                                valueRange = 0.5f..20f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            // Preview Nib
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(inkEngine.currentColor.toInt())),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size((inkEngine.currentWidth * 1.5f).dp.coerceIn(2.dp, 24.dp))
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }

                        HorizontalDivider()

                        // Palm Rejection Setting
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Stylus-Only Mode (Palm Rejection)", fontWeight = FontWeight.SemiBold)
                                Text("Ignore accidental finger touches while writing with stylus.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isStylusOnlyMode,
                                onCheckedChange = { isStylusOnlyMode = it }
                            )
                        }

                        HorizontalDivider()

                        // Shape Recognition Snapping
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shape Auto-Recognition", fontWeight = FontWeight.SemiBold)
                                Text("Automatically snap hand-drawn lines, circles, and boxes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isShapeSnapEnabled,
                                onCheckedChange = { isShapeSnapEnabled = it }
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showToolPropertiesDialog = false }) {
                        Text("Done")
                    }
                }
            )
        }

        // --- CUSTOM COLOR PICKER DIALOG ---
        if (showCustomColorDialog) {
            var hexInput by remember { mutableStateOf("#2563EB") }
            AlertDialog(
                onDismissRequest = { showCustomColorDialog = false },
                title = { Text("Custom Color Picker") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Choose from expanded palette:")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            palette.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.toInt()))
                                        .clickable {
                                            inkEngine.currentColor = c
                                            showCustomColorDialog = false
                                        }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Or enter HEX code:")
                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { hexInput = it },
                            singleLine = true,
                            placeholder = { Text("#RRGGBB") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            try {
                                val cleanHex = hexInput.replace("#", "")
                                val parsed = cleanHex.toLong(16) or 0xFF000000
                                inkEngine.currentColor = parsed
                                showCustomColorDialog = false
                            } catch (e: Exception) {
                                Toast.makeText(context, "Invalid HEX code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomColorDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- INSERT TEXT NOTE DIALOG ---
        if (showInsertTextDialog) {
            AlertDialog(
                onDismissRequest = { showInsertTextDialog = false },
                title = { Text("Insert Typed Note") },
                text = {
                    OutlinedTextField(
                        value = insertedTextContent,
                        onValueChange = { insertedTextContent = it },
                        label = { Text("Note Text") },
                        placeholder = { Text("Type summary, equation, or reminder…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (insertedTextContent.isNotBlank()) {
                                showInsertTextDialog = false
                                val newObj = PageTextObject(
                                    id = UUID.randomUUID().toString(),
                                    pageId = currentPage?.id ?: "",
                                    x = pendingTextInsertDocCoords.first,
                                    y = pendingTextInsertDocCoords.second,
                                    text = insertedTextContent
                                )
                                val updated = pageTexts + newObj
                                pageTexts = updated
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageTexts(it, updated) }
                                }
                                insertedTextContent = ""
                            }
                        }
                    ) {
                        Text("Insert")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInsertTextDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- "ASK INKFLOW" SIDE PANEL ---
        AnimatedVisibility(
            visible = showAiPanel,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .shadow(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ask InkFlow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showAiPanel = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Text(
                        text = "100% On-Device · Grounded in your notes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // AI Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isAiLoading = true
                                    val textSource = document?.title ?: "Note"
                                    val result = aiProvider.generateSummary("$textSource. Key principles: Digital ink vector architecture, offline local-first storage, supermemo SM-2 spaced repetition.")
                                    aiSummary = result.getOrNull()
                                    isAiLoading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Summarize", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isAiLoading = true
                                    val textSource = document?.title ?: "Study Notes"
                                    val result = aiProvider.generateFlashcards("$textSource is a comprehensive study document. Spaced repetition is an evidence-based learning technique. SM-2 is the foundation algorithm for optimal retention intervals.")
                                    aiFlashcards = result.getOrDefault(emptyList())
                                    isAiLoading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Make Cards", fontSize = 12.sp)
                        }
                    }

                    if (isAiLoading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // Display Summary
                    aiSummary?.let { summary ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Summary", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Display Generated Flashcards
                    if (aiFlashcards.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Generated Flashcards (${aiFlashcards.size})", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        aiFlashcards.forEach { (front, back) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Q: $front", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("A: $back", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val cards = aiFlashcards.map { (f, b) ->
                                        StudyCard(
                                            id = UUID.randomUUID().toString(),
                                            documentId = documentId,
                                            front = f,
                                            back = b
                                        )
                                    }
                                    studyRepository.saveCards(cards)
                                    Toast.makeText(context, "Saved ${cards.size} cards to Study Sets!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save All Cards to Study Deck")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Ask Question Form
                    OutlinedTextField(
                        value = aiQueryText,
                        onValueChange = { aiQueryText = it },
                        placeholder = { Text("Ask anything about this note…") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isAiLoading = true
                                val result = aiProvider.answerQuestion(
                                    content = document?.title ?: "",
                                    question = aiQueryText
                                )
                                aiAnswerText = result.getOrNull()
                                isAiLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Find Answer")
                    }

                    aiAnswerText?.let { ans ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(ans, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
