package app.inkflow.studio.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.net.Uri
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import app.inkflow.studio.feature.ink.PenPreset
import app.inkflow.studio.feature.ink.PresetShapeType
import app.inkflow.studio.feature.ink.RecognizedShapeType
import app.inkflow.studio.feature.ink.ShapeFactory
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
import kotlin.math.*

private enum class ImageHandleType {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ROTATE
}

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
    var activeToolState by remember { mutableStateOf(ToolType.PEN) }
    var activeColorState by remember { mutableLongStateOf(0xFF0F172AL) }
    var activeWidthState by remember { mutableFloatStateOf(2.5f) }

    // Keep inkEngine synced with reactive state
    fun selectTool(tool: ToolType) {
        activeToolState = tool
        inkEngine.currentTool = tool
    }
    fun selectColor(color: Long) {
        activeColorState = color
        inkEngine.currentColor = color
    }
    fun selectWidth(width: Float) {
        activeWidthState = width
        inkEngine.currentWidth = width
    }

    var currentStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var pageTexts by remember { mutableStateOf<List<PageTextObject>>(emptyList()) }
    var pageImages by remember { mutableStateOf<List<PageImageObject>>(emptyList()) }
    var activeStrokeState by remember { mutableStateOf<InkStroke?>(null) }

    // Saved Pen Presets
    var penPresets by remember {
        mutableStateOf(
            listOf(
                PenPreset("p1", "Fine Ink", ToolType.PEN, 0xFF0F172A, 2.2f),
                PenPreset("p2", "Lecture Blue", ToolType.BALLPOINT, 0xFF2563EB, 2.5f),
                PenPreset("p3", "Markup Red", ToolType.PEN, 0xFFDC2626, 2.8f),
                PenPreset("p4", "Sketch Pencil", ToolType.PENCIL, 0xFF475569, 3.0f),
                PenPreset("p5", "Highlighter", ToolType.HIGHLIGHTER, 0xFFF59E0B, 10.0f)
            )
        )
    }

    // Decoded Bitmap Cache for Page Images
    val imageBitmaps = remember { mutableStateMapOf<String, Bitmap>() }

    // Canvas Transform (Pan & Zoom)
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Multi-touch Pinch & Pan tracking
    var isPinching by remember { mutableStateOf(false) }
    var lastPinchDist by remember { mutableFloatStateOf(0f) }
    var lastPinchMid by remember { mutableStateOf(Offset.Zero) }

    // Settings & Toggles
    var isStylusOnlyMode by remember { mutableStateOf(false) }
    var isShapeSnapEnabled by remember { mutableStateOf(false) }

    // Interactive Digital Ruler State
    var showRuler by remember { mutableStateOf(false) }
    var rulerX by remember { mutableFloatStateOf(297f) }
    var rulerY by remember { mutableFloatStateOf(420f) }
    var rulerAngleDeg by remember { mutableFloatStateOf(0f) }
    var isDraggingRuler by remember { mutableStateOf(false) }
    var rulerDragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // PDF Reading Guide (Red Line Overlay)
    var showReadingGuide by remember { mutableStateOf(false) }
    var readingGuideY by remember { mutableFloatStateOf(300f) }
    var isDraggingReadingGuide by remember { mutableStateOf(false) }
    var readingGuideLastTapTime by remember { mutableLongStateOf(0L) }

    // Preset Shapes State
    var selectedPresetShape by remember { mutableStateOf(PresetShapeType.RECTANGLE) }
    var showPresetShapeMenu by remember { mutableStateOf(false) }
    var shapeStartPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var shapeCurrentPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Image Manipulation State
    var selectedImageId by remember { mutableStateOf<String?>(null) }
    val selectedImage = pageImages.find { it.id == selectedImageId }
    var activeImageHandle by remember { mutableStateOf(ImageHandleType.NONE) }
    var imageDragStartPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var initialImageSnapshot by remember { mutableStateOf<PageImageObject?>(null) }
    var showImageCropDialog by remember { mutableStateOf(false) }
    var lastImageTapTime by remember { mutableLongStateOf(0L) }
    var lastTappedImageId by remember { mutableStateOf<String?>(null) }

    // Text Manipulation State
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    val selectedText = pageTexts.find { it.id == selectedTextId }
    var isDraggingText by remember { mutableStateOf(false) }
    var textDragStartPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var initialTextPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var editingTextObject by remember { mutableStateOf<PageTextObject?>(null) }

    // Lasso Selection State
    var lassoPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var isLassoActive by remember { mutableStateOf(false) }
    var selectedStrokes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDraggingLassoSelection by remember { mutableStateOf(false) }
    var lassoDragLastPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Dialog & Sheet States
    var showPageManagerSheet by remember { mutableStateOf(false) }
    var showToolPropertiesDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showInsertTextDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showInsertPageDialog by remember { mutableStateOf(false) }
    var insertPageTargetIndex by remember { mutableIntStateOf(0) }
    var insertPageSelectedTemplate by remember { mutableStateOf(PageTemplate.RULED) }
    var insertPagePositionMode by remember { mutableStateOf("AFTER") } // "AFTER", "BEFORE", "END"
    var pendingTextInsertDocCoords by remember { mutableStateOf(Pair(100f, 100f)) }
    var insertedTextContent by remember { mutableStateOf("") }

    // SAF Launcher to Save Exported PDF anywhere on Device
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            coroutineScope.launch {
                val doc = document ?: return@launch
                val tempPdf = File(context.cacheDir, "export_temp_${doc.id}.pdf")
                val success = PdfExporter().exportDocumentToPdf(
                    document = doc,
                    pages = pages,
                    strokeProvider = { pageId -> repository.loadPageStrokes(pageId) },
                    imageProvider = { pageId -> repository.loadPageImages(pageId) },
                    textProvider = { pageId -> repository.loadPageTexts(pageId) },
                    outputFile = tempPdf
                )
                if (success && tempPdf.exists()) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(targetUri)?.use { out ->
                            tempPdf.inputStream().use { input -> input.copyTo(out) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "PDF saved to device!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to export PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val savedPath = repository.copyImageFromUri(it)
                    val targetPage = currentPage ?: return@launch
                    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(savedPath) }
                    if (bmp != null) {
                        imageBitmaps[savedPath] = bmp
                    }
                    val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
                    val initW = 240f
                    val initH = (initW / aspect).coerceIn(80f, 360f)

                    val newImg = PageImageObject(
                        id = UUID.randomUUID().toString(),
                        pageId = targetPage.id,
                        filePath = savedPath,
                        x = ((targetPage.width - initW) / 2f).coerceAtLeast(20f),
                        y = ((targetPage.height - initH) / 2f).coerceAtLeast(20f),
                        width = initW,
                        height = initH,
                        zIndex = (pageImages.maxOfOrNull { img -> img.zIndex } ?: 0) + 1
                    )
                    val updated = pageImages + newImg
                    pageImages = updated
                    selectedImageId = newImg.id
                    selectedTextId = null
                    selectTool(ToolType.PAN)
                    repository.savePageImages(targetPage.id, updated)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image inserted — drag handles to resize or rotate", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to insert image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Audio Subsystem
    val audioRecorder = remember { AudioNoteRecorder(context) }
    val audioPlayer = remember { AudioNotePlayer() }
    val recordingState by audioRecorder.recordingState.collectAsState()
    val recordingDurationMs by audioRecorder.durationMs.collectAsState()
    val isAudioPlaying by audioPlayer.isPlaying.collectAsState()
    val audioPlayPositionMs by audioPlayer.currentPositionMs.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            if (audioRecorder.recordingState.value == RecordingState.RECORDING) {
                audioRecorder.stopRecording()
            }
            audioPlayer.stop()
        }
    }

    // AI Workspace Side-panel State (100% Real Document Grounding)
    var showAiPanel by remember { mutableStateOf(false) }
    val aiProvider = remember { LocalAiProvider() }
    var aiSummary by remember { mutableStateOf<String?>(null) }
    var aiFlashcards by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var aiQueryText by remember { mutableStateOf("") }
    var aiAnswerText by remember { mutableStateOf<String?>(null) }
    var quickStudyNoteInput by remember { mutableStateOf("") }
    var isAiLoading by remember { mutableStateOf(false) }

    // Helper that builds the REAL document text & handwriting corpus across all pages
    suspend fun buildRealDocumentCorpus(): String {
        val sb = StringBuilder()
        document?.let { doc ->
            sb.append("Document Title: ${doc.title}.\n")
            if (doc.tags.isNotEmpty()) {
                sb.append("Tags: ${doc.tags.joinToString(", ")}.\n")
            }
        }
        for (p in pages) {
            val pNum = p.index + 1
            val texts = if (p.id == currentPage?.id) pageTexts else repository.loadPageTexts(p.id)
            val strokes = if (p.id == currentPage?.id) currentStrokes else repository.loadPageStrokes(p.id)
            for (t in texts) {
                sb.append("[Page $pNum] ${t.text.trim()}.\n")
            }
            val layoutDesc = aiProvider.analyzeHandwritingStructure(strokes, pNum)
            if (layoutDesc.isNotBlank()) {
                sb.append("$layoutDesc\n")
            }
        }
        return sb.toString().trim()
    }

    // PDF Renderer (if document type is PDF)
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val pdfRenderer = remember(document?.pdfFilePath) {
        document?.pdfFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) PdfPageRenderer(file) else null
        }
    }

    DisposableEffect(pdfRenderer) {
        onDispose {
            pdfRenderer?.close()
        }
    }

    // Track previous page id so we can save before switching
    var previousPageId by remember { mutableStateOf<String?>(null) }

    // Load strokes, text notes, and images whenever currentPage changes
    // PART 1 FIX: Save current page data BEFORE loading new page data to prevent image/text loss on navigation
    LaunchedEffect(currentPage?.id) {
        val newPageId = currentPage?.id ?: return@LaunchedEffect
        // Save previous page content before switching away (prevents data loss on page navigation)
        previousPageId?.let { prevId ->
            if (prevId != newPageId) {
                repository.savePageStrokes(prevId, currentStrokes)
                repository.savePageTexts(prevId, pageTexts)
                repository.savePageImages(prevId, pageImages)
            }
        }
        previousPageId = newPageId
        // Load new page content
        currentStrokes = repository.loadPageStrokes(newPageId)
        pageTexts = repository.loadPageTexts(newPageId)
        pageImages = repository.loadPageImages(newPageId)
        inkEngine.clearHistory()
        selectedStrokes = emptySet()
        selectedImageId = null
        selectedTextId = null
    }

    // Decode bitmaps for any newly loaded images safely on IO and commit to state on Main thread
    LaunchedEffect(pageImages) {
        val loaded = withContext(Dispatchers.IO) {
            pageImages.mapNotNull { img ->
                if (!imageBitmaps.containsKey(img.filePath)) {
                    val f = File(img.filePath)
                    if (f.exists()) {
                        val bmp = BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) img.filePath to bmp else null
                    } else null
                } else null
            }
        }
        for ((path, bmp) in loaded) {
            imageBitmaps[path] = bmp
        }
    }

    // View Mode: Continuous Vertical Scrolling (like standard PDF / Acrobat) vs Single Page Flip
    var isContinuousVerticalScroll by remember { mutableStateOf(document?.type == DocumentType.PDF) }
    val pdfBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }

    // Render PDF page bitmap whenever currentPageIndex, pdfRenderer, or isContinuousVerticalScroll changes
    LaunchedEffect(currentPageIndex, pdfRenderer, isContinuousVerticalScroll) {
        pdfRenderer?.let { renderer ->
            val pCount = renderer.pageCount
            val pagesToLoad = if (isContinuousVerticalScroll && pCount > 1) {
                ((currentPageIndex - 1).coerceAtLeast(0)..(currentPageIndex + 2).coerceAtMost(pCount - 1)).toList()
            } else {
                listOf(currentPageIndex)
            }
            for (idx in pagesToLoad) {
                if (!pdfBitmaps.containsKey(idx)) {
                    val bmp = renderer.renderPage(idx)
                    if (bmp != null) {
                        pdfBitmaps[idx] = bmp
                        if (idx == currentPageIndex) pdfBitmap = bmp
                    }
                } else if (idx == currentPageIndex) {
                    pdfBitmap = pdfBitmaps[idx]
                }
            }
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

    // Helper: Snap point to Digital Ruler edge if ruler is active and point is within 28pt of edge
    fun snapPointToRulerIfNear(docX: Float, docY: Float): Pair<Float, Float> {
        if (!showRuler) return Pair(docX, docY)
        val rad = Math.toRadians(rulerAngleDeg.toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        val normX = -dirY
        val normY = dirX

        val relX = docX - rulerX
        val relY = docY - rulerY
        val along = relX * dirX + relY * dirY
        val perp = relX * normX + relY * normY

        // Ruler length is 440pt (-220..+220), thickness is 44pt (-22..+22)
        if (abs(along) <= 230f && abs(perp) in 12f..46f) {
            val edgeOffset = if (perp >= 0f) 22f else -22f
            val snappedX = rulerX + along * dirX + edgeOffset * normX
            val snappedY = rulerY + along * dirY + edgeOffset * normY
            return Pair(snappedX, snappedY)
        }
        return Pair(docX, docY)
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
                                    text = "Saved locally · ${currentStrokes.size} strokes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        // Center: Page Navigator & Audio Bar
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            modifier = Modifier.size(26.dp)
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

                            IconButton(
                                enabled = currentPageIndex > 0,
                                onClick = {
                                    currentPageIndex -= 1
                                    coroutineScope.launch { repository.updateLastOpenedPage(documentId, currentPageIndex) }
                                }
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                            }

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

                            // Scroll Mode Toggle: Single Page Flip vs Continuous Vertical Scroll
                            IconButton(
                                onClick = {
                                    isContinuousVerticalScroll = !isContinuousVerticalScroll
                                    Toast.makeText(
                                        context,
                                        if (isContinuousVerticalScroll) "Vertical Scrolling View (Standard PDF)" else "Single Page View",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isContinuousVerticalScroll) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                Icon(
                                    imageVector = if (isContinuousVerticalScroll) Icons.Default.ViewStream else Icons.Default.CropPortrait,
                                    contentDescription = "Toggle Scroll Mode",
                                    tint = if (isContinuousVerticalScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    insertPageTargetIndex = currentPageIndex
                                    insertPagePositionMode = "AFTER"
                                    insertPageSelectedTemplate = currentPage?.template ?: PageTemplate.RULED
                                    showInsertPageDialog = true
                                }
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Insert Page Here")
                            }
                        }

                        // Right: Ruler toggle, PDF Export, & Ask InkFlow
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { showRuler = !showRuler },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (showRuler) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                Icon(
                                    Icons.Default.Straighten,
                                    contentDescription = "Toggle Ruler",
                                    tint = if (showRuler) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // PDF Reading Guide Toggle (Red Line Overlay for tracking reading position)
                            IconButton(
                                onClick = { showReadingGuide = !showReadingGuide },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (showReadingGuide) Color(0x22DC2626) else Color.Transparent)
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Toggle Reading Guide",
                                    tint = if (showReadingGuide) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(onClick = { showExportDialog = true }) {
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

                val margin = 24f
                val fitScaleX = (availableW - margin * 2) / pageW
                val fitScaleY = (availableH - margin * 2) / pageH
                val fitScale = min(fitScaleX, fitScaleY).coerceAtLeast(0.1f)

                val totalScale = fitScale * zoomScale

                val originX = (availableW - pageW * totalScale) / 2f
                val originY = (availableH - pageH * totalScale) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInteropFilter { motionEvent ->
                            // 1. Multi-touch handling: 2+ fingers for Pinch-Zoom and Pan
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
                                        shapeStartPoint = null
                                        shapeCurrentPoint = null
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (isPinching && lastPinchDist > 10f) {
                                            val factor = dist / lastPinchDist
                                            zoomScale = (zoomScale * factor).coerceIn(0.4f, 5.0f)
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

                            val isStylus = motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            val isStylusEraser = (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) ||
                                    motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

                            if (isStylusOnlyMode && !isStylus && activeToolState != ToolType.PAN) {
                                return@pointerInteropFilter false
                            }

                            val touchX = motionEvent.x
                            val touchY = motionEvent.y
                            val pressure = motionEvent.pressure

                            val (rawDocX, rawDocY) = inkEngine.screenToDocument(
                                touchX, touchY, originX, originY, panOffset.x, panOffset.y, totalScale
                            )

                            val effectiveTool = if (isStylusEraser) ToolType.ERASER_STROKE else activeToolState

                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    // A0. Check if user tapped/dragged the Reading Guide line
                                    if (showReadingGuide) {
                                        val guideHitZone = 20f / totalScale
                                        if (abs(rawDocY - readingGuideY) <= guideHitZone) {
                                            val nowMs = System.currentTimeMillis()
                                            val doubleTap = nowMs - readingGuideLastTapTime < 350L
                                            readingGuideLastTapTime = nowMs
                                            if (doubleTap) {
                                                // Double-tap: hide the guide
                                                showReadingGuide = false
                                            } else {
                                                // Single tap/drag: start dragging
                                                isDraggingReadingGuide = true
                                            }
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // A. Check if user grabbed the Digital Ruler body to reposition it
                                    if (showRuler) {
                                        val rad = Math.toRadians(rulerAngleDeg.toDouble())
                                        val dirX = cos(rad).toFloat()
                                        val dirY = sin(rad).toFloat()
                                        val normX = -dirY
                                        val normY = dirX
                                        val relX = rawDocX - rulerX
                                        val relY = rawDocY - rulerY
                                        val along = relX * dirX + relY * dirY
                                        val perp = relX * normX + relY * normY
                                        if (abs(along) <= 180f && abs(perp) <= 14f) {
                                            isDraggingRuler = true
                                            rulerDragStart = Pair(rawDocX - rulerX, rawDocY - rulerY)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // B. Check Selected Image Handles (All 4 corners, top rotate knob, and body move)
                                    if (selectedImage != null && !selectedImage.isLocked) {
                                        val img = selectedImage
                                        val hitR = 32f / totalScale
                                        val rotKnobX = img.x + img.width / 2f
                                        val rotKnobY = img.y - 22f / totalScale

                                        when {
                                            hypot(rawDocX - rotKnobX, rawDocY - rotKnobY) < hitR -> {
                                                activeImageHandle = ImageHandleType.ROTATE
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(rawDocX - (img.x + img.width), rawDocY - (img.y + img.height)) < hitR -> {
                                                activeImageHandle = ImageHandleType.BOTTOM_RIGHT
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(rawDocX - img.x, rawDocY - img.y) < hitR -> {
                                                activeImageHandle = ImageHandleType.TOP_LEFT
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(rawDocX - (img.x + img.width), rawDocY - img.y) < hitR -> {
                                                activeImageHandle = ImageHandleType.TOP_RIGHT
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(rawDocX - img.x, rawDocY - (img.y + img.height)) < hitR -> {
                                                activeImageHandle = ImageHandleType.BOTTOM_LEFT
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            rawDocX in img.x..(img.x + img.width) && rawDocY in img.y..(img.y + img.height) -> {
                                                activeImageHandle = ImageHandleType.MOVE
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                        }
                                    }

                                    // C. Check Selected Text Object Drag
                                    if (selectedText != null) {
                                        val txt = selectedText
                                        val lines = txt.text.split("\n")
                                        val boxW = ((lines.maxOfOrNull { it.length } ?: 4) * txt.fontSizeSp * 0.6f + 24f).coerceAtLeast(70f)
                                        val boxH = lines.size * txt.fontSizeSp * 1.4f + 16f
                                        if (rawDocX in (txt.x - 8f)..(txt.x + boxW) && rawDocY in (txt.y - 6f)..(txt.y + boxH)) {
                                            isDraggingText = true
                                            textDragStartPoint = Pair(rawDocX, rawDocY)
                                            initialTextPos = Pair(txt.x, txt.y)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // D. Check Dragging Inside Lasso Selection Bounds
                                    if (selectedStrokes.isNotEmpty()) {
                                        val selBounds = inkEngine.getSelectionBounds(currentStrokes)
                                        if (selBounds != null &&
                                            rawDocX in (selBounds.left - 12f)..(selBounds.right + 12f) &&
                                            rawDocY in (selBounds.top - 12f)..(selBounds.bottom + 12f)
                                        ) {
                                            isDraggingLassoSelection = true
                                            lassoDragLastPoint = Pair(rawDocX, rawDocY)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // E. Tap to select an Image or Text Object (Single-tap in PAN/LASSO/TEXT, or Double-tap in ANY mode)
                                    val tappedImg = pageImages.sortedBy { it.zIndex }.lastOrNull { img ->
                                        rawDocX in img.x..(img.x + img.width) && rawDocY in img.y..(img.y + img.height)
                                    }
                                    val now = System.currentTimeMillis()
                                    val isImageDoubleTap = (tappedImg != null && tappedImg.id == lastTappedImageId && (now - lastImageTapTime) < 400L)
                                    if (tappedImg != null) {
                                        lastTappedImageId = tappedImg.id
                                        lastImageTapTime = now
                                    }

                                    if (effectiveTool == ToolType.PAN || effectiveTool == ToolType.LASSO || effectiveTool == ToolType.TEXT || isImageDoubleTap) {
                                        val tappedText = pageTexts.lastOrNull { txt ->
                                            val lines = txt.text.split("\n")
                                            val boxW = ((lines.maxOfOrNull { it.length } ?: 4) * txt.fontSizeSp * 0.6f + 24f).coerceAtLeast(70f)
                                            val boxH = lines.size * txt.fontSizeSp * 1.4f + 16f
                                            rawDocX in (txt.x - 8f)..(txt.x + boxW) && rawDocY in (txt.y - 6f)..(txt.y + boxH)
                                        }
                                        if (tappedText != null) {
                                            selectedTextId = tappedText.id
                                            selectedImageId = null
                                            isDraggingText = true
                                            textDragStartPoint = Pair(rawDocX, rawDocY)
                                            initialTextPos = Pair(tappedText.x, tappedText.y)
                                            return@pointerInteropFilter true
                                        }

                                        if (tappedImg != null) {
                                            selectedImageId = tappedImg.id
                                            selectedTextId = null
                                            if (!tappedImg.isLocked) {
                                                activeImageHandle = ImageHandleType.MOVE
                                                imageDragStartPoint = Pair(rawDocX, rawDocY)
                                                initialImageSnapshot = tappedImg
                                            }
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    val (docX, docY) = snapPointToRulerIfNear(rawDocX, rawDocY)

                                    when (effectiveTool) {
                                        ToolType.ERASER_STROKE -> {
                                            val (remaining, _) = inkEngine.eraseAt(docX, docY, 20f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, remaining) }
                                            }
                                        }
                                        ToolType.ERASER_PIXEL -> {
                                            val remaining = inkEngine.erasePartialAt(docX, docY, 16f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, remaining) }
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isLassoActive = true
                                            lassoPoints = listOf(Pair(rawDocX, rawDocY))
                                        }
                                        ToolType.TEXT -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            pendingTextInsertDocCoords = Pair(rawDocX.coerceIn(16f, pageW - 120f), rawDocY.coerceIn(16f, pageH - 40f))
                                            insertedTextContent = ""
                                            editingTextObject = null
                                            showInsertTextDialog = true
                                        }
                                        ToolType.SHAPE -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            shapeStartPoint = Pair(rawDocX, rawDocY)
                                            shapeCurrentPoint = Pair(rawDocX, rawDocY)
                                        }
                                        ToolType.PAN -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            imageDragStartPoint = Pair(touchX, touchY)
                                        }
                                        else -> {
                                            selectedImageId = null
                                            selectedTextId = null
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
                                    if (isDraggingRuler && rulerDragStart != null) {
                                        rulerX = (rawDocX - rulerDragStart!!.first).coerceIn(40f, pageW - 40f)
                                        rulerY = (rawDocY - rulerDragStart!!.second).coerceIn(40f, pageH - 40f)
                                        return@pointerInteropFilter true
                                    }

                                    // Reading guide drag: move the line to finger position
                                    if (isDraggingReadingGuide) {
                                        readingGuideY = rawDocY.coerceIn(10f, pageH - 10f)
                                        return@pointerInteropFilter true
                                    }

                                    if (activeImageHandle != ImageHandleType.NONE && selectedImage != null && initialImageSnapshot != null && imageDragStartPoint != null) {
                                        val snap = initialImageSnapshot!!
                                        val dx = rawDocX - imageDragStartPoint!!.first
                                        val dy = rawDocY - imageDragStartPoint!!.second
                                        val aspect = if (snap.height > 0f) snap.width / snap.height else 1f

                                        val updatedImg = when (activeImageHandle) {
                                            ImageHandleType.MOVE -> snap.copy(x = snap.x + dx, y = snap.y + dy)
                                            ImageHandleType.BOTTOM_RIGHT -> {
                                                val newW = (snap.width + dx).coerceAtLeast(48f)
                                                val newH = if (snap.aspectRatioLocked) (newW / aspect).coerceAtLeast(48f) else (snap.height + dy).coerceAtLeast(48f)
                                                snap.copy(width = newW, height = newH)
                                            }
                                            ImageHandleType.TOP_LEFT -> {
                                                val newW = (snap.width - dx).coerceAtLeast(48f)
                                                val newH = if (snap.aspectRatioLocked) (newW / aspect).coerceAtLeast(48f) else (snap.height - dy).coerceAtLeast(48f)
                                                snap.copy(
                                                    x = snap.x + (snap.width - newW),
                                                    y = snap.y + (snap.height - newH),
                                                    width = newW,
                                                    height = newH
                                                )
                                            }
                                            ImageHandleType.TOP_RIGHT -> {
                                                val newW = (snap.width + dx).coerceAtLeast(48f)
                                                val newH = if (snap.aspectRatioLocked) (newW / aspect).coerceAtLeast(48f) else (snap.height - dy).coerceAtLeast(48f)
                                                snap.copy(
                                                    y = snap.y + (snap.height - newH),
                                                    width = newW,
                                                    height = newH
                                                )
                                            }
                                            ImageHandleType.BOTTOM_LEFT -> {
                                                val newW = (snap.width - dx).coerceAtLeast(48f)
                                                val newH = if (snap.aspectRatioLocked) (newW / aspect).coerceAtLeast(48f) else (snap.height + dy).coerceAtLeast(48f)
                                                snap.copy(
                                                    x = snap.x + (snap.width - newW),
                                                    width = newW,
                                                    height = newH
                                                )
                                            }
                                            ImageHandleType.ROTATE -> {
                                                val cx = snap.x + snap.width / 2f
                                                val cy = snap.y + snap.height / 2f
                                                val angleRad = atan2(rawDocY - cy, rawDocX - cx)
                                                val deg = ((Math.toDegrees(angleRad.toDouble()).toFloat() + 90f) + 360f) % 360f
                                                snap.copy(rotationDegrees = deg)
                                            }
                                            ImageHandleType.NONE -> snap
                                        }
                                        pageImages = pageImages.map { if (it.id == snap.id) updatedImg else it }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingText && selectedText != null && textDragStartPoint != null && initialTextPos != null) {
                                        val dx = rawDocX - textDragStartPoint!!.first
                                        val dy = rawDocY - textDragStartPoint!!.second
                                        val newX = (initialTextPos!!.first + dx).coerceIn(8f, pageW - 60f)
                                        val newY = (initialTextPos!!.second + dy).coerceIn(8f, pageH - 24f)
                                        pageTexts = pageTexts.map {
                                            if (it.id == selectedText.id) it.copy(x = newX, y = newY) else it
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingLassoSelection && lassoDragLastPoint != null) {
                                        val dx = rawDocX - lassoDragLastPoint!!.first
                                        val dy = rawDocY - lassoDragLastPoint!!.second
                                        if (hypot(dx, dy) > 1.5f) {
                                            currentStrokes = inkEngine.moveSelectedStrokes(dx, dy, currentStrokes)
                                            lassoDragLastPoint = Pair(rawDocX, rawDocY)
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    val (docX, docY) = snapPointToRulerIfNear(rawDocX, rawDocY)

                                    when (effectiveTool) {
                                        ToolType.ERASER_STROKE -> {
                                            val (remaining, _) = inkEngine.eraseAt(docX, docY, 20f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                        }
                                        ToolType.ERASER_PIXEL -> {
                                            val remaining = inkEngine.erasePartialAt(docX, docY, 16f / totalScale, currentStrokes)
                                            currentStrokes = remaining
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                lassoPoints = lassoPoints + Pair(rawDocX, rawDocY)
                                            }
                                        }
                                        ToolType.SHAPE -> {
                                            shapeCurrentPoint = Pair(rawDocX, rawDocY)
                                        }
                                        ToolType.PAN -> {
                                            imageDragStartPoint?.let { start ->
                                                val dx = touchX - start.first
                                                val dy = touchY - start.second
                                                panOffset += Offset(dx, dy)
                                                imageDragStartPoint = Pair(touchX, touchY)
                                            }
                                        }
                                        else -> {
                                            inkEngine.continueStroke(docX, docY, pressure)
                                            activeStrokeState = inkEngine.activeStroke
                                        }
                                    }
                                    true
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    if (isDraggingReadingGuide) {
                                        isDraggingReadingGuide = false
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingRuler) {
                                        isDraggingRuler = false
                                        rulerDragStart = null
                                        return@pointerInteropFilter true
                                    }

                                    if (activeImageHandle != ImageHandleType.NONE) {
                                        activeImageHandle = ImageHandleType.NONE
                                        imageDragStartPoint = null
                                        initialImageSnapshot = null
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageImages(it, pageImages) }
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingText) {
                                        isDraggingText = false
                                        textDragStartPoint = null
                                        initialTextPos = null
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageTexts(it, pageTexts) }
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingLassoSelection) {
                                        isDraggingLassoSelection = false
                                        lassoDragLastPoint = null
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageStrokes(it, currentStrokes) }
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    when (effectiveTool) {
                                        ToolType.ERASER_STROKE, ToolType.ERASER_PIXEL -> {
                                            coroutineScope.launch {
                                                currentPage?.id?.let { repository.savePageStrokes(it, currentStrokes) }
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                selectedStrokes = inkEngine.selectWithLasso(lassoPoints, currentStrokes)
                                                isLassoActive = false
                                            }
                                        }
                                        ToolType.SHAPE -> {
                                            shapeStartPoint?.let { start ->
                                                if (hypot(rawDocX - start.first, rawDocY - start.second) > 6f) {
                                                    val createdStroke = ShapeFactory.createPresetShape(
                                                        type = selectedPresetShape,
                                                        startX = start.first,
                                                        startY = start.second,
                                                        endX = rawDocX,
                                                        endY = rawDocY,
                                                        color = activeColorState,
                                                        width = activeWidthState,
                                                        opacity = inkEngine.currentOpacity
                                                    )
                                                    inkEngine.recordAddedStroke(createdStroke)
                                                    val updated = currentStrokes + createdStroke
                                                    currentStrokes = updated
                                                    coroutineScope.launch {
                                                        currentPage?.id?.let { repository.savePageStrokes(it, updated) }
                                                    }
                                                }
                                            }
                                            shapeStartPoint = null
                                            shapeCurrentPoint = null
                                        }
                                        ToolType.PAN, ToolType.TEXT -> {
                                            imageDragStartPoint = null
                                        }
                                        else -> {
                                            var completed = inkEngine.finishStroke()
                                            activeStrokeState = null

                                            if (completed != null) {
                                                if (isShapeSnapEnabled) {
                                                    val recognized = ShapeRecognizer.recognize(completed)
                                                    if (recognized.type != RecognizedShapeType.NONE) {
                                                        completed = recognized.snappedStroke
                                                        inkEngine.replaceLastAddedStroke(completed)
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
                        // 1. Page Canvas Surface
                        drawRect(
                            color = Color.White,
                            topLeft = Offset.Zero,
                            size = Size(pageW, pageH)
                        )
                        drawRect(
                            color = Color(0xFFCBD5E1),
                            topLeft = Offset.Zero,
                            size = Size(pageW, pageH),
                            style = Stroke(width = 1f)
                        )

                        // 2. PDF Background or Paper Template
                        pdfBitmap?.let { bmp ->
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(pageW.toInt(), pageH.toInt())
                            )
                        } ?: run {
                            currentPage?.template?.let { tmpl ->
                                TemplateRenderer.drawTemplate(this, tmpl, pageW, pageH)
                            }
                        }

                        // 3. Draw Inserted Images (sorted by zIndex, with non-destructive crop)
                        for (img in pageImages.sortedBy { it.zIndex }) {
                            val bmp = imageBitmaps[img.filePath]
                            if (bmp != null) {
                                drawContext.canvas.save()
                                val cx = img.x + img.width / 2f
                                val cy = img.y + img.height / 2f
                                drawContext.canvas.translate(cx, cy)
                                if (img.rotationDegrees != 0f) {
                                    drawContext.canvas.rotate(img.rotationDegrees)
                                }
                                val sx = if (img.isFlippedHorizontal) -1f else 1f
                                val sy = if (img.isFlippedVertical) -1f else 1f
                                if (sx != 1f || sy != 1f) {
                                    drawContext.canvas.scale(sx, sy)
                                }
                                drawContext.canvas.translate(-img.width / 2f, -img.height / 2f)

                                val cLeft = (img.cropLeft.coerceIn(0f, 0.95f) * bmp.width).roundToInt()
                                val cTop = (img.cropTop.coerceIn(0f, 0.95f) * bmp.height).roundToInt()
                                val cRight = (img.cropRight.coerceIn(cLeft / bmp.width.toFloat() + 0.05f, 1f) * bmp.width).roundToInt()
                                val cBottom = (img.cropBottom.coerceIn(cTop / bmp.height.toFloat() + 0.05f, 1f) * bmp.height).roundToInt()

                                drawImage(
                                    image = bmp.asImageBitmap(),
                                    srcOffset = IntOffset(cLeft, cTop),
                                    srcSize = IntSize((cRight - cLeft).coerceAtLeast(1), (cBottom - cTop).coerceAtLeast(1)),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(img.width.toInt().coerceAtLeast(1), img.height.toInt().coerceAtLeast(1)),
                                    alpha = img.opacity
                                )
                                drawContext.canvas.restore()
                            }
                        }

                        // 4. Draw Selected Image Bounding Box & Handles
                        selectedImage?.let { img ->
                            drawRect(
                                color = Color(0xFF2563EB),
                                topLeft = Offset(img.x, img.y),
                                size = Size(img.width, img.height),
                                style = Stroke(
                                    width = 2f / totalScale,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / totalScale, 8f / totalScale), 0f)
                                )
                            )

                            val handleR = 6.5f / totalScale
                            val corners = listOf(
                                Offset(img.x, img.y),
                                Offset(img.x + img.width, img.y),
                                Offset(img.x, img.y + img.height),
                                Offset(img.x + img.width, img.y + img.height)
                            )
                            for (c in corners) {
                                drawCircle(color = Color.White, radius = handleR, center = c)
                                drawCircle(color = Color(0xFF2563EB), radius = handleR, center = c, style = Stroke(width = 2f / totalScale))
                            }

                            val topMid = Offset(img.x + img.width / 2f, img.y)
                            val rotKnob = Offset(img.x + img.width / 2f, img.y - 22f / totalScale)
                            drawLine(color = Color(0xFF2563EB), start = topMid, end = rotKnob, strokeWidth = 1.5f / totalScale)
                            drawCircle(color = Color(0xFF2563EB), radius = handleR, center = rotKnob)
                        }

                        // 5. Draw Placed Page Text Objects (Background Card + Real Multiline Text + Selection Frame)
                        for (txt in pageTexts.sortedBy { it.zIndex }) {
                            val lines = txt.text.split("\n")
                            val lineHeight = txt.fontSizeSp * 1.35f
                            val maxLineLen = (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(4)
                            val boxW = (maxLineLen * txt.fontSizeSp * 0.6f + 20f).coerceAtLeast(64f)
                            val boxH = lines.size * lineHeight + 12f

                            txt.backgroundColor?.let { bgCol ->
                                drawRoundRect(
                                    color = Color(bgCol.toInt()),
                                    topLeft = Offset(txt.x - 6f, txt.y - 4f),
                                    size = Size(boxW, boxH),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }

                            val style = when {
                                txt.isBold && txt.isItalic -> Typeface.BOLD_ITALIC
                                txt.isBold -> Typeface.BOLD
                                txt.isItalic -> Typeface.ITALIC
                                else -> Typeface.NORMAL
                            }
                            val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                color = txt.color.toInt()
                                textSize = txt.fontSizeSp
                                typeface = Typeface.create(Typeface.DEFAULT, style)
                            }
                            for ((idx, line) in lines.withIndex()) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    line,
                                    txt.x,
                                    txt.y + txt.fontSizeSp + idx * lineHeight,
                                    textPaint
                                )
                            }

                            if (txt.id == selectedTextId) {
                                drawRoundRect(
                                    color = Color(0xFF2563EB),
                                    topLeft = Offset(txt.x - 8f, txt.y - 6f),
                                    size = Size(boxW + 4f, boxH + 4f),
                                    cornerRadius = CornerRadius(6f, 6f),
                                    style = Stroke(
                                        width = 1.8f / totalScale,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f / totalScale, 6f / totalScale), 0f)
                                    )
                                )
                            }
                        }

                        // 6. Draw Committed Vector Strokes (with Quadratic Smoothing & Tool-Specific Brush Rendering)
                        for (stroke in currentStrokes) {
                            if (stroke.points.size < 2) continue
                            val isSelected = selectedStrokes.contains(stroke.id)
                            val isAudioActive = isAudioPlaying && stroke.audioTimestampMs != null &&
                                    abs(stroke.audioTimestampMs - audioPlayPositionMs) < 1500

                            drawStyledInkStroke(
                                stroke = stroke,
                                isSelected = isSelected,
                                isAudioActive = isAudioActive
                            )
                        }

                        // 7. Draw Active Live Inking Stroke
                        activeStrokeState?.let { active ->
                            if (active.points.size >= 2) {
                                drawStyledInkStroke(
                                    stroke = active,
                                    isSelected = false,
                                    isAudioActive = false
                                )
                            }
                        }

                        // 8. Draw Live Preset Shape Preview
                        if (shapeStartPoint != null && shapeCurrentPoint != null) {
                            val (sX, sY) = shapeStartPoint!!
                            val (cX, cY) = shapeCurrentPoint!!
                            val previewStroke = ShapeFactory.createPresetShape(
                                type = selectedPresetShape,
                                startX = sX,
                                startY = sY,
                                endX = cX,
                                endY = cY,
                                color = activeColorState,
                                width = activeWidthState
                            )
                            if (previewStroke.points.size >= 2) {
                                val previewPath = Path()
                                previewPath.moveTo(previewStroke.points[0].x, previewStroke.points[0].y)
                                for (i in 1 until previewStroke.points.size) {
                                    previewPath.lineTo(previewStroke.points[i].x, previewStroke.points[i].y)
                                }
                                drawPath(
                                    path = previewPath,
                                    color = Color(activeColorState.toInt()).copy(alpha = 0.85f),
                                    style = Stroke(
                                        width = activeWidthState,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // 9. Draw Lasso Selection Polygon & Selected Strokes Bounding Box
                        if (isLassoActive && lassoPoints.size >= 2) {
                            val lassoPath = Path()
                            lassoPath.moveTo(lassoPoints[0].first, lassoPoints[0].second)
                            for (i in 1 until lassoPoints.size) {
                                lassoPath.lineTo(lassoPoints[i].first, lassoPoints[i].second)
                            }
                            drawPath(
                                path = lassoPath,
                                color = Color(0xFF2563EB),
                                style = Stroke(
                                    width = 1.5f / totalScale,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / totalScale, 10f / totalScale), 0f)
                                )
                            )
                        }

                        if (selectedStrokes.isNotEmpty()) {
                            inkEngine.getSelectionBounds(currentStrokes)?.let { b ->
                                drawRect(
                                    color = Color(0xFF2563EB),
                                    topLeft = Offset(b.left - 6f, b.top - 6f),
                                    size = Size((b.right - b.left) + 12f, (b.bottom - b.top) + 12f),
                                    style = Stroke(
                                        width = 1.5f / totalScale,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f / totalScale, 6f / totalScale), 0f)
                                    )
                                )
                            }
                        }

                        // 10. Draw Interactive Digital Ruler Overlay
                        if (showRuler) {
                            drawContext.canvas.save()
                            drawContext.canvas.translate(rulerX, rulerY)
                            drawContext.canvas.rotate(rulerAngleDeg)

                            val rLen = 440f
                            val rHalf = rLen / 2f
                            val rThick = 44f
                            val rHalfThick = rThick / 2f

                            drawRoundRect(
                                color = Color(0xCC1E293B),
                                topLeft = Offset(-rHalf, -rHalfThick),
                                size = Size(rLen, rThick),
                                cornerRadius = CornerRadius(6f, 6f)
                            )
                            drawRoundRect(
                                color = Color(0xFF38BDF8),
                                topLeft = Offset(-rHalf, -rHalfThick),
                                size = Size(rLen, rThick),
                                cornerRadius = CornerRadius(6f, 6f),
                                style = Stroke(width = 1.5f)
                            )

                            // Graduated tick marks along top and bottom edges
                            var tx = -200f
                            while (tx <= 200f) {
                                val isMajor = (tx.toInt() % 50 == 0)
                                val tickH = if (isMajor) 10f else 5f
                                drawLine(
                                    color = if (isMajor) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
                                    start = Offset(tx, -rHalfThick),
                                    end = Offset(tx, -rHalfThick + tickH),
                                    strokeWidth = if (isMajor) 1.5f else 1f
                                )
                                drawLine(
                                    color = if (isMajor) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
                                    start = Offset(tx, rHalfThick),
                                    end = Offset(tx, rHalfThick - tickH),
                                    strokeWidth = if (isMajor) 1.5f else 1f
                                )
                                tx += 10f
                            }

                            val anglePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                textSize = 11f
                                textAlign = AndroidPaint.Align.CENTER
                                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                "RULER · ${rulerAngleDeg.roundToInt()}° (Draw along edge to snap)",
                                0f,
                                4f,
                                anglePaint
                            )
                            drawContext.canvas.restore()
                        }

                        // 11. Draw PDF Reading Guide Overlay (Red Border with White Center, interactive toggle/drag)
                        if (showReadingGuide) {
                            val rY = readingGuideY.coerceIn(4f, pageH - 4f)
                            val guideHeight = 8f / totalScale

                            // Outer red border (top and bottom)
                            drawRect(
                                color = Color(0xFFDC2626).copy(alpha = 0.95f),
                                topLeft = Offset(0f, rY - guideHeight / 2f),
                                size = Size(pageW, guideHeight)
                            )
                            // White inner stripe
                            drawRect(
                                color = Color.White.copy(alpha = 0.90f),
                                topLeft = Offset(0f, rY - guideHeight / 4f),
                                size = Size(pageW, guideHeight / 2f)
                            )

                            // Reading Guide Handle indicator on the left & right margins
                            drawCircle(
                                color = Color(0xFFDC2626),
                                radius = 7f / totalScale,
                                center = Offset(14f / totalScale, rY)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.5f / totalScale,
                                center = Offset(14f / totalScale, rY)
                            )
                            drawCircle(
                                color = Color(0xFFDC2626),
                                radius = 7f / totalScale,
                                center = Offset(pageW - 14f / totalScale, rY)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.5f / totalScale,
                                center = Offset(pageW - 14f / totalScale, rY)
                            )
                        }
                    }
                }

                // --- FLOATING RULER ANGLE BAR (when Ruler is active) ---
                if (showRuler) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 16.dp)
                            .shadow(6.dp, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Ruler ${rulerAngleDeg.roundToInt()}°", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            listOf(0f, 30f, 45f, 60f, 90f).forEach { ang ->
                                FilterChip(
                                    selected = abs(rulerAngleDeg - ang) < 1f,
                                    onClick = { rulerAngleDeg = ang },
                                    label = { Text("${ang.toInt()}°", fontSize = 11.sp) }
                                )
                            }
                            IconButton(onClick = { showRuler = false }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close Ruler", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // --- FLOATING READING GUIDE CONTROL BAR (when Reading Guide is active) ---
                if (showReadingGuide) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 16.dp, top = 16.dp)
                            .shadow(6.dp, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626))
                            )
                            Text("Reading Guide", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                            IconButton(
                                onClick = { readingGuideY = (readingGuideY - 26f).coerceAtLeast(10f) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Prev Line", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { readingGuideY = (readingGuideY + 26f).coerceAtMost(pageH - 10f) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Line", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { showReadingGuide = false },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Reading Guide", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // --- FLOATING IMAGE CONTEXTUAL TOOLBAR ---
                selectedImage?.let { img ->
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Image (${img.width.toInt()}×${img.height.toInt()})",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )

                            // Crop & Adjust Dialog
                            FilledTonalButton(
                                onClick = { showImageCropDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Crop, contentDescription = "Crop", modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Crop & Opacity", fontSize = 11.sp)
                            }

                            // Rotate 90 deg clockwise
                            IconButton(onClick = {
                                val updated = pageImages.map {
                                    if (it.id == img.id) it.copy(rotationDegrees = (it.rotationDegrees + 90f) % 360f) else it
                                }
                                pageImages = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90°", modifier = Modifier.size(18.dp))
                            }

                            // Flip Horizontal
                            IconButton(onClick = {
                                val updated = pageImages.map {
                                    if (it.id == img.id) it.copy(isFlippedHorizontal = !it.isFlippedHorizontal) else it
                                }
                                pageImages = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Flip, contentDescription = "Flip Horizontal", modifier = Modifier.size(18.dp))
                            }

                            // Flip Vertical
                            IconButton(onClick = {
                                val updated = pageImages.map {
                                    if (it.id == img.id) it.copy(isFlippedVertical = !it.isFlippedVertical) else it
                                }
                                pageImages = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Flip Vertical", modifier = Modifier.size(18.dp))
                            }

                            // Bring to Front
                            IconButton(onClick = {
                                val maxZ = (pageImages.maxOfOrNull { it.zIndex } ?: 0) + 1
                                val updated = pageImages.map {
                                    if (it.id == img.id) it.copy(zIndex = maxZ) else it
                                }
                                pageImages = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.FlipToFront, contentDescription = "Bring to Front", modifier = Modifier.size(18.dp))
                            }

                            // Lock / Unlock
                            IconButton(onClick = {
                                val updated = pageImages.map {
                                    if (it.id == img.id) it.copy(isLocked = !it.isLocked) else it
                                }
                                pageImages = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    imageVector = if (img.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock",
                                    tint = if (img.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Duplicate
                            IconButton(onClick = {
                                val copy = img.copy(
                                    id = UUID.randomUUID().toString(),
                                    x = (img.x + 24f).coerceAtMost(pageW - img.width),
                                    y = (img.y + 24f).coerceAtMost(pageH - img.height),
                                    zIndex = (pageImages.maxOfOrNull { it.zIndex } ?: 0) + 1
                                )
                                val updated = pageImages + copy
                                pageImages = updated
                                selectedImageId = copy.id
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(18.dp))
                            }

                            // Delete Image
                            IconButton(onClick = {
                                val updated = pageImages.filter { it.id != img.id }
                                pageImages = updated
                                selectedImageId = null
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }

                            IconButton(onClick = { selectedImageId = null }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // --- FLOATING TEXT OBJECT CONTEXTUAL TOOLBAR ---
                selectedText?.let { txt ->
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Text Note", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)

                            // Edit Text Content
                            FilledTonalButton(
                                onClick = {
                                    editingTextObject = txt
                                    insertedTextContent = txt.text
                                    showInsertTextDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Text", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit", fontSize = 11.sp)
                            }

                            // Font Size - / +
                            IconButton(onClick = {
                                val updated = pageTexts.map {
                                    if (it.id == txt.id) it.copy(fontSizeSp = (it.fontSizeSp - 2f).coerceAtLeast(10f)) else it
                                }
                                pageTexts = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Remove, contentDescription = "Smaller Font", modifier = Modifier.size(16.dp))
                            }
                            Text("${txt.fontSizeSp.toInt()}sp", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = {
                                val updated = pageTexts.map {
                                    if (it.id == txt.id) it.copy(fontSizeSp = (it.fontSizeSp + 2f).coerceAtMost(48f)) else it
                                }
                                pageTexts = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Larger Font", modifier = Modifier.size(16.dp))
                            }

                            // Bold Toggle
                            FilterChip(
                                selected = txt.isBold,
                                onClick = {
                                    val updated = pageTexts.map { if (it.id == txt.id) it.copy(isBold = !it.isBold) else it }
                                    pageTexts = updated
                                    coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                                },
                                label = { Text("B", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                            )

                            // Italic Toggle
                            FilterChip(
                                selected = txt.isItalic,
                                onClick = {
                                    val updated = pageTexts.map { if (it.id == txt.id) it.copy(isItalic = !it.isItalic) else it }
                                    pageTexts = updated
                                    coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                                },
                                label = { Text("I", fontStyle = FontStyle.Italic, fontSize = 11.sp) }
                            )

                            // Background Note Color Cycle
                            IconButton(onClick = {
                                val bgCycle = listOf<Long?>(0x80FEF08A, 0x80BAE6FD, 0x80BBF7D0, 0x80FBCFE8, null)
                                val nextIdx = (bgCycle.indexOf(txt.backgroundColor) + 1) % bgCycle.size
                                val updated = pageTexts.map { if (it.id == txt.id) it.copy(backgroundColor = bgCycle[nextIdx]) else it }
                                pageTexts = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.FormatColorFill, contentDescription = "Note Background", modifier = Modifier.size(16.dp))
                            }

                            // Delete Text
                            IconButton(onClick = {
                                val updated = pageTexts.filter { it.id != txt.id }
                                pageTexts = updated
                                selectedTextId = null
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageTexts(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Text", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }

                            IconButton(onClick = { selectedTextId = null }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // --- LASSO SELECTION ACTION BAR ---
                if (selectedStrokes.isNotEmpty() && selectedImage == null && selectedText == null) {
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${selectedStrokes.size} selected (drag box to move)",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )

                            // Scale Down
                            IconButton(onClick = {
                                val updated = inkEngine.scaleSelectedStrokes(0.85f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ZoomOutMap, contentDescription = "Shrink Selection", modifier = Modifier.size(16.dp))
                            }

                            // Scale Up
                            IconButton(onClick = {
                                val updated = inkEngine.scaleSelectedStrokes(1.18f, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.OpenInFull, contentDescription = "Enlarge Selection", modifier = Modifier.size(16.dp))
                            }

                            // Recolor to active color
                            IconButton(onClick = {
                                val updated = inkEngine.recolorSelectedStrokes(activeColorState, currentStrokes)
                                currentStrokes = updated
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Palette, contentDescription = "Recolor Selection", tint = Color(activeColorState.toInt()), modifier = Modifier.size(18.dp))
                            }

                            // Duplicate Selection
                            IconButton(onClick = {
                                val updated = inkEngine.duplicateSelectedStrokes(24f, 24f, currentStrokes)
                                currentStrokes = updated
                                selectedStrokes = inkEngine.selectedStrokeIds.toSet()
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Selection", modifier = Modifier.size(16.dp))
                            }

                            // Delete Selection
                            IconButton(onClick = {
                                val updated = inkEngine.deleteSelectedStrokes(currentStrokes)
                                currentStrokes = updated
                                selectedStrokes = emptySet()
                                coroutineScope.launch { currentPage?.id?.let { repository.savePageStrokes(it, updated) } }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Selection", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }

                            IconButton(onClick = {
                                inkEngine.clearSelection()
                                selectedStrokes = emptySet()
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Floating Viewport Zoom Controls (Bottom Right)
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 92.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { zoomScale = (zoomScale / 1.2f).coerceIn(0.4f, 5.0f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(16.dp))
                        }

                        Text(
                            text = "${(zoomScale * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    zoomScale = 1.0f
                                    panOffset = Offset.Zero
                                }
                                .padding(horizontal = 4.dp)
                        )

                        IconButton(
                            onClick = { zoomScale = (zoomScale * 1.2f).coerceIn(0.4f, 5.0f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(16.dp))
                        }

                        VerticalDivider(modifier = Modifier.height(18.dp))

                        IconButton(
                            onClick = {
                                zoomScale = 1.0f
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.FitScreen, contentDescription = "Fit Page", modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            onClick = {
                                val targetScale = (availableW - margin * 2) / (pageW * fitScale)
                                zoomScale = targetScale.coerceIn(0.4f, 5.0f)
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "Fit Width", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Preset Shape Dropdown Menu
                if (showPresetShapeMenu) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 88.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 12.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PresetShapeType.entries.forEach { shape ->
                                    FilterChip(
                                        selected = selectedPresetShape == shape,
                                        onClick = {
                                            selectedPresetShape = shape
                                            selectTool(ToolType.SHAPE)
                                            showPresetShapeMenu = false
                                        },
                                        label = { Text(shape.displayName, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Inking Toolbar Dock
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .shadow(16.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Inking Tools: Fountain Pen, Ballpoint, Pencil, Highlighter, Stroke Eraser, Pixel Eraser
                        listOf(
                            Triple(ToolType.PEN, Icons.Default.Edit, "Fountain Pen"),
                            Triple(ToolType.BALLPOINT, Icons.Default.DriveFileRenameOutline, "Ballpoint"),
                            Triple(ToolType.PENCIL, Icons.Default.Create, "Pencil"),
                            Triple(ToolType.HIGHLIGHTER, Icons.Default.BorderColor, "Highlighter"),
                            Triple(ToolType.ERASER_STROKE, Icons.Default.AutoFixNormal, "Stroke Eraser"),
                            Triple(ToolType.ERASER_PIXEL, Icons.Default.CleaningServices, "Pixel Eraser")
                        ).forEach { (tool, icon, label) ->
                            val isSelected = activeToolState == tool
                            IconButton(
                                onClick = {
                                    if (isSelected && (tool == ToolType.PEN || tool == ToolType.BALLPOINT || tool == ToolType.HIGHLIGHTER || tool == ToolType.PENCIL)) {
                                        showToolPropertiesDialog = true
                                    } else {
                                        selectTool(tool)
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

                        // Preset Shape Tool
                        val isShapeActive = activeToolState == ToolType.SHAPE
                        IconButton(
                            onClick = {
                                if (isShapeActive) {
                                    showPresetShapeMenu = !showPresetShapeMenu
                                } else {
                                    selectTool(ToolType.SHAPE)
                                    showPresetShapeMenu = true
                                }
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isShapeActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Category,
                                contentDescription = "Shapes",
                                tint = if (isShapeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Lasso Tool
                        val isLassoSelected = activeToolState == ToolType.LASSO
                        IconButton(
                            onClick = { selectTool(ToolType.LASSO) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isLassoSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gesture,
                                contentDescription = "Lasso Select",
                                tint = if (isLassoSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Insert Image Button with clear label
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { imagePickerLauncher.launch("image/*") }
                                .padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Insert Image",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Add Image", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // Insert Text Button
                        val isTextSelected = activeToolState == ToolType.TEXT
                        IconButton(
                            onClick = { selectTool(ToolType.TEXT) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isTextSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextFields,
                                contentDescription = "Text Note",
                                tint = if (isTextSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Pan / Select Object Tool
                        val isPanSelected = activeToolState == ToolType.PAN
                        IconButton(
                            onClick = { selectTool(ToolType.PAN) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isPanSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PanTool,
                                contentDescription = "Pan & Select Object",
                                tint = if (isPanSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        VerticalDivider(modifier = Modifier.height(26.dp))

                        // Color Palette Swatches (first 5 colors)
                        palette.take(5).forEach { c ->
                            val isColorSelected = activeColorState == c
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(c.toInt()))
                                    .border(
                                        width = if (isColorSelected) 3.dp else 1.dp,
                                        color = if (isColorSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectColor(c) }
                            )
                        }

                        IconButton(
                            onClick = { showCustomColorDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = "Custom Color", modifier = Modifier.size(16.dp))
                        }

                        VerticalDivider(modifier = Modifier.height(26.dp))

                        IconButton(
                            onClick = { showToolPropertiesDialog = true },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Tool Settings & Presets", modifier = Modifier.size(18.dp))
                        }

                        VerticalDivider(modifier = Modifier.height(26.dp))

                        // Undo & Redo Controls
                        IconButton(
                            enabled = inkEngine.canUndo,
                            onClick = {
                                val undone = inkEngine.undo(currentStrokes)
                                currentStrokes = undone
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageStrokes(it, undone) }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            enabled = inkEngine.canRedo,
                            onClick = {
                                val redone = inkEngine.redo(currentStrokes)
                                currentStrokes = redone
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageStrokes(it, redone) }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // --- IMAGE CROP & ADJUSTMENTS DIALOG ---
        if (showImageCropDialog && selectedImage != null) {
            val img = selectedImage
            var cLeft by remember(img) { mutableFloatStateOf(img.cropLeft) }
            var cTop by remember(img) { mutableFloatStateOf(img.cropTop) }
            var cRight by remember(img) { mutableFloatStateOf(img.cropRight) }
            var cBottom by remember(img) { mutableFloatStateOf(img.cropBottom) }
            var imgOpacity by remember(img) { mutableFloatStateOf(img.opacity) }
            var lockAspect by remember(img) { mutableStateOf(img.aspectRatioLocked) }

            AlertDialog(
                onDismissRequest = { showImageCropDialog = false },
                title = { Text("Crop & Adjust Image") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Quick Crop Presets:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { cLeft = 0f; cTop = 0f; cRight = 1f; cBottom = 1f }) {
                                Text("Full", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { cLeft = 0.1f; cTop = 0.1f; cRight = 0.9f; cBottom = 0.9f }) {
                                Text("Center 80%", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { cLeft = 0f; cTop = 0.15f; cRight = 1f; cBottom = 0.85f }) {
                                Text("16:9 Wide", fontSize = 11.sp)
                            }
                        }

                        Text("Horizontal Crop (${(cLeft * 100).toInt()}% – ${(cRight * 100).toInt()}%)", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = cLeft,
                                onValueChange = { cLeft = it.coerceAtMost(cRight - 0.1f) },
                                valueRange = 0f..0.85f,
                                modifier = Modifier.weight(1f)
                            )
                            Slider(
                                value = cRight,
                                onValueChange = { cRight = it.coerceAtLeast(cLeft + 0.1f) },
                                valueRange = 0.15f..1f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text("Vertical Crop (${(cTop * 100).toInt()}% – ${(cBottom * 100).toInt()}%)", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = cTop,
                                onValueChange = { cTop = it.coerceAtMost(cBottom - 0.1f) },
                                valueRange = 0f..0.85f,
                                modifier = Modifier.weight(1f)
                            )
                            Slider(
                                value = cBottom,
                                onValueChange = { cBottom = it.coerceAtLeast(cTop + 0.1f) },
                                valueRange = 0.15f..1f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider()

                        Text("Opacity: ${(imgOpacity * 100).toInt()}%", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Slider(
                            value = imgOpacity,
                            onValueChange = { imgOpacity = it },
                            valueRange = 0.15f..1.0f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Lock Aspect Ratio when Resizing", fontSize = 13.sp)
                            Switch(checked = lockAspect, onCheckedChange = { lockAspect = it })
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val updated = pageImages.map {
                            if (it.id == img.id) {
                                it.copy(
                                    cropLeft = cLeft,
                                    cropTop = cTop,
                                    cropRight = cRight,
                                    cropBottom = cBottom,
                                    opacity = imgOpacity,
                                    aspectRatioLocked = lockAspect
                                )
                            } else it
                        }
                        pageImages = updated
                        // PART 2 FIX: Restore selectedImageId so user can re-edit the image immediately after cropping
                        selectedImageId = img.id
                        coroutineScope.launch { currentPage?.id?.let { repository.savePageImages(it, updated) } }
                        showImageCropDialog = false
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImageCropDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- EXPORT PDF DIALOG ---
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Annotated PDF") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Export \"${document?.title ?: "Note"}\" (${pages.size} pages) with all vector ink annotations, shapes, images, and text notes.",
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                showExportDialog = false
                                val safeName = (document?.title ?: "InkFlow_Note").replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                                savePdfLauncher.launch("${safeName}.pdf")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save PDF to Device Storage…")
                        }

                        OutlinedButton(
                            onClick = {
                                showExportDialog = false
                                coroutineScope.launch {
                                    val exportFile = File(context.cacheDir, "${document?.title ?: "Note"}_Export.pdf")
                                    val success = PdfExporter().exportDocumentToPdf(
                                        document = document ?: return@launch,
                                        pages = pages,
                                        strokeProvider = { pageId -> repository.loadPageStrokes(pageId) },
                                        imageProvider = { pageId -> repository.loadPageImages(pageId) },
                                        textProvider = { pageId -> repository.loadPageTexts(pageId) },
                                        outputFile = exportFile
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Toast.makeText(context, "Exported PDF (${exportFile.length() / 1024} KB): ${exportFile.name}", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Quick Export to App Cache")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Close")
                    }
                }
            )
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

                            Button(
                                onClick = {
                                    insertPageTargetIndex = currentPageIndex
                                    insertPagePositionMode = "AFTER"
                                    insertPageSelectedTemplate = PageTemplate.RULED
                                    showInsertPageDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Insert Page")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Template: ", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterVertically))
                        PageTemplate.entries.forEach { tmpl ->
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
                                            text = p.template.name.take(7),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

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

                                    // Quick Insert After Button on Page Thumbnail
                                    IconButton(
                                        onClick = {
                                            insertPageTargetIndex = p.index
                                            insertPagePositionMode = "AFTER"
                                            insertPageSelectedTemplate = p.template
                                            showInsertPageDialog = true
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AddCircleOutline,
                                            contentDescription = "Insert Page After",
                                            tint = MaterialTheme.colorScheme.primary,
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

        // --- INSERT PAGE DIALOG (Mid-document Blank, Ruled, Dotted, Grid, Cornell, Checklist, Divider) ---
        if (showInsertPageDialog) {
            AlertDialog(
                onDismissRequest = { showInsertPageDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PostAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Insert Page in Document")
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("1. Insertion Position:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { insertPagePositionMode = "AFTER" }
                                    .background(if (insertPagePositionMode == "AFTER") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = insertPagePositionMode == "AFTER",
                                    onClick = { insertPagePositionMode = "AFTER" }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "After Page ${insertPageTargetIndex + 1} (Insert into position ${insertPageTargetIndex + 2})",
                                    fontSize = 13.sp,
                                    fontWeight = if (insertPagePositionMode == "AFTER") FontWeight.SemiBold else FontWeight.Normal
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { insertPagePositionMode = "BEFORE" }
                                    .background(if (insertPagePositionMode == "BEFORE") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = insertPagePositionMode == "BEFORE",
                                    onClick = { insertPagePositionMode = "BEFORE" }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Before Page ${insertPageTargetIndex + 1} (Insert into position ${insertPageTargetIndex + 1})",
                                    fontSize = 13.sp,
                                    fontWeight = if (insertPagePositionMode == "BEFORE") FontWeight.SemiBold else FontWeight.Normal
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { insertPagePositionMode = "END" }
                                    .background(if (insertPagePositionMode == "END") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = insertPagePositionMode == "END",
                                    onClick = { insertPagePositionMode = "END" }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "At Document End (Page ${pages.size + 1})",
                                    fontSize = 13.sp,
                                    fontWeight = if (insertPagePositionMode == "END") FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }

                        HorizontalDivider()

                        Text("2. Page Template & Style:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageTemplate.entries.forEach { tmpl ->
                                val isSelected = insertPageSelectedTemplate == tmpl
                                val (label, desc, icon) = when (tmpl) {
                                    PageTemplate.BLANK -> Triple("Blank White Page", "Plain clean sheet for drawing & diagrams", Icons.Default.CropPortrait)
                                    PageTemplate.RULED -> Triple("Ruled Lined", "Standard wide lines with red left margin", Icons.AutoMirrored.Filled.FormatAlignLeft)
                                    PageTemplate.COLLEGE_RULED -> Triple("College Ruled", "Narrow ruled lines for dense study notes", Icons.Default.DensitySmall)
                                    PageTemplate.DOTTED -> Triple("Dotted Grid", "Bullet journal 5mm dot grid", Icons.Default.Grain)
                                    PageTemplate.GRID -> Triple("Math / Graph Grid", "Cartesian graph grid for calculations", Icons.Default.Grid4x4)
                                    PageTemplate.CORNELL -> Triple("Cornell Method", "Cue column & bottom summary block", Icons.Default.VerticalSplit)
                                    PageTemplate.CHECKLIST -> Triple("Checklist / To-Do", "Ruled lines with interactive checkboxes", Icons.Default.Checklist)
                                }
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { insertPageSelectedTemplate = tmpl },
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val afterIdx = when (insertPagePositionMode) {
                                    "BEFORE" -> (insertPageTargetIndex - 1).coerceAtLeast(-1)
                                    "END" -> (pages.size - 1).coerceAtLeast(-1)
                                    else -> insertPageTargetIndex
                                }
                                val newPage = repository.insertPageAt(documentId, afterIdx, insertPageSelectedTemplate)
                                val newIndex = (afterIdx + 1).coerceIn(0, pages.size)
                                currentPageIndex = newIndex
                                showInsertPageDialog = false
                                showPageManagerSheet = false
                                Toast.makeText(context, "Page inserted at position ${newIndex + 1}!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Insert Page")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInsertPageDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- TOOL PROPERTIES & SAVED PEN PRESETS DIALOG ---
        if (showToolPropertiesDialog) {
            AlertDialog(
                onDismissRequest = { showToolPropertiesDialog = false },
                title = { Text("Pen Presets & Canvas Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Saved Pen Presets:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            penPresets.forEach { preset ->
                                OutlinedButton(
                                    onClick = {
                                        selectTool(preset.tool)
                                        selectColor(preset.color)
                                        selectWidth(preset.width)
                                        showToolPropertiesDialog = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(preset.color.toInt()))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("${preset.name} (${preset.width}pt)", fontSize = 11.sp)
                                }
                            }
                        }

                        TextButton(
                            onClick = {
                                val newPreset = PenPreset(
                                    id = UUID.randomUUID().toString(),
                                    name = "${activeToolState.name.lowercase().replaceFirstChar { it.uppercase() }} #${penPresets.size + 1}",
                                    tool = activeToolState,
                                    color = activeColorState,
                                    width = (activeWidthState * 10).roundToInt() / 10f
                                )
                                penPresets = penPresets + newPreset
                                Toast.makeText(context, "Saved current pen preset!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Current Pen as Preset", fontSize = 12.sp)
                        }

                        HorizontalDivider()

                        Text("Stroke Width: ${String.format(Locale.ROOT, "%.1f pt", activeWidthState)}", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = activeWidthState,
                                onValueChange = { selectWidth(it) },
                                valueRange = 0.5f..20f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(activeColorState.toInt())),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size((activeWidthState * 1.5f).dp.coerceIn(2.dp, 24.dp))
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }

                        HorizontalDivider()

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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Smart AutoShape Recognition", fontWeight = FontWeight.SemiBold)
                                Text("Automatically snap hand-drawn lines, circles, boxes, and triangles.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            selectColor(c)
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
                                selectColor(parsed)
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

        // --- INSERT OR EDIT TEXT NOTE DIALOG ---
        if (showInsertTextDialog) {
            AlertDialog(
                onDismissRequest = {
                    showInsertTextDialog = false
                    editingTextObject = null
                },
                title = { Text(if (editingTextObject != null) "Edit Typed Note" else "Insert Typed Note") },
                text = {
                    OutlinedTextField(
                        value = insertedTextContent,
                        onValueChange = { insertedTextContent = it },
                        label = { Text("Note Text (Multi-line supported)") },
                        placeholder = { Text("Type definition (e.g., Mitosis is cell division), formula, or summary…") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (insertedTextContent.isNotBlank()) {
                                showInsertTextDialog = false
                                val existing = editingTextObject
                                val updated = if (existing != null) {
                                    pageTexts.map { if (it.id == existing.id) it.copy(text = insertedTextContent.trim()) else it }
                                } else {
                                    val newObj = PageTextObject(
                                        id = UUID.randomUUID().toString(),
                                        pageId = currentPage?.id ?: "",
                                        x = pendingTextInsertDocCoords.first,
                                        y = pendingTextInsertDocCoords.second,
                                        text = insertedTextContent.trim(),
                                        color = activeColorState,
                                        zIndex = (pageTexts.maxOfOrNull { it.zIndex } ?: 0) + 1
                                    )
                                    selectedTextId = newObj.id
                                    pageTexts + newObj
                                }
                                pageTexts = updated
                                coroutineScope.launch {
                                    currentPage?.id?.let { repository.savePageTexts(it, updated) }
                                }
                                insertedTextContent = ""
                                editingTextObject = null
                            }
                        }
                    ) {
                        Text(if (editingTextObject != null) "Save" else "Insert")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showInsertTextDialog = false
                        editingTextObject = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- "ASK INKFLOW" SIDE PANEL (100% Real Document Content & Study Card Generator) ---
        AnimatedVisibility(
            visible = showAiPanel,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(390.dp)
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
                        text = "100% On-Device · Grounded in your real page notes & strokes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Add Study Note / Transcript to Current Page
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Page ${currentPageIndex + 1} Study Notes (${pageTexts.size} notes, ${currentStrokes.size} strokes)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = quickStudyNoteInput,
                                onValueChange = { quickStudyNoteInput = it },
                                placeholder = { Text("Add key concept (e.g. Entropy is a measure of disorder)…", fontSize = 12.sp) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    if (quickStudyNoteInput.isNotBlank()) {
                                        val newNote = PageTextObject(
                                            id = UUID.randomUUID().toString(),
                                            pageId = currentPage?.id ?: "",
                                            x = 72f,
                                            y = (68f + pageTexts.size * 38f).coerceAtMost((currentPage?.height ?: 842f) - 60f),
                                            text = quickStudyNoteInput.trim()
                                        )
                                        val updated = pageTexts + newNote
                                        pageTexts = updated
                                        coroutineScope.launch {
                                            currentPage?.id?.let { repository.savePageTexts(it, updated) }
                                        }
                                        quickStudyNoteInput = ""
                                        Toast.makeText(context, "Added note to Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Note to Page ${currentPageIndex + 1}", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // AI Action Buttons operating on REAL document corpus
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isAiLoading = true
                                    val corpus = buildRealDocumentCorpus()
                                    val result = aiProvider.generateSummary(corpus)
                                    aiSummary = result.getOrNull()
                                    isAiLoading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Summarize Note", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isAiLoading = true
                                    val corpus = buildRealDocumentCorpus()
                                    val result = aiProvider.generateFlashcards(corpus)
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
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // Display Summary
                    aiSummary?.let { summary ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Document Summary", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Display Generated Flashcards
                    if (aiFlashcards.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // Ask Question Form grounded in real corpus
                    OutlinedTextField(
                        value = aiQueryText,
                        onValueChange = { aiQueryText = it },
                        placeholder = { Text("Search or ask about this document…") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isAiLoading = true
                                val corpus = buildRealDocumentCorpus()
                                val result = aiProvider.answerQuestion(
                                    content = corpus,
                                    question = aiQueryText
                                )
                                aiAnswerText = result.getOrNull()
                                isAiLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Find Answer in Notes")
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

/**
 * Renders a vector InkStroke with Quadratic Bezier curve smoothing and tool-specific brush physics
 * (Fountain Pen pressure dynamics, Highlighter Multiply/Darken blend, Pencil graphite grain, and Shape crispness).
 */
private fun DrawScope.drawStyledInkStroke(
    stroke: InkStroke,
    isSelected: Boolean,
    isAudioActive: Boolean
) {
    val pts = stroke.points
    if (pts.size < 2) return

    val isHighlight = stroke.tool == ToolType.HIGHLIGHTER
    val isPencil = stroke.tool == ToolType.PENCIL

    val baseColor = when {
        isSelected -> Color(0xFF3B82F6)
        isAudioActive -> Color(0xFFEAB308)
        else -> Color(stroke.color.toInt()).copy(alpha = stroke.opacity)
    }

    val strokePath = Path()
    strokePath.moveTo(pts[0].x, pts[0].y)

    if (stroke.tool == ToolType.SHAPE || pts.size == 2) {
        for (i in 1 until pts.size) {
            strokePath.lineTo(pts[i].x, pts[i].y)
        }
    } else {
        for (i in 1 until pts.size - 1) {
            val midX = (pts[i].x + pts[i + 1].x) / 2f
            val midY = (pts[i].y + pts[i + 1].y) / 2f
            strokePath.quadraticTo(pts[i].x, pts[i].y, midX, midY)
        }
        val last = pts.last()
        strokePath.lineTo(last.x, last.y)
    }

    drawPath(
        path = strokePath,
        color = baseColor,
        blendMode = if (isHighlight && !isSelected) BlendMode.Darken else BlendMode.SrcOver,
        style = Stroke(
            width = if (isHighlight) stroke.width * 2.8f else stroke.width,
            cap = if (isHighlight) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (isPencil) PathEffect.dashPathEffect(floatArrayOf(stroke.width * 2.2f, stroke.width * 0.35f), 0f) else null
        )
    )

    // Secondary subtle graphite grain strand for Pencil tool
    if (isPencil && !isSelected) {
        drawPath(
            path = strokePath,
            color = baseColor.copy(alpha = stroke.opacity * 0.45f),
            style = Stroke(
                width = stroke.width * 1.25f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke.width * 0.8f, stroke.width * 1.1f), 2f)
            )
        )
    }
}
