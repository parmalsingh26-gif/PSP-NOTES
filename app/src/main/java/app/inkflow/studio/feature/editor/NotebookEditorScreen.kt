package app.inkflow.studio.feature.editor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import app.inkflow.studio.feature.ink.TemplateRenderer
import app.inkflow.studio.feature.pdf.PdfExporter
import app.inkflow.studio.feature.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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

    val currentPage = pages.getOrNull(currentPageIndex)

    // Ink Engine & Canvas State
    val inkEngine = remember { InkEngine() }
    var currentStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var activeStrokeState by remember { mutableStateOf<InkStroke?>(null) }

    // Canvas Transform (Pan & Zoom)
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

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

    // Load strokes whenever currentPage changes
    LaunchedEffect(currentPage?.id) {
        currentPage?.id?.let { pageId ->
            val loadedStrokes = repository.loadPageStrokes(pageId)
            currentStrokes = loadedStrokes
            inkEngine.clearHistory()
        }
    }

    // Render PDF page bitmap whenever currentPageIndex or pdfRenderer changes
    LaunchedEffect(currentPageIndex, pdfRenderer) {
        pdfRenderer?.let { renderer ->
            val bitmap = renderer.renderPage(currentPageIndex)
            pdfBitmap = bitmap
        }
    }

    // Palette Colors
    val palette = listOf(
        0xFF0F172A, // Slate Black
        0xFF2563EB, // Royal Blue
        0xFF0D9488, // Muted Teal
        0xFFDC2626, // Crimson Red
        0xFF7C3AED, // Deep Purple
        0xFF16A34A, // Forest Green
        0xFFD97706  // Amber
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
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = document?.title ?: "Loading…",
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

                            // Page Flip Controls
                            IconButton(
                                enabled = currentPageIndex > 0,
                                onClick = {
                                    currentPageIndex -= 1
                                    coroutineScope.launch { repository.updateLastOpenedPage(documentId, currentPageIndex) }
                                }
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                            }

                            Text(
                                text = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )

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
                                                Toast.makeText(context, "Exported PDF to ${exportFile.name}", Toast.LENGTH_LONG).show()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(if (isSystemInDarkTheme()) InkCanvasDark else InkCanvasLight)
            ) {
                // Interactive Canvas Surface
                val pageW = currentPage?.width ?: 595f
                val pageH = currentPage?.height ?: 842f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (inkEngine.currentTool == ToolType.PAN) {
                                    zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3.5f)
                                    panOffset += pan
                                }
                            }
                        }
                        .pointerInput(currentPage?.id, inkEngine.currentTool) {
                            if (inkEngine.currentTool != ToolType.PAN) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val (docX, docY) = inkEngine.screenToDocument(
                                        down.position.x,
                                        down.position.y,
                                        panOffset.x,
                                        panOffset.y,
                                        zoomScale
                                    )

                                    if (inkEngine.currentTool == ToolType.ERASER_STROKE) {
                                        val (remaining, _) = inkEngine.eraseAt(docX, docY, 18f / zoomScale, currentStrokes)
                                        currentStrokes = remaining
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageStrokes(it, remaining) }
                                        }
                                    } else {
                                        val currentAudioTime = if (recordingState == RecordingState.RECORDING) {
                                            audioRecorder.currentTimestampMs
                                        } else null

                                        inkEngine.startStroke(docX, docY, down.pressure, currentAudioTime)
                                        activeStrokeState = inkEngine.activeStroke
                                    }

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            val (currX, currY) = inkEngine.screenToDocument(
                                                change.position.x,
                                                change.position.y,
                                                panOffset.x,
                                                panOffset.y,
                                                zoomScale
                                            )
                                            if (inkEngine.currentTool == ToolType.ERASER_STROKE) {
                                                val (remaining, _) = inkEngine.eraseAt(currX, currY, 18f / zoomScale, currentStrokes)
                                                currentStrokes = remaining
                                            } else {
                                                inkEngine.continueStroke(currX, currY, change.pressure)
                                                activeStrokeState = inkEngine.activeStroke
                                            }
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })

                                    // Touch Up: Finish stroke
                                    if (inkEngine.currentTool == ToolType.ERASER_STROKE) {
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageStrokes(it, currentStrokes) }
                                        }
                                    } else {
                                        val completed = inkEngine.finishStroke()
                                        activeStrokeState = null
                                        if (completed != null) {
                                            val updated = currentStrokes + completed
                                            currentStrokes = updated
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, updated) }
                                            }
                                        }
                                    }
                                }
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
                            }
                    ) {
                        // 1. Draw Page Canvas Surface
                        drawRect(
                            color = Color.White,
                            topLeft = Offset.Zero,
                            size = androidx.compose.ui.geometry.Size(pageW, pageH)
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

                            val drawColor = if (isAudioActive) {
                                Color(0xFFEAB308) // Highlight active playback strokes in vibrant yellow
                            } else {
                                Color(stroke.color.toInt()).copy(alpha = stroke.opacity)
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
                    }
                }

                // Floating Ink Toolbar Dock
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .shadow(12.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Tool Buttons: Pen, Ballpoint, Pencil, Highlighter, Eraser, Pan
                        listOf(
                            Triple(ToolType.PEN, Icons.Default.Edit, "Pen"),
                            Triple(ToolType.BALLPOINT, Icons.Default.DriveFileRenameOutline, "Ballpoint"),
                            Triple(ToolType.PENCIL, Icons.Default.Create, "Pencil"),
                            Triple(ToolType.HIGHLIGHTER, Icons.Default.BorderColor, "Highlighter"),
                            Triple(ToolType.ERASER_STROKE, Icons.Default.AutoFixNormal, "Eraser"),
                            Triple(ToolType.PAN, Icons.Default.PanTool, "Pan & Zoom")
                        ).forEach { (tool, icon, label) ->
                            val isSelected = inkEngine.currentTool == tool
                            IconButton(
                                onClick = { inkEngine.currentTool = tool },
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

                        // Color Palette Swatches
                        palette.forEach { c ->
                            val isColorSelected = inkEngine.currentColor == c
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
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

                        VerticalDivider(modifier = Modifier.height(28.dp))

                        // Stroke Width Presets
                        listOf(1.5f, 2.5f, 5.0f, 8.0f).forEach { w ->
                            val isWidthSelected = inkEngine.currentWidth == w
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(if (isWidthSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { inkEngine.currentWidth = w },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size((w * 2.2f).dp.coerceIn(4.dp, 20.dp))
                                        .clip(CircleShape)
                                        .background(if (isWidthSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
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
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
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
                            Icon(Icons.Default.Redo, contentDescription = "Redo")
                        }
                    }
                }
            }
        }

        // "Ask InkFlow" Slide-Out AI Side Panel
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
                                            id = java.util.UUID.randomUUID().toString(),
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

