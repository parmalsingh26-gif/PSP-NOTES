package app.inkflow.studio.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.net.Uri
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.*

private val editorPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private enum class ImageHandleType {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ROTATE,
    CROP_LEFT,
    CROP_RIGHT,
    CROP_TOP,
    CROP_BOTTOM,
    CROP_TOP_LEFT,
    CROP_TOP_RIGHT,
    CROP_BOTTOM_LEFT,
    CROP_BOTTOM_RIGHT
}

private data class LaserPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

private data class LaserStrokeTrail(
    val id: String,
    val pageId: String,
    val color: Long,
    val points: List<LaserPoint>
)

internal data class UncroppedImageFrame(
    val fullX: Float,
    val fullY: Float,
    val fullW: Float,
    val fullH: Float,
    val centerX: Float,
    val centerY: Float
)

internal fun inverseRotatePoint(
    px: Float,
    py: Float,
    cx: Float,
    cy: Float,
    rotationDeg: Float
): Pair<Float, Float> {
    if (rotationDeg == 0f) return Pair(px, py)
    val rad = Math.toRadians(-rotationDeg.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val dx = px - cx
    val dy = py - cy
    return Pair(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
}

internal fun isPointInsideImage(
    docX: Float,
    docY: Float,
    img: PageImageObject,
    pad: Float = 8f
): Boolean {
    val cx = img.x + img.width / 2f
    val cy = img.y + img.height / 2f
    val (localX, localY) = inverseRotatePoint(docX, docY, cx, cy, img.rotationDegrees)
    return localX in (img.x - pad)..(img.x + img.width + pad) &&
            localY in (img.y - pad)..(img.y + img.height + pad)
}

internal fun computeUncroppedFrame(img: PageImageObject): UncroppedImageFrame {
    val cropW = (img.cropRight - img.cropLeft).coerceAtLeast(0.05f)
    val cropH = (img.cropBottom - img.cropTop).coerceAtLeast(0.05f)
    val fullW = img.width / cropW
    val fullH = img.height / cropH
    val fullX = img.x - img.cropLeft * fullW
    val fullY = img.y - img.cropTop * fullH
    return UncroppedImageFrame(
        fullX = fullX,
        fullY = fullY,
        fullW = fullW,
        fullH = fullH,
        centerX = img.x + img.width / 2f,
        centerY = img.y + img.height / 2f
    )
}

internal fun applyImageCrop(
    img: PageImageObject,
    liveLeft: Float,
    liveTop: Float,
    liveRight: Float,
    liveBottom: Float
): PageImageObject {
    val safeL = liveLeft.coerceIn(0f, 0.92f)
    val safeT = liveTop.coerceIn(0f, 0.92f)
    val safeR = liveRight.coerceIn(safeL + 0.05f, 1f)
    val safeB = liveBottom.coerceIn(safeT + 0.05f, 1f)

    val frame = computeUncroppedFrame(img)
    val newW = ((safeR - safeL) * frame.fullW).coerceAtLeast(24f)
    val newH = ((safeB - safeT) * frame.fullH).coerceAtLeast(24f)

    val localNewX = frame.fullX + safeL * frame.fullW
    val localNewY = frame.fullY + safeT * frame.fullH
    val localNewCx = localNewX + newW / 2f
    val localNewCy = localNewY + newH / 2f

    val flippedCx = if (img.isFlippedHorizontal) 2f * frame.centerX - localNewCx else localNewCx
    val flippedCy = if (img.isFlippedVertical) 2f * frame.centerY - localNewCy else localNewCy

    val (worldCx, worldCy) = if (img.rotationDegrees != 0f) {
        val rad = Math.toRadians(img.rotationDegrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = flippedCx - frame.centerX
        val dy = flippedCy - frame.centerY
        Pair(frame.centerX + dx * cosA - dy * sinA, frame.centerY + dx * sinA + dy * cosA)
    } else {
        Pair(flippedCx, flippedCy)
    }

    return img.copy(
        x = worldCx - newW / 2f,
        y = worldCy - newH / 2f,
        width = newW,
        height = newH,
        cropLeft = safeL,
        cropTop = safeT,
        cropRight = safeR,
        cropBottom = safeB
    )
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

    // Authoritative multi-page in-memory state keyed by pageId so continuous scroll renders all
    // visible pages' ink, images, and text notes without ever overwriting or losing edits on scroll.
    val pageStrokesMap = remember { mutableStateMapOf<String, List<InkStroke>>() }
    val pageTextsMap = remember { mutableStateMapOf<String, List<PageTextObject>>() }
    val pageImagesMap = remember { mutableStateMapOf<String, List<PageImageObject>>() }
    val loadedPageIds = remember { mutableStateMapOf<String, Boolean>() }

    val currentPageId = currentPage?.id
    val currentStrokes: List<InkStroke> = currentPageId?.let { pageStrokesMap[it] } ?: emptyList()
    val pageTexts: List<PageTextObject> = currentPageId?.let { pageTextsMap[it] } ?: emptyList()
    val pageImages: List<PageImageObject> = currentPageId?.let { pageImagesMap[it] } ?: emptyList()
    var activeStrokeState by remember { mutableStateOf<InkStroke?>(null) }
    var activeStrokePageId by remember { mutableStateOf<String?>(null) }

    fun getStrokes(pageId: String?): List<InkStroke> = if (pageId != null) (pageStrokesMap[pageId] ?: emptyList()) else emptyList()
    fun getTexts(pageId: String?): List<PageTextObject> = if (pageId != null) (pageTextsMap[pageId] ?: emptyList()) else emptyList()
    fun getImages(pageId: String?): List<PageImageObject> = if (pageId != null) (pageImagesMap[pageId] ?: emptyList()) else emptyList()

    fun commitPageStrokes(pageId: String, newList: List<InkStroke>) {
        pageStrokesMap[pageId] = newList
        loadedPageIds[pageId] = true
        editorPersistenceScope.launch { repository.savePageStrokes(pageId, newList) }
    }
    fun commitPageTexts(pageId: String, newList: List<PageTextObject>) {
        pageTextsMap[pageId] = newList
        loadedPageIds[pageId] = true
        editorPersistenceScope.launch { repository.savePageTexts(pageId, newList) }
    }
    fun commitPageImages(pageId: String, newList: List<PageImageObject>, persistNow: Boolean = true) {
        pageImagesMap[pageId] = newList
        loadedPageIds[pageId] = true
        if (persistNow) {
            editorPersistenceScope.launch { repository.savePageImages(pageId, newList) }
        }
    }

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

    // Fit Width View Mode (Fits PDF page to device width for reading without side margins)
    var isFitWidth by remember { mutableStateOf(true) }

    // Touch gesture tracking for click vs draw detection & active multi-page interaction
    var touchDownDocX by remember { mutableFloatStateOf(0f) }
    var touchDownDocY by remember { mutableFloatStateOf(0f) }
    var touchDownScreenX by remember { mutableFloatStateOf(0f) }
    var touchDownScreenY by remember { mutableFloatStateOf(0f) }
    var touchDownTime by remember { mutableLongStateOf(0L) }
    var potentialTappedImageId by remember { mutableStateOf<String?>(null) }
    var activeInteractionPageId by remember { mutableStateOf<String?>(null) }

    // PDF Reading Guide (Red Line Overlay with trail/fade effect)
    var showReadingGuide by remember { mutableStateOf(false) }
    var readingGuideY by remember { mutableFloatStateOf(300f) }
    var isDraggingReadingGuide by remember { mutableStateOf(false) }
    var readingGuideLastTapTime by remember { mutableLongStateOf(0L) }
    // Trail: last 6 positions that fade out behind the reading guide
    var readingGuideTrail by remember { mutableStateOf<List<Float>>(emptyList()) }

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
    var isCroppingImage by remember { mutableStateOf(false) }
    // Live crop values used while in interactive canvas crop mode
    var liveCropLeft by remember { mutableFloatStateOf(0f) }
    var liveCropTop by remember { mutableFloatStateOf(0f) }
    var liveCropRight by remember { mutableFloatStateOf(1f) }
    var liveCropBottom by remember { mutableFloatStateOf(1f) }
    var lastImageTapTime by remember { mutableLongStateOf(0L) }
    var lastTappedImageId by remember { mutableStateOf<String?>(null) }

    // Laser Pen State (draws glowing fading underline trail right under text lines while reading)
    var laserColorState by remember { mutableLongStateOf(0xFFEF4444L) }
    var isLaserUnderlineSnap by remember { mutableStateOf(true) }
    var laserTrails by remember { mutableStateOf<List<LaserStrokeTrail>>(emptyList()) }
    var activeLaserTrail by remember { mutableStateOf<LaserStrokeTrail?>(null) }
    var laserRenderTick by remember { mutableLongStateOf(0L) }

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

    // Safe Page Switch Helper: clears selection/undo cleanly and optionally resets pan to top of target page
    fun navigateToPage(targetIndex: Int, resetPan: Boolean = true) {
        if (targetIndex < 0 || targetIndex >= pages.size) return
        val prevPage = currentPage
        if (prevPage != null) {
            val sSnap = getStrokes(prevPage.id)
            val tSnap = getTexts(prevPage.id)
            val iSnap = getImages(prevPage.id)
            editorPersistenceScope.launch {
                repository.savePageStrokes(prevPage.id, sSnap)
                repository.savePageTexts(prevPage.id, tSnap)
                repository.savePageImages(prevPage.id, iSnap)
            }
        }
        if (targetIndex != currentPageIndex) {
            inkEngine.clearHistory()
            selectedStrokes = emptySet()
            selectedImageId = null
            selectedTextId = null
            isCroppingImage = false
            activeImageHandle = ImageHandleType.NONE
        }
        if (resetPan) {
            panOffset = Offset.Zero
        }
        currentPageIndex = targetIndex
        editorPersistenceScope.launch {
            repository.updateLastOpenedPage(documentId, targetIndex)
        }
    }

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
                    strokeProvider = { pageId -> pageStrokesMap[pageId] ?: repository.loadPageStrokes(pageId) },
                    imageProvider = { pageId -> pageImagesMap[pageId] ?: repository.loadPageImages(pageId) },
                    textProvider = { pageId -> pageTextsMap[pageId] ?: repository.loadPageTexts(pageId) },
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

    // Image Gallery Picker (native Photo Gallery via PickVisualMedia + GetContent universal fallback)
    var pendingImageTargetPageId by remember { mutableStateOf<String?>(null) }
    val handleSelectedImageUri: (Uri?) -> Unit = { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val targetPid = pendingImageTargetPageId ?: currentPage?.id
                    val targetPage = pages.find { p -> p.id == targetPid } ?: currentPage ?: return@launch
                    val savedPath = repository.copyImageFromUri(it)
                    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(savedPath) }
                    if (bmp != null) {
                        imageBitmaps[savedPath] = bmp
                    }
                    val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
                    val initW = 240f
                    val initH = (initW / aspect).coerceIn(80f, 360f)

                    val existingImgs = getImages(targetPage.id)
                    val newImg = PageImageObject(
                        id = UUID.randomUUID().toString(),
                        pageId = targetPage.id,
                        filePath = savedPath,
                        x = ((targetPage.width - initW) / 2f).coerceAtLeast(20f),
                        y = ((targetPage.height - initH) / 2f).coerceAtLeast(20f),
                        width = initW,
                        height = initH,
                        zIndex = (existingImgs.maxOfOrNull { img -> img.zIndex } ?: 0) + 1
                    )
                    val updated = existingImgs + newImg
                    commitPageImages(targetPage.id, updated)
                    selectedImageId = newImg.id
                    selectedTextId = null
                    isCroppingImage = false
                    selectTool(ToolType.PAN)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image inserted — drag handles, or tap Crop to trim edges", Toast.LENGTH_SHORT).show()
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
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = handleSelectedImageUri
    )
    val imageGetContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = handleSelectedImageUri
    )
    fun launchImageGalleryPicker() {
        pendingImageTargetPageId = currentPage?.id
        try {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Throwable) {
            try {
                imageGetContentLauncher.launch("image/*")
            } catch (_: Throwable) {
                Toast.makeText(context, "No image picker available on device", Toast.LENGTH_SHORT).show()
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
            val texts = if (loadedPageIds[p.id] == true) getTexts(p.id) else repository.loadPageTexts(p.id)
            val strokes = if (loadedPageIds[p.id] == true) getStrokes(p.id) else repository.loadPageStrokes(p.id)
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

    // View Mode: Continuous Vertical Scrolling (like standard PDF / Acrobat) vs Single Page Flip
    var isContinuousVerticalScroll by remember { mutableStateOf(true) }
    val pdfBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }

    // Resume last opened page once when document & pages load
    var hasRestoredLastPage by remember(documentId) { mutableStateOf(false) }
    LaunchedEffect(document?.id, pages.size) {
        val doc = document ?: return@LaunchedEffect
        if (!hasRestoredLastPage && pages.isNotEmpty()) {
            hasRestoredLastPage = true
            currentPageIndex = doc.lastOpenedPageIndex.coerceIn(0, pages.size - 1)
        }
    }

    // Preload strokes, text notes, and images for ALL currently visible pages (and decode image bitmaps)
    val visiblePageRange = remember(currentPageIndex, pages, isContinuousVerticalScroll) {
        if (pages.isEmpty()) emptyList()
        else if (isContinuousVerticalScroll) {
            val minIdx = (currentPageIndex - 2).coerceAtLeast(0)
            val maxIdx = (currentPageIndex + 2).coerceAtMost(pages.size - 1)
            (minIdx..maxIdx).map { pages[it] }
        } else {
            listOfNotNull(pages.getOrNull(currentPageIndex))
        }
    }

    LaunchedEffect(visiblePageRange) {
        val toLoad = visiblePageRange.filter { loadedPageIds[it.id] != true }
        if (toLoad.isNotEmpty()) {
            val loadedData = withContext(Dispatchers.IO) {
                toLoad.map { p ->
                    Triple(
                        p.id,
                        repository.loadPageStrokes(p.id),
                        Pair(repository.loadPageTexts(p.id), repository.loadPageImages(p.id))
                    )
                }
            }
            for ((pid, s, ti) in loadedData) {
                if (loadedPageIds[pid] != true) {
                    pageStrokesMap[pid] = s
                    pageTextsMap[pid] = ti.first
                    pageImagesMap[pid] = ti.second
                    loadedPageIds[pid] = true
                }
            }
        }
        // Decode image bitmaps for ALL images on visible pages that aren't cached yet
        val allVisibleImages = visiblePageRange.flatMap { pageImagesMap[it.id].orEmpty() }
        val missingPaths = allVisibleImages.map { it.filePath }.distinct().filter { !imageBitmaps.containsKey(it) }
        if (missingPaths.isNotEmpty()) {
            val decoded = withContext(Dispatchers.IO) {
                missingPaths.mapNotNull { path ->
                    val f = File(path)
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { path to it } else null
                }
            }
            for ((path, bmp) in decoded) {
                imageBitmaps[path] = bmp
            }
        }
    }

    // Flush ALL loaded pages safely via non-cancellable scope when leaving the editor
    DisposableEffect(documentId) {
        onDispose {
            val strokeSnapshot = pageStrokesMap.toMap()
            val textSnapshot = pageTextsMap.toMap()
            val imageSnapshot = pageImagesMap.toMap()
            editorPersistenceScope.launch {
                strokeSnapshot.forEach { (pid, s) -> repository.savePageStrokes(pid, s) }
                textSnapshot.forEach { (pid, t) -> repository.savePageTexts(pid, t) }
                imageSnapshot.forEach { (pid, i) -> repository.savePageImages(pid, i) }
            }
        }
    }

    // Smooth 60fps decay loop while Laser Pen trails are fading out behind the reader
    LaunchedEffect(laserTrails.isNotEmpty(), activeLaserTrail != null) {
        while (laserTrails.isNotEmpty() || activeLaserTrail != null) {
            withFrameNanos { }
            val nowMs = System.currentTimeMillis()
            val cutoff = nowMs - 1700L
            laserTrails = laserTrails.mapNotNull { trail ->
                val freshPts = trail.points.filter { it.timestampMs >= cutoff }
                if (freshPts.size >= 2) trail.copy(points = freshPts) else null
            }
            activeLaserTrail?.let { active ->
                val freshPts = active.points.filter { it.timestampMs >= cutoff }
                if (freshPts.size != active.points.size) {
                    activeLaserTrail = if (freshPts.isNotEmpty()) active.copy(points = freshPts) else null
                }
            }
            laserRenderTick = nowMs
        }
    }

    // Synchronize default modes once document metadata loads
    LaunchedEffect(document?.id, document?.type) {
        if (document?.type == DocumentType.PDF) {
            isFitWidth = true
            isContinuousVerticalScroll = true
        }
    }

    // Render PDF page bitmap whenever currentPageIndex, pdfRenderer, or isContinuousVerticalScroll changes
    LaunchedEffect(currentPageIndex, pdfRenderer, isContinuousVerticalScroll, currentPage?.pdfPageIndex, pages) {
        pdfRenderer?.let { renderer ->
            val pCount = renderer.pageCount
            if (pCount <= 0) return@LaunchedEffect

            val isPdfDoc = document?.type == DocumentType.PDF
            val currentPdfIdx = currentPage?.pdfPageIndex ?: (if (isPdfDoc) currentPageIndex else null)

            val pagesToLoad = if (isContinuousVerticalScroll && pCount > 1) {
                pages.filter { abs(it.index - currentPageIndex) <= 3 }
                    .mapNotNull { it.pdfPageIndex ?: (if (isPdfDoc) it.index else null) }
            } else {
                listOfNotNull(currentPdfIdx)
            }

            // Evict distant bitmaps from Compose state to prevent heap exhaustion
            val activePdfIdx = currentPdfIdx ?: 0
            pdfBitmaps.keys.filter { abs(it - activePdfIdx) > 4 }.forEach { pdfBitmaps.remove(it) }

            for (idx in pagesToLoad) {
                if (idx in 0 until pCount && !pdfBitmaps.containsKey(idx)) {
                    val bmp = renderer.renderPage(idx, targetWidth = 1440, targetHeight = 2040)
                    if (bmp != null) {
                        pdfBitmaps[idx] = bmp
                    }
                }
            }
            pdfBitmap = currentPdfIdx?.let { pdfBitmaps[it] }
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
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Back & Title (fixed, compact so action bar has plenty of room on phones)
                        IconButton(onClick = {
                            val strokeSnap = pageStrokesMap.toMap()
                            val textSnap = pageTextsMap.toMap()
                            val imgSnap = pageImagesMap.toMap()
                            editorPersistenceScope.launch {
                                strokeSnap.forEach { (pid, s) -> repository.savePageStrokes(pid, s) }
                                textSnap.forEach { (pid, t) -> repository.savePageTexts(pid, t) }
                                imgSnap.forEach { (pid, i) -> repository.savePageImages(pid, i) }
                            }
                            onNavigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.widthIn(max = 130.dp).padding(end = 6.dp)) {
                            Text(
                                text = document?.title ?: "Note",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${currentStrokes.size} strokes · ${pageImages.size} img",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1
                            )
                        }

                        // Scrollable Action Strip: guarantees ALL buttons are reachable on both phones & tablets
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                enabled = currentPageIndex > 0,
                                onClick = { navigateToPage(currentPageIndex - 1) }
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                            }

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showPageManagerSheet = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${currentPageIndex + 1}/${pages.size.coerceAtLeast(1)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(
                                enabled = currentPageIndex < pages.size - 1,
                                onClick = { navigateToPage(currentPageIndex + 1) }
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
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

                            // Laser Pen Toggle (draw fading underline under text lines as you read)
                            val isLaserSelected = activeToolState == ToolType.LASER
                            IconButton(
                                onClick = {
                                    if (isLaserSelected) selectTool(ToolType.PEN) else selectTool(ToolType.LASER)
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isLaserSelected) Color(0x28EF4444) else Color.Transparent)
                            ) {
                                Icon(
                                    Icons.Default.Flare,
                                    contentDescription = "Laser Reading Pen",
                                    tint = if (isLaserSelected) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // PDF Reading Guide Toggle (Red Border / White Center Line)
                            IconButton(
                                onClick = { showReadingGuide = !showReadingGuide },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (showReadingGuide) Color(0x22DC2626) else Color.Transparent)
                            ) {
                                Icon(
                                    Icons.Default.HorizontalRule,
                                    contentDescription = "Toggle Reading Guide",
                                    tint = if (showReadingGuide) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // View Mode: Fit Width vs Fit Page
                            IconButton(
                                onClick = {
                                    isFitWidth = !isFitWidth
                                    panOffset = Offset.Zero
                                    zoomScale = 1.0f
                                    Toast.makeText(context, if (isFitWidth) "Fit Width" else "Fit Whole Page", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isFitWidth) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            ) {
                                Icon(
                                    imageVector = if (isFitWidth) Icons.Default.FitScreen else Icons.Default.ZoomOutMap,
                                    contentDescription = "Fit Width / Page",
                                    tint = if (isFitWidth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Scroll Mode Toggle: Single Page Flip vs Continuous Vertical Scroll
                            IconButton(
                                onClick = {
                                    isContinuousVerticalScroll = !isContinuousVerticalScroll
                                    panOffset = Offset.Zero
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

                            VerticalDivider(modifier = Modifier.height(22.dp).padding(horizontal = 2.dp))

                            if (recordingState == RecordingState.RECORDING) {
                                Button(
                                    onClick = { audioRecorder.stopRecording() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
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
                                            modifier = Modifier.size(24.dp)
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

                            IconButton(onClick = { showExportDialog = true }) {
                                Icon(Icons.Default.Share, contentDescription = "Export PDF")
                            }

                            FilledTonalButton(
                                onClick = { showAiPanel = !showAiPanel },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ask InkFlow", fontSize = 12.sp)
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

                val margin = if (isFitWidth) 0f else 20f
                val fitScaleX = if (isFitWidth) availableW / pageW else (availableW - margin * 2) / pageW
                val fitScaleY = (availableH - margin * 2) / pageH
                val baseScale = if (isFitWidth) fitScaleX.coerceIn(0.2f, 4.0f) else min(fitScaleX, fitScaleY).coerceAtLeast(0.1f)

                val totalScale = baseScale * zoomScale

                val originX = if (isFitWidth) ((availableW - pageW * totalScale) / 2f).coerceAtLeast(0f) else (availableW - pageW * totalScale) / 2f
                val originY = if (isContinuousVerticalScroll) 16f else (availableH - pageH * totalScale) / 2f
                val pageGapPx = 24f

                fun getPageYOffset(targetIdx: Int): Float {
                    if (!isContinuousVerticalScroll || pages.isEmpty() || targetIdx == currentPageIndex) return 0f
                    var y = 0f
                    if (targetIdx > currentPageIndex) {
                        for (i in currentPageIndex until targetIdx) {
                            y += (pages.getOrNull(i)?.height ?: pageH) + pageGapPx
                        }
                    } else {
                        for (i in targetIdx until currentPageIndex) {
                            y -= (pages.getOrNull(i)?.height ?: pageH) + pageGapPx
                        }
                    }
                    return y
                }

                fun findTouchedPageIndex(rawDocY: Float): Int {
                    if (!isContinuousVerticalScroll || pages.size <= 1) {
                        return currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    }
                    if (rawDocY >= 0f) {
                        var top = 0f
                        for (i in currentPageIndex until pages.size) {
                            val h = pages[i].height + pageGapPx
                            if (rawDocY < top + h || i == pages.lastIndex) return i
                            top += h
                        }
                        return pages.lastIndex
                    } else {
                        var top = 0f
                        for (i in (currentPageIndex - 1) downTo 0) {
                            val h = pages[i].height + pageGapPx
                            top -= h
                            if (rawDocY >= top || i == 0) return i
                        }
                        return 0
                    }
                }

                fun getInteractOffsetY(pageId: String?): Float {
                    if (!isContinuousVerticalScroll || pages.size <= 1 || pageId == null) return 0f
                    val idx = pages.indexOfFirst { it.id == pageId }
                    return if (idx >= 0) getPageYOffset(idx) else 0f
                }

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
                                        activeStrokePageId = null
                                        activeLaserTrail = null
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

                                            if (isContinuousVerticalScroll && pages.size > 1) {
                                                val curPageTotalH = ((currentPage?.height ?: pageH) + pageGapPx) * totalScale
                                                if (panOffset.y < -curPageTotalH * 0.5f && currentPageIndex < pages.size - 1) {
                                                    panOffset = panOffset.copy(y = panOffset.y + curPageTotalH)
                                                    navigateToPage(currentPageIndex + 1, resetPan = false)
                                                } else if (panOffset.y > curPageTotalH * 0.5f && currentPageIndex > 0) {
                                                    val prevTotalH = ((pages[currentPageIndex - 1].height) + pageGapPx) * totalScale
                                                    panOffset = panOffset.copy(y = panOffset.y - prevTotalH)
                                                    navigateToPage(currentPageIndex - 1, resetPan = false)
                                                }
                                            }
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

                            val touchedPageIndex = findTouchedPageIndex(rawDocY)
                            val touchedPage = pages.getOrNull(touchedPageIndex) ?: currentPage
                            val touchedPageW = touchedPage?.width ?: pageW
                            val touchedPageH = touchedPage?.height ?: pageH
                            val touchedPageStartY = getPageYOffset(touchedPageIndex)

                            val localUnclampedX = rawDocX
                            val localUnclampedY = if (isContinuousVerticalScroll && pages.size > 1) {
                                rawDocY - touchedPageStartY
                            } else {
                                rawDocY
                            }
                            val annotDocX = if (isContinuousVerticalScroll && pages.size > 1) {
                                localUnclampedX.coerceIn(0f, touchedPageW)
                            } else {
                                rawDocX
                            }
                            val annotDocY = if (isContinuousVerticalScroll && pages.size > 1) {
                                localUnclampedY.coerceIn(0f, touchedPageH)
                            } else {
                                rawDocY
                            }

                            val effectiveTool = if (isStylusEraser) ToolType.ERASER_STROKE else activeToolState

                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchDownScreenX = touchX
                                    touchDownScreenY = touchY
                                    touchDownTime = System.currentTimeMillis()

                                    // A0. Check if user tapped/dragged the Reading Guide line (global to current viewport)
                                    if (showReadingGuide) {
                                        val guideHitZone = 22f / totalScale
                                        if (abs(rawDocY - readingGuideY) <= guideHitZone) {
                                            val nowMs = System.currentTimeMillis()
                                            val doubleTap = nowMs - readingGuideLastTapTime < 350L
                                            readingGuideLastTapTime = nowMs
                                            if (doubleTap) {
                                                showReadingGuide = false
                                            } else {
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
                                        if (abs(along) <= 200f && abs(perp) <= 18f) {
                                            isDraggingRuler = true
                                            rulerDragStart = Pair(rawDocX - rulerX, rawDocY - rulerY)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // Page switch in continuous scroll: keep visual position locked with zero jump
                                    if (isContinuousVerticalScroll && touchedPageIndex != currentPageIndex && touchedPageIndex in pages.indices) {
                                        panOffset = panOffset.copy(y = panOffset.y + touchedPageStartY * totalScale)
                                        navigateToPage(touchedPageIndex, resetPan = false)
                                    }

                                    val activePid = touchedPage?.id
                                    activeInteractionPageId = activePid
                                    val activeImages = getImages(activePid)
                                    val activeTexts = getTexts(activePid)
                                    val activeStrokes = getStrokes(activePid)

                                    // B. Check Selected Image Handles (Real 8-handle interactive Crop + 4 Resize Corners + Rotate + Move)
                                    val curSelectedImage = activeImages.find { it.id == selectedImageId }
                                    if (curSelectedImage != null && !curSelectedImage.isLocked) {
                                        val img = curSelectedImage
                                        val hitR = 34f / totalScale

                                        // B1. Interactive Canvas Crop Handles (4 corners + 4 edges)
                                        if (isCroppingImage) {
                                            val frame = computeUncroppedFrame(img)
                                            val (rotX, rotY) = inverseRotatePoint(localUnclampedX, localUnclampedY, frame.centerX, frame.centerY, img.rotationDegrees)
                                            val cropLocalX = if (img.isFlippedHorizontal) 2f * frame.centerX - rotX else rotX
                                            val cropLocalY = if (img.isFlippedVertical) 2f * frame.centerY - rotY else rotY

                                            val cropHitR = 34f / totalScale
                                            val cropL = frame.fullX + liveCropLeft * frame.fullW
                                            val cropR = frame.fullX + liveCropRight * frame.fullW
                                            val cropT = frame.fullY + liveCropTop * frame.fullH
                                            val cropB = frame.fullY + liveCropBottom * frame.fullH
                                            val midX = (cropL + cropR) / 2f
                                            val midY = (cropT + cropB) / 2f

                                            val matchedCropHandle = when {
                                                hypot(cropLocalX - cropL, cropLocalY - cropT) < cropHitR -> ImageHandleType.CROP_TOP_LEFT
                                                hypot(cropLocalX - cropR, cropLocalY - cropT) < cropHitR -> ImageHandleType.CROP_TOP_RIGHT
                                                hypot(cropLocalX - cropL, cropLocalY - cropB) < cropHitR -> ImageHandleType.CROP_BOTTOM_LEFT
                                                hypot(cropLocalX - cropR, cropLocalY - cropB) < cropHitR -> ImageHandleType.CROP_BOTTOM_RIGHT
                                                hypot(cropLocalX - cropL, cropLocalY - midY) < cropHitR -> ImageHandleType.CROP_LEFT
                                                hypot(cropLocalX - cropR, cropLocalY - midY) < cropHitR -> ImageHandleType.CROP_RIGHT
                                                hypot(cropLocalX - midX, cropLocalY - cropT) < cropHitR -> ImageHandleType.CROP_TOP
                                                hypot(cropLocalX - midX, cropLocalY - cropB) < cropHitR -> ImageHandleType.CROP_BOTTOM
                                                else -> ImageHandleType.NONE
                                            }
                                            if (matchedCropHandle != ImageHandleType.NONE) {
                                                activeImageHandle = matchedCropHandle
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }

                                            // Absorb touch inside full uncropped bounds so accidental strokes don't trigger during crop
                                            if (cropLocalX in (frame.fullX - 16f / totalScale)..(frame.fullX + frame.fullW + 16f / totalScale) &&
                                                cropLocalY in (frame.fullY - 16f / totalScale)..(frame.fullY + frame.fullH + 16f / totalScale)
                                            ) {
                                                return@pointerInteropFilter true
                                            }
                                            isCroppingImage = false
                                        }

                                        val cx = img.x + img.width / 2f
                                        val cy = img.y + img.height / 2f
                                        val (localRotX, localRotY) = inverseRotatePoint(localUnclampedX, localUnclampedY, cx, cy, img.rotationDegrees)
                                        val rotKnobX = img.x + img.width / 2f
                                        val rotKnobY = img.y - 22f / totalScale

                                        when {
                                            hypot(localRotX - rotKnobX, localRotY - rotKnobY) < hitR -> {
                                                activeImageHandle = ImageHandleType.ROTATE
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(localRotX - (img.x + img.width), localRotY - (img.y + img.height)) < hitR -> {
                                                activeImageHandle = ImageHandleType.BOTTOM_RIGHT
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(localRotX - img.x, localRotY - img.y) < hitR -> {
                                                activeImageHandle = ImageHandleType.TOP_LEFT
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(localRotX - (img.x + img.width), localRotY - img.y) < hitR -> {
                                                activeImageHandle = ImageHandleType.TOP_RIGHT
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            hypot(localRotX - img.x, localRotY - (img.y + img.height)) < hitR -> {
                                                activeImageHandle = ImageHandleType.BOTTOM_LEFT
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                            localRotX in img.x..(img.x + img.width) && localRotY in img.y..(img.y + img.height) -> {
                                                activeImageHandle = ImageHandleType.MOVE
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = img
                                                return@pointerInteropFilter true
                                            }
                                        }
                                    }

                                    // C. Check Selected Text Object Drag
                                    val curSelectedText = activeTexts.find { it.id == selectedTextId }
                                    if (curSelectedText != null) {
                                        val txt = curSelectedText
                                        val lines = txt.text.split("\n")
                                        val boxW = ((lines.maxOfOrNull { it.length } ?: 4) * txt.fontSizeSp * 0.6f + 24f).coerceAtLeast(70f)
                                        val boxH = lines.size * txt.fontSizeSp * 1.4f + 16f
                                        if (localUnclampedX in (txt.x - 8f)..(txt.x + boxW) && localUnclampedY in (txt.y - 6f)..(txt.y + boxH)) {
                                            isDraggingText = true
                                            textDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                            initialTextPos = Pair(txt.x, txt.y)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // D. Check Dragging Inside Lasso Selection Bounds
                                    if (selectedStrokes.isNotEmpty()) {
                                        val selBounds = inkEngine.getSelectionBounds(activeStrokes)
                                        if (selBounds != null &&
                                            localUnclampedX in (selBounds.left - 12f)..(selBounds.right + 12f) &&
                                            localUnclampedY in (selBounds.top - 12f)..(selBounds.bottom + 12f)
                                        ) {
                                            isDraggingLassoSelection = true
                                            lassoDragLastPoint = Pair(localUnclampedX, localUnclampedY)
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    // Track touch down for tap gesture recognition
                                    touchDownDocX = localUnclampedX
                                    touchDownDocY = localUnclampedY

                                    // E. Tap to select an Image or Text Object (works accurately even on rotated or cropped images)
                                    val tappedImg = activeImages.sortedBy { it.zIndex }.lastOrNull { img ->
                                        isPointInsideImage(localUnclampedX, localUnclampedY, img, pad = 10f / totalScale)
                                    }
                                    potentialTappedImageId = tappedImg?.id
                                    val now = System.currentTimeMillis()
                                    val isImageDoubleTap = (tappedImg != null && tappedImg.id == lastTappedImageId && (now - lastImageTapTime) < 400L)
                                    if (tappedImg != null) {
                                        lastTappedImageId = tappedImg.id
                                        lastImageTapTime = now
                                    }

                                    if (effectiveTool == ToolType.PAN || effectiveTool == ToolType.LASSO || effectiveTool == ToolType.TEXT || isImageDoubleTap) {
                                        val tappedText = activeTexts.lastOrNull { txt ->
                                            val lines = txt.text.split("\n")
                                            val boxW = ((lines.maxOfOrNull { it.length } ?: 4) * txt.fontSizeSp * 0.6f + 24f).coerceAtLeast(70f)
                                            val boxH = lines.size * txt.fontSizeSp * 1.4f + 16f
                                            localUnclampedX in (txt.x - 8f)..(txt.x + boxW) && localUnclampedY in (txt.y - 6f)..(txt.y + boxH)
                                        }
                                        if (tappedText != null) {
                                            selectedTextId = tappedText.id
                                            selectedImageId = null
                                            isCroppingImage = false
                                            isDraggingText = true
                                            textDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                            initialTextPos = Pair(tappedText.x, tappedText.y)
                                            return@pointerInteropFilter true
                                        }

                                        if (tappedImg != null) {
                                            selectedImageId = tappedImg.id
                                            selectedTextId = null
                                            isCroppingImage = false
                                            if (!tappedImg.isLocked) {
                                                activeImageHandle = ImageHandleType.MOVE
                                                imageDragStartPoint = Pair(localUnclampedX, localUnclampedY)
                                                initialImageSnapshot = tappedImg
                                            }
                                            return@pointerInteropFilter true
                                        }
                                    }

                                    val (docX, docY) = snapPointToRulerIfNear(annotDocX, annotDocY)

                                    when (effectiveTool) {
                                        ToolType.LASER -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            val pid = activePid ?: ""
                                            activeLaserTrail = LaserStrokeTrail(
                                                id = UUID.randomUUID().toString(),
                                                pageId = pid,
                                                color = laserColorState,
                                                points = listOf(LaserPoint(annotDocX, annotDocY, System.currentTimeMillis()))
                                            )
                                        }
                                        ToolType.ERASER_STROKE -> {
                                            val (remaining, _) = inkEngine.eraseAt(docX, docY, 20f / totalScale, activeStrokes)
                                            if (activePid != null) commitPageStrokes(activePid, remaining)
                                        }
                                        ToolType.ERASER_PIXEL -> {
                                            val remaining = inkEngine.erasePartialAt(docX, docY, 16f / totalScale, activeStrokes)
                                            if (activePid != null) commitPageStrokes(activePid, remaining)
                                        }
                                        ToolType.LASSO -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            isLassoActive = true
                                            lassoPoints = listOf(Pair(annotDocX, annotDocY))
                                        }
                                        ToolType.TEXT -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            pendingTextInsertDocCoords = Pair(annotDocX.coerceIn(16f, (touchedPageW - 120f).coerceAtLeast(16f)), annotDocY.coerceIn(16f, (touchedPageH - 40f).coerceAtLeast(16f)))
                                            insertedTextContent = ""
                                            editingTextObject = null
                                            showInsertTextDialog = true
                                        }
                                        ToolType.SHAPE -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            shapeStartPoint = Pair(annotDocX, annotDocY)
                                            shapeCurrentPoint = Pair(annotDocX, annotDocY)
                                        }
                                        ToolType.PAN -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            imageDragStartPoint = Pair(touchX, touchY)
                                        }
                                        else -> {
                                            selectedImageId = null
                                            selectedTextId = null
                                            isCroppingImage = false
                                            val currentAudioTime = if (recordingState == RecordingState.RECORDING) {
                                                audioRecorder.currentTimestampMs
                                            } else null

                                            activeStrokePageId = activePid
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

                                    if (isDraggingReadingGuide) {
                                        val newY = rawDocY.coerceIn(10f, pageH - 10f)
                                        if (abs(newY - readingGuideY) > 4f) {
                                            readingGuideTrail = (listOf(readingGuideY) + readingGuideTrail).take(7)
                                        }
                                        readingGuideY = newY
                                        return@pointerInteropFilter true
                                    }

                                    val interactPid = activeInteractionPageId ?: activeStrokePageId ?: currentPageId
                                    val interactPage = pages.find { it.id == interactPid } ?: currentPage
                                    val interactW = interactPage?.width ?: pageW
                                    val interactH = interactPage?.height ?: pageH
                                    val interactOffsetY = getInteractOffsetY(interactPid)

                                    val moveUnclampedX = rawDocX
                                    val moveUnclampedY = if (isContinuousVerticalScroll && pages.size > 1) {
                                        rawDocY - interactOffsetY
                                    } else {
                                        rawDocY
                                    }
                                    val moveDocX = if (isContinuousVerticalScroll && pages.size > 1) moveUnclampedX.coerceIn(0f, interactW) else moveUnclampedX
                                    val moveDocY = if (isContinuousVerticalScroll && pages.size > 1) moveUnclampedY.coerceIn(0f, interactH) else moveUnclampedY

                                    if (activeImageHandle != ImageHandleType.NONE && initialImageSnapshot != null && imageDragStartPoint != null) {
                                        val snap = initialImageSnapshot!!

                                        // Interactive Crop handle dragging (4 corners + 4 edges)
                                        if (isCroppingImage && activeImageHandle in listOf(
                                                ImageHandleType.CROP_LEFT, ImageHandleType.CROP_RIGHT,
                                                ImageHandleType.CROP_TOP, ImageHandleType.CROP_BOTTOM,
                                                ImageHandleType.CROP_TOP_LEFT, ImageHandleType.CROP_TOP_RIGHT,
                                                ImageHandleType.CROP_BOTTOM_LEFT, ImageHandleType.CROP_BOTTOM_RIGHT
                                            )
                                        ) {
                                            val frame = computeUncroppedFrame(snap)
                                            val (rotX, rotY) = inverseRotatePoint(moveUnclampedX, moveUnclampedY, frame.centerX, frame.centerY, snap.rotationDegrees)
                                            val localX = if (snap.isFlippedHorizontal) 2f * frame.centerX - rotX else rotX
                                            val localY = if (snap.isFlippedVertical) 2f * frame.centerY - rotY else rotY

                                            val fx = ((localX - frame.fullX) / frame.fullW)
                                            val fy = ((localY - frame.fullY) / frame.fullH)
                                            when (activeImageHandle) {
                                                ImageHandleType.CROP_LEFT -> {
                                                    liveCropLeft = fx.coerceIn(0f, liveCropRight - 0.06f)
                                                }
                                                ImageHandleType.CROP_RIGHT -> {
                                                    liveCropRight = fx.coerceIn(liveCropLeft + 0.06f, 1f)
                                                }
                                                ImageHandleType.CROP_TOP -> {
                                                    liveCropTop = fy.coerceIn(0f, liveCropBottom - 0.06f)
                                                }
                                                ImageHandleType.CROP_BOTTOM -> {
                                                    liveCropBottom = fy.coerceIn(liveCropTop + 0.06f, 1f)
                                                }
                                                ImageHandleType.CROP_TOP_LEFT -> {
                                                    liveCropLeft = fx.coerceIn(0f, liveCropRight - 0.06f)
                                                    liveCropTop = fy.coerceIn(0f, liveCropBottom - 0.06f)
                                                }
                                                ImageHandleType.CROP_TOP_RIGHT -> {
                                                    liveCropRight = fx.coerceIn(liveCropLeft + 0.06f, 1f)
                                                    liveCropTop = fy.coerceIn(0f, liveCropBottom - 0.06f)
                                                }
                                                ImageHandleType.CROP_BOTTOM_LEFT -> {
                                                    liveCropLeft = fx.coerceIn(0f, liveCropRight - 0.06f)
                                                    liveCropBottom = fy.coerceIn(liveCropTop + 0.06f, 1f)
                                                }
                                                ImageHandleType.CROP_BOTTOM_RIGHT -> {
                                                    liveCropRight = fx.coerceIn(liveCropLeft + 0.06f, 1f)
                                                    liveCropBottom = fy.coerceIn(liveCropTop + 0.06f, 1f)
                                                }
                                                else -> {}
                                            }
                                            return@pointerInteropFilter true
                                        }

                                        val dxRaw = moveUnclampedX - imageDragStartPoint!!.first
                                        val dyRaw = moveUnclampedY - imageDragStartPoint!!.second
                                        val (dx, dy) = if (snap.rotationDegrees != 0f && activeImageHandle != ImageHandleType.MOVE && activeImageHandle != ImageHandleType.ROTATE) {
                                            val rad = Math.toRadians(-snap.rotationDegrees.toDouble())
                                            val cosA = cos(rad).toFloat()
                                            val sinA = sin(rad).toFloat()
                                            Pair(dxRaw * cosA - dyRaw * sinA, dxRaw * sinA + dyRaw * cosA)
                                        } else {
                                            Pair(dxRaw, dyRaw)
                                        }
                                        val aspect = if (snap.height > 0f) snap.width / snap.height else 1f

                                        val updatedImg = when (activeImageHandle) {
                                            ImageHandleType.MOVE -> snap.copy(x = snap.x + dxRaw, y = snap.y + dyRaw)
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
                                                val angleRad = atan2(moveUnclampedY - cy, moveUnclampedX - cx)
                                                val deg = ((Math.toDegrees(angleRad.toDouble()).toFloat() + 90f) + 360f) % 360f
                                                snap.copy(rotationDegrees = deg)
                                            }
                                            else -> snap
                                        }
                                        if (interactPid != null) {
                                            commitPageImages(
                                                interactPid,
                                                getImages(interactPid).map { if (it.id == snap.id) updatedImg else it },
                                                persistNow = false
                                            )
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingText && selectedTextId != null && textDragStartPoint != null && initialTextPos != null && interactPid != null) {
                                        val dx = moveUnclampedX - textDragStartPoint!!.first
                                        val dy = moveUnclampedY - textDragStartPoint!!.second
                                        val newX = (initialTextPos!!.first + dx).coerceIn(8f, (interactW - 60f).coerceAtLeast(8f))
                                        val newY = (initialTextPos!!.second + dy).coerceIn(8f, (interactH - 24f).coerceAtLeast(8f))
                                        pageTextsMap[interactPid] = getTexts(interactPid).map {
                                            if (it.id == selectedTextId) it.copy(x = newX, y = newY) else it
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingLassoSelection && lassoDragLastPoint != null && interactPid != null) {
                                        val dx = moveUnclampedX - lassoDragLastPoint!!.first
                                        val dy = moveUnclampedY - lassoDragLastPoint!!.second
                                        if (hypot(dx, dy) > 1.5f) {
                                            pageStrokesMap[interactPid] = inkEngine.moveSelectedStrokes(dx, dy, getStrokes(interactPid))
                                            lassoDragLastPoint = Pair(moveUnclampedX, moveUnclampedY)
                                        }
                                        return@pointerInteropFilter true
                                    }

                                    val (docX, docY) = snapPointToRulerIfNear(moveDocX, moveDocY)

                                    when (effectiveTool) {
                                        ToolType.LASER -> {
                                            val currentActive = activeLaserTrail ?: LaserStrokeTrail(
                                                id = UUID.randomUUID().toString(),
                                                pageId = interactPid ?: "",
                                                color = laserColorState,
                                                points = emptyList()
                                            )
                                            val pts = currentActive.points
                                            val lastPt = pts.lastOrNull()
                                            val smoothedY = if (isLaserUnderlineSnap && lastPt != null) {
                                                val sdx = abs(moveDocX - lastPt.x)
                                                val sdy = abs(moveDocY - lastPt.y)
                                                if (sdx > sdy * 1.35f && sdy < 18f) {
                                                    lastPt.y * 0.72f + moveDocY * 0.28f
                                                } else {
                                                    moveDocY
                                                }
                                            } else {
                                                moveDocY
                                            }
                                            if (lastPt == null || hypot(moveDocX - lastPt.x, smoothedY - lastPt.y) > 1.5f) {
                                                activeLaserTrail = currentActive.copy(
                                                    points = pts + LaserPoint(moveDocX, smoothedY, System.currentTimeMillis())
                                                )
                                            }
                                        }
                                        ToolType.ERASER_STROKE -> {
                                            if (interactPid != null) {
                                                val (remaining, _) = inkEngine.eraseAt(docX, docY, 20f / totalScale, getStrokes(interactPid))
                                                pageStrokesMap[interactPid] = remaining
                                            }
                                        }
                                        ToolType.ERASER_PIXEL -> {
                                            if (interactPid != null) {
                                                val remaining = inkEngine.erasePartialAt(docX, docY, 16f / totalScale, getStrokes(interactPid))
                                                pageStrokesMap[interactPid] = remaining
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                lassoPoints = lassoPoints + Pair(moveDocX, moveDocY)
                                            }
                                        }
                                        ToolType.SHAPE -> {
                                            shapeCurrentPoint = Pair(moveDocX, moveDocY)
                                        }
                                        ToolType.PAN -> {
                                            imageDragStartPoint?.let { start ->
                                                val dx = touchX - start.first
                                                val dy = touchY - start.second
                                                panOffset += Offset(dx, dy)
                                                imageDragStartPoint = Pair(touchX, touchY)

                                                if (isContinuousVerticalScroll && pages.size > 1) {
                                                    val curPageTotalH = ((currentPage?.height ?: pageH) + pageGapPx) * totalScale
                                                    if (panOffset.y < -curPageTotalH * 0.5f && currentPageIndex < pages.size - 1) {
                                                        panOffset = panOffset.copy(y = panOffset.y + curPageTotalH)
                                                        navigateToPage(currentPageIndex + 1, resetPan = false)
                                                    } else if (panOffset.y > curPageTotalH * 0.5f && currentPageIndex > 0) {
                                                        val prevTotalH = ((pages[currentPageIndex - 1].height) + pageGapPx) * totalScale
                                                        panOffset = panOffset.copy(y = panOffset.y - prevTotalH)
                                                        navigateToPage(currentPageIndex - 1, resetPan = false)
                                                    }
                                                }
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
                                    val interactPid = activeInteractionPageId ?: activeStrokePageId ?: currentPageId
                                    val interactPage = pages.find { it.id == interactPid } ?: currentPage
                                    val interactW = interactPage?.width ?: pageW
                                    val interactH = interactPage?.height ?: pageH
                                    val interactOffsetY = getInteractOffsetY(interactPid)

                                    val upUnclampedX = rawDocX
                                    val upUnclampedY = if (isContinuousVerticalScroll && pages.size > 1) {
                                        rawDocY - interactOffsetY
                                    } else {
                                        rawDocY
                                    }
                                    val upDocX = if (isContinuousVerticalScroll && pages.size > 1) upUnclampedX.coerceIn(0f, interactW) else upUnclampedX
                                    val upDocY = if (isContinuousVerticalScroll && pages.size > 1) upUnclampedY.coerceIn(0f, interactH) else upUnclampedY

                                    if (isDraggingReadingGuide) {
                                        isDraggingReadingGuide = false
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingRuler) {
                                        isDraggingRuler = false
                                        rulerDragStart = null
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }

                                    if (activeImageHandle != ImageHandleType.NONE) {
                                        activeImageHandle = ImageHandleType.NONE
                                        imageDragStartPoint = null
                                        initialImageSnapshot = null
                                        if (interactPid != null) {
                                            commitPageImages(interactPid, getImages(interactPid))
                                        }
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingText) {
                                        isDraggingText = false
                                        textDragStartPoint = null
                                        initialTextPos = null
                                        if (interactPid != null) {
                                            commitPageTexts(interactPid, getTexts(interactPid))
                                        }
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }

                                    if (isDraggingLassoSelection) {
                                        isDraggingLassoSelection = false
                                        lassoDragLastPoint = null
                                        if (interactPid != null) {
                                            commitPageStrokes(interactPid, getStrokes(interactPid))
                                        }
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }

                                    val screenTravel = hypot(touchX - touchDownScreenX, touchY - touchDownScreenY)
                                    val isTap = screenTravel < 28f && (System.currentTimeMillis() - touchDownTime) < 450L

                                    // Re-select any inserted image on single tap (works in ANY tool mode & even after cropping)
                                    if (isTap && potentialTappedImageId != null) {
                                        inkEngine.cancelStroke()
                                        activeStrokeState = null
                                        activeStrokePageId = null
                                        activeLaserTrail = null
                                        selectedImageId = potentialTappedImageId
                                        selectedTextId = null
                                        isCroppingImage = false
                                        potentialTappedImageId = null
                                        activeInteractionPageId = null
                                        return@pointerInteropFilter true
                                    }
                                    potentialTappedImageId = null

                                    // Deselect image/crop if tapped on blank canvas
                                    if (isTap && effectiveTool != ToolType.TEXT) {
                                        val clickedOnImg = getImages(interactPid).any { img ->
                                            isPointInsideImage(upUnclampedX, upUnclampedY, img, pad = 10f / totalScale)
                                        }
                                        if (!clickedOnImg && (selectedImageId != null || isCroppingImage)) {
                                            selectedImageId = null
                                            isCroppingImage = false
                                        }
                                    }

                                    when (effectiveTool) {
                                        ToolType.LASER -> {
                                            activeLaserTrail?.let { finished ->
                                                if (finished.points.size >= 2) {
                                                    laserTrails = (laserTrails + finished).takeLast(12)
                                                }
                                            }
                                            activeLaserTrail = null
                                        }
                                        ToolType.ERASER_STROKE, ToolType.ERASER_PIXEL -> {
                                            if (interactPid != null) {
                                                commitPageStrokes(interactPid, getStrokes(interactPid))
                                            }
                                        }
                                        ToolType.LASSO -> {
                                            if (isLassoActive) {
                                                selectedStrokes = inkEngine.selectWithLasso(lassoPoints, getStrokes(interactPid))
                                                isLassoActive = false
                                            }
                                        }
                                        ToolType.SHAPE -> {
                                            shapeStartPoint?.let { start ->
                                                if (hypot(upDocX - start.first, upDocY - start.second) > 6f) {
                                                    val createdStroke = ShapeFactory.createPresetShape(
                                                        type = selectedPresetShape,
                                                        startX = start.first,
                                                        startY = start.second,
                                                        endX = upDocX,
                                                        endY = upDocY,
                                                        color = activeColorState,
                                                        width = activeWidthState,
                                                        opacity = inkEngine.currentOpacity
                                                    )
                                                    inkEngine.recordAddedStroke(createdStroke)
                                                    if (interactPid != null) {
                                                        commitPageStrokes(interactPid, getStrokes(interactPid) + createdStroke)
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
                                            val strokePid = activeStrokePageId ?: interactPid
                                            activeStrokePageId = null

                                            if (completed != null) {
                                                if (isShapeSnapEnabled) {
                                                    val recognized = ShapeRecognizer.recognize(completed)
                                                    if (recognized.type != RecognizedShapeType.NONE) {
                                                        completed = recognized.snappedStroke
                                                        inkEngine.replaceLastAddedStroke(completed)
                                                    }
                                                }

                                                if (strokePid != null) {
                                                    commitPageStrokes(strokePid, getStrokes(strokePid) + completed)
                                                }
                                            }
                                        }
                                    }
                                    activeInteractionPageId = null
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
                        val animTick = laserRenderTick
                        val nowLaserMs = System.currentTimeMillis()

                        val pagesToRender = if (isContinuousVerticalScroll && pages.isNotEmpty()) {
                            val minIdx = (currentPageIndex - 2).coerceAtLeast(0)
                            val maxIdx = (currentPageIndex + 2).coerceAtMost(pages.size - 1)
                            (minIdx..maxIdx).toList()
                        } else if (pages.isNotEmpty()) {
                            listOf(currentPageIndex.coerceIn(0, pages.lastIndex))
                        } else {
                            emptyList()
                        }

                        for (idx in pagesToRender) {
                            val p = pages[idx]
                            val pW = p.width
                            val pH = p.height
                            val yOffset = getPageYOffset(idx)
                            val isCurrentRenderPage = p.id == currentPageId

                            // 1. Page Surface Background & Subtle Border
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(0f, yOffset),
                                size = Size(pW, pH)
                            )
                            drawRect(
                                color = Color(0xFFCBD5E1),
                                topLeft = Offset(0f, yOffset),
                                size = Size(pW, pH),
                                style = Stroke(width = 1f)
                            )

                            // 2. Render PDF Page Bitmap or Paper Template
                            val pdfIdx = p.pdfPageIndex ?: (if (document?.type == DocumentType.PDF) p.index else null)
                            val pBmp = if (pdfIdx != null) (pdfBitmaps[pdfIdx] ?: (if (idx == currentPageIndex) pdfBitmap else null)) else null

                            if (pBmp != null) {
                                drawImage(
                                    image = pBmp.asImageBitmap(),
                                    dstOffset = IntOffset(0, yOffset.toInt()),
                                    dstSize = IntSize(pW.toInt().coerceAtLeast(1), pH.toInt().coerceAtLeast(1))
                                )
                            } else {
                                drawContext.canvas.save()
                                drawContext.canvas.translate(0f, yOffset)
                                TemplateRenderer.drawTemplate(this, p.template, pW, pH)
                                drawContext.canvas.restore()
                            }

                            // Per-page Annotations (Images, Selection/Crop UI, Text Notes, Strokes, Lasso, Laser)
                            drawContext.canvas.save()
                            drawContext.canvas.translate(0f, yOffset)

                            // 3. Draw Inserted Images for THIS page
                            val pImages = getImages(p.id)
                            for (img in pImages.sortedBy { it.zIndex }) {
                                val bmp = imageBitmaps[img.filePath]
                                if (bmp != null) {
                                    if (isCroppingImage && isCurrentRenderPage && img.id == selectedImageId) {
                                        // Interactive Crop Preview: Full uncropped photo ghost + darkened mask + crisp live crop window + 8 handles
                                        val frame = computeUncroppedFrame(img)
                                        drawContext.canvas.save()
                                        drawContext.canvas.translate(frame.centerX, frame.centerY)
                                        if (img.rotationDegrees != 0f) {
                                            drawContext.canvas.rotate(img.rotationDegrees)
                                        }
                                        val sx = if (img.isFlippedHorizontal) -1f else 1f
                                        val sy = if (img.isFlippedVertical) -1f else 1f
                                        if (sx != 1f || sy != 1f) {
                                            drawContext.canvas.scale(sx, sy)
                                        }
                                        drawContext.canvas.translate(-frame.centerX, -frame.centerY)

                                        // Full uncropped reference (dimmed)
                                        drawImage(
                                            image = bmp.asImageBitmap(),
                                            srcOffset = IntOffset.Zero,
                                            srcSize = IntSize(bmp.width, bmp.height),
                                            dstOffset = IntOffset(frame.fullX.roundToInt(), frame.fullY.roundToInt()),
                                            dstSize = IntSize(frame.fullW.roundToInt().coerceAtLeast(1), frame.fullH.roundToInt().coerceAtLeast(1)),
                                            alpha = 0.35f
                                        )

                                        val cropL = frame.fullX + liveCropLeft * frame.fullW
                                        val cropR = frame.fullX + liveCropRight * frame.fullW
                                        val cropT = frame.fullY + liveCropTop * frame.fullH
                                        val cropB = frame.fullY + liveCropBottom * frame.fullH

                                        // Dark scrim outside crop box inside uncropped frame
                                        drawRect(Color(0x66000000), topLeft = Offset(frame.fullX, frame.fullY), size = Size(frame.fullW, (cropT - frame.fullY).coerceAtLeast(0f)))
                                        drawRect(Color(0x66000000), topLeft = Offset(frame.fullX, cropB), size = Size(frame.fullW, (frame.fullY + frame.fullH - cropB).coerceAtLeast(0f)))
                                        drawRect(Color(0x66000000), topLeft = Offset(frame.fullX, cropT), size = Size((cropL - frame.fullX).coerceAtLeast(0f), (cropB - cropT).coerceAtLeast(0f)))
                                        drawRect(Color(0x66000000), topLeft = Offset(cropR, cropT), size = Size((frame.fullX + frame.fullW - cropR).coerceAtLeast(0f), (cropB - cropT).coerceAtLeast(0f)))

                                        // Sharp cropped preview inside crop region
                                        val srcL = (liveCropLeft.coerceIn(0f, 0.95f) * bmp.width).roundToInt()
                                        val srcT = (liveCropTop.coerceIn(0f, 0.95f) * bmp.height).roundToInt()
                                        val srcR = (liveCropRight.coerceIn(liveCropLeft + 0.05f, 1f) * bmp.width).roundToInt()
                                        val srcB = (liveCropBottom.coerceIn(liveCropTop + 0.05f, 1f) * bmp.height).roundToInt()
                                        drawImage(
                                            image = bmp.asImageBitmap(),
                                            srcOffset = IntOffset(srcL, srcT),
                                            srcSize = IntSize((srcR - srcL).coerceAtLeast(1), (srcB - srcT).coerceAtLeast(1)),
                                            dstOffset = IntOffset(cropL.roundToInt(), cropT.roundToInt()),
                                            dstSize = IntSize((cropR - cropL).roundToInt().coerceAtLeast(1), (cropB - cropT).roundToInt().coerceAtLeast(1)),
                                            alpha = img.opacity
                                        )

                                        // Crop border & 3x3 rule-of-thirds grid
                                        val cropLineStroke = 2f / totalScale
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset(cropL, cropT),
                                            size = Size(cropR - cropL, cropB - cropT),
                                            style = Stroke(width = cropLineStroke)
                                        )
                                        val thirdW = (cropR - cropL) / 3f
                                        val thirdH = (cropB - cropT) / 3f
                                        for (gridIdx in 1..2) {
                                            drawLine(Color.White.copy(alpha = 0.45f), Offset(cropL + thirdW * gridIdx, cropT), Offset(cropL + thirdW * gridIdx, cropB), cropLineStroke * 0.55f)
                                            drawLine(Color.White.copy(alpha = 0.45f), Offset(cropL, cropT + thirdH * gridIdx), Offset(cropR, cropT + thirdH * gridIdx), cropLineStroke * 0.55f)
                                        }

                                        // 4 L-shaped Corner Brackets
                                        val arm = min(18f / totalScale, min((cropR - cropL) * 0.3f, (cropB - cropT) * 0.3f))
                                        val cornerStroke = 3.5f / totalScale
                                        val cornerColor = Color(0xFF38BDF8)
                                        drawLine(cornerColor, Offset(cropL, cropT), Offset(cropL + arm, cropT), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropL, cropT), Offset(cropL, cropT + arm), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropR, cropT), Offset(cropR - arm, cropT), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropR, cropT), Offset(cropR, cropT + arm), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropL, cropB), Offset(cropL + arm, cropB), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropL, cropB), Offset(cropL, cropB - arm), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropR, cropB), Offset(cropR - arm, cropB), strokeWidth = cornerStroke, cap = StrokeCap.Round)
                                        drawLine(cornerColor, Offset(cropR, cropB), Offset(cropR, cropB - arm), strokeWidth = cornerStroke, cap = StrokeCap.Round)

                                        // 4 Edge Midpoint Handles
                                        val midX = (cropL + cropR) / 2f
                                        val midY = (cropT + cropB) / 2f
                                        val edgeR = 8f / totalScale
                                        listOf(
                                            Offset(cropL, midY), Offset(cropR, midY),
                                            Offset(midX, cropT), Offset(midX, cropB)
                                        ).forEach { hc ->
                                            drawCircle(Color.White, edgeR, hc)
                                            drawCircle(Color(0xFF2563EB), edgeR, hc, style = Stroke(2f / totalScale))
                                        }

                                        drawContext.canvas.restore()
                                    } else {
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
                            }

                            // 4. Selected Image Selection Box & Handles (rotated with image when not cropping)
                            if (isCurrentRenderPage && !isCroppingImage) {
                                pImages.find { it.id == selectedImageId }?.let { img ->
                                    drawContext.canvas.save()
                                    val cx = img.x + img.width / 2f
                                    val cy = img.y + img.height / 2f
                                    if (img.rotationDegrees != 0f) {
                                        drawContext.canvas.translate(cx, cy)
                                        drawContext.canvas.rotate(img.rotationDegrees)
                                        drawContext.canvas.translate(-cx, -cy)
                                    }

                                    drawRect(
                                        color = Color(0xFF2563EB),
                                        topLeft = Offset(img.x, img.y),
                                        size = Size(img.width, img.height),
                                        style = Stroke(
                                            width = 2f / totalScale,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / totalScale, 8f / totalScale), 0f)
                                        )
                                    )

                                    val handleR = 7f / totalScale
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
                                    drawContext.canvas.restore()
                                }
                            }

                            // 5. Placed Page Text Objects for THIS page
                            val pTexts = getTexts(p.id)
                            for (txt in pTexts.sortedBy { it.zIndex }) {
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
                                for ((lineIdx, line) in lines.withIndex()) {
                                    drawContext.canvas.nativeCanvas.drawText(
                                        line,
                                        txt.x,
                                        txt.y + txt.fontSizeSp + lineIdx * lineHeight,
                                        textPaint
                                    )
                                }

                                if (isCurrentRenderPage && txt.id == selectedTextId) {
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

                            // 6. Committed Vector Strokes for THIS page
                            val pStrokes = getStrokes(p.id)
                            for (stroke in pStrokes) {
                                if (stroke.points.size < 2) continue
                                val isSelected = isCurrentRenderPage && selectedStrokes.contains(stroke.id)
                                val isAudioActive = isAudioPlaying && stroke.audioTimestampMs != null &&
                                        abs(stroke.audioTimestampMs - audioPlayPositionMs) < 1500

                                drawStyledInkStroke(
                                    stroke = stroke,
                                    isSelected = isSelected,
                                    isAudioActive = isAudioActive
                                )
                            }

                            // 7. Live Inking Stroke (on its origin page)
                            if (p.id == (activeStrokePageId ?: currentPageId)) {
                                activeStrokeState?.let { active ->
                                    if (active.points.size >= 2) {
                                        drawStyledInkStroke(
                                            stroke = active,
                                            isSelected = false,
                                            isAudioActive = false
                                        )
                                    }
                                }
                            }

                            // 8. Live Preset Shape Preview & Lasso UI (on current active page)
                            if (isCurrentRenderPage) {
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
                                    inkEngine.getSelectionBounds(pStrokes)?.let { b ->
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
                            }

                            // 9. Glowing Fading Laser Reading Trails for THIS page (fades from behind as you underline text)
                            @Suppress("UNUSED_VARIABLE")
                            val triggerFrame = animTick
                            val pageLasers = laserTrails.filter { it.pageId == p.id } +
                                    listOfNotNull(activeLaserTrail?.takeIf { it.pageId == p.id })
                            val laserScaleDiv = totalScale.coerceAtLeast(0.75f)
                            for (trail in pageLasers) {
                                val pts = trail.points
                                if (pts.size < 2) continue
                                val baseCol = Color(trail.color.toInt())
                                for (i in 0 until pts.size - 1) {
                                    val ptA = pts[i]
                                    val ptB = pts[i + 1]
                                    val ageMs = (nowLaserMs - ptB.timestampMs).coerceAtLeast(0L)
                                    val life = (1f - ageMs / 1700f).coerceIn(0f, 1f)
                                    if (life <= 0.01f) continue
                                    val fade = life.pow(1.25f)

                                    drawLine(
                                        color = baseCol.copy(alpha = fade * 0.28f),
                                        start = Offset(ptA.x, ptA.y),
                                        end = Offset(ptB.x, ptB.y),
                                        strokeWidth = (11f / laserScaleDiv) * (0.4f + 0.6f * life),
                                        cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        color = baseCol.copy(alpha = fade * 0.92f),
                                        start = Offset(ptA.x, ptA.y),
                                        end = Offset(ptB.x, ptB.y),
                                        strokeWidth = (3.4f / laserScaleDiv) * (0.5f + 0.5f * life),
                                        cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        color = Color.White.copy(alpha = fade * 0.85f),
                                        start = Offset(ptA.x, ptA.y),
                                        end = Offset(ptB.x, ptB.y),
                                        strokeWidth = (1.3f / laserScaleDiv) * life,
                                        cap = StrokeCap.Round
                                    )
                                }
                                val tip = pts.last()
                                val tipAge = (nowLaserMs - tip.timestampMs).coerceAtLeast(0L)
                                val tipLife = (1f - tipAge / 500f).coerceIn(0f, 1f)
                                if (tipLife > 0.05f) {
                                    drawCircle(
                                        color = baseCol.copy(alpha = tipLife * 0.35f),
                                        radius = 9f / laserScaleDiv,
                                        center = Offset(tip.x, tip.y)
                                    )
                                    drawCircle(
                                        color = Color.White.copy(alpha = tipLife * 0.95f),
                                        radius = 2.6f / laserScaleDiv,
                                        center = Offset(tip.x, tip.y)
                                    )
                                }
                            }

                            drawContext.canvas.restore()

                            // Inter-page dashed separator in continuous scroll mode
                            if (isContinuousVerticalScroll && idx < pages.lastIndex) {
                                drawLine(
                                    color = Color(0xFF94A3B8),
                                    start = Offset(0f, yOffset + pH + pageGapPx / 2f),
                                    end = Offset(pW, yOffset + pH + pageGapPx / 2f),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                )
                            }
                        }

                        // 10. Interactive Digital Ruler Overlay (drawn relative to active page viewport)
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

                        // 11. PDF Reading Guide Overlay (Red Border with White Center, fading trail)
                        if (showReadingGuide) {
                            val rY = readingGuideY.coerceIn(6f, pageH - 6f)
                            val guideHeight = 14f / totalScale
                            val borderThickness = 2.5f / totalScale

                            readingGuideTrail.forEachIndexed { idx, trailY ->
                                val trailAlpha = (0.55f - idx * 0.08f).coerceAtLeast(0f)
                                if (trailAlpha <= 0f) return@forEachIndexed
                                val ty = trailY.coerceIn(6f, pageH - 6f)
                                drawRect(
                                    color = Color(0xFFDC2626).copy(alpha = trailAlpha * 0.7f),
                                    topLeft = Offset(0f, ty - guideHeight / 2f),
                                    size = Size(pageW, borderThickness)
                                )
                                drawRect(
                                    color = Color.White.copy(alpha = trailAlpha * 0.5f),
                                    topLeft = Offset(0f, ty - guideHeight / 2f + borderThickness),
                                    size = Size(pageW, guideHeight - borderThickness * 2)
                                )
                                drawRect(
                                    color = Color(0xFFDC2626).copy(alpha = trailAlpha * 0.7f),
                                    topLeft = Offset(0f, ty + guideHeight / 2f - borderThickness),
                                    size = Size(pageW, borderThickness)
                                )
                            }

                            drawRect(
                                color = Color(0xFFDC2626),
                                topLeft = Offset(0f, rY - guideHeight / 2f),
                                size = Size(pageW, borderThickness)
                            )
                            drawRect(
                                color = Color.White.copy(alpha = 0.88f),
                                topLeft = Offset(0f, rY - guideHeight / 2f + borderThickness),
                                size = Size(pageW, guideHeight - borderThickness * 2)
                            )
                            drawRect(
                                color = Color(0xFFDC2626),
                                topLeft = Offset(0f, rY + guideHeight / 2f - borderThickness),
                                size = Size(pageW, borderThickness)
                            )

                            val tabW = 28f / totalScale
                            val tabH = 16f / totalScale
                            drawRoundRect(
                                color = Color(0xFFDC2626),
                                topLeft = Offset(2f, rY - tabH / 2f),
                                size = Size(tabW, tabH),
                                cornerRadius = CornerRadius(tabH / 2f, tabH / 2f)
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(4f, rY - (tabH - 4f) / 2f),
                                size = Size(tabW - 4f, tabH - 4f),
                                cornerRadius = CornerRadius((tabH - 4f) / 2f, (tabH - 4f) / 2f)
                            )
                            drawRoundRect(
                                color = Color(0xFFDC2626),
                                topLeft = Offset(pageW - tabW - 2f, rY - tabH / 2f),
                                size = Size(tabW, tabH),
                                cornerRadius = CornerRadius(tabH / 2f, tabH / 2f)
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(pageW - tabW, rY - (tabH - 4f) / 2f),
                                size = Size(tabW - 4f, tabH - 4f),
                                cornerRadius = CornerRadius((tabH - 4f) / 2f, (tabH - 4f) / 2f)
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
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626))
                            )
                            Text("Guide", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                            // Fine step up (1 line)
                            IconButton(
                                onClick = {
                                    readingGuideTrail = (listOf(readingGuideY) + readingGuideTrail).take(7)
                                    readingGuideY = (readingGuideY - 18f).coerceAtLeast(10f)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Line Up", modifier = Modifier.size(16.dp))
                            }
                            // Coarse step up (~1 paragraph)
                            IconButton(
                                onClick = {
                                    readingGuideTrail = (listOf(readingGuideY) + readingGuideTrail).take(7)
                                    readingGuideY = (readingGuideY - 50f).coerceAtLeast(10f)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.KeyboardDoubleArrowUp, contentDescription = "Para Up", modifier = Modifier.size(16.dp))
                            }
                            // Fine step down
                            IconButton(
                                onClick = {
                                    readingGuideTrail = (listOf(readingGuideY) + readingGuideTrail).take(7)
                                    readingGuideY = (readingGuideY + 18f).coerceAtMost(pageH - 10f)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Line Down", modifier = Modifier.size(16.dp))
                            }
                            // Coarse step down
                            IconButton(
                                onClick = {
                                    readingGuideTrail = (listOf(readingGuideY) + readingGuideTrail).take(7)
                                    readingGuideY = (readingGuideY + 50f).coerceAtMost(pageH - 10f)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = "Para Down", modifier = Modifier.size(16.dp))
                            }
                            // Clear fading trail
                            if (readingGuideTrail.isNotEmpty()) {
                                IconButton(
                                    onClick = { readingGuideTrail = emptyList() },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Default.ClearAll, contentDescription = "Clear Trail", modifier = Modifier.size(16.dp), tint = Color(0xFFDC2626).copy(alpha = 0.7f))
                                }
                            }
                            IconButton(
                                onClick = {
                                    showReadingGuide = false
                                    readingGuideTrail = emptyList()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Guide", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // --- FLOATING IMAGE CONTEXTUAL TOOLBAR ---
                selectedImage?.let { img ->
                    val imgPid = img.pageId.ifEmpty { currentPageId ?: "" }
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

                            // Interactive Canvas Crop (4 corners + 4 edges on full uncropped preview without aspect distortion)
                            FilledTonalButton(
                                onClick = {
                                    if (isCroppingImage) {
                                        val cropped = applyImageCrop(img, liveCropLeft, liveCropTop, liveCropRight, liveCropBottom)
                                        val updated = getImages(imgPid).map { pi ->
                                            if (pi.id == img.id) cropped else pi
                                        }
                                        commitPageImages(imgPid, updated)
                                        selectedImageId = img.id
                                        isCroppingImage = false
                                    } else {
                                        liveCropLeft = img.cropLeft
                                        liveCropTop = img.cropTop
                                        liveCropRight = img.cropRight
                                        liveCropBottom = img.cropBottom
                                        isCroppingImage = true
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp),
                                colors = if (isCroppingImage) ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else ButtonDefaults.filledTonalButtonColors()
                            ) {
                                Icon(if (isCroppingImage) Icons.Default.Check else Icons.Default.Crop, contentDescription = "Crop", modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isCroppingImage) "Apply Crop" else "Crop", fontSize = 11.sp)
                            }

                            // Reset / Cancel Crop
                            if (isCroppingImage) {
                                TextButton(
                                    onClick = {
                                        liveCropLeft = 0f; liveCropTop = 0f
                                        liveCropRight = 1f; liveCropBottom = 1f
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("Reset", fontSize = 11.sp) }

                                TextButton(
                                    onClick = { isCroppingImage = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("Cancel", fontSize = 11.sp) }
                            }

                            // Opacity slider (always visible in toolbar when image selected)
                            if (!isCroppingImage) {
                                Text("α", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                                Slider(
                                    value = img.opacity,
                                    onValueChange = { op ->
                                        val updated = getImages(imgPid).map { pi ->
                                            if (pi.id == img.id) pi.copy(opacity = op) else pi
                                        }
                                        commitPageImages(imgPid, updated, persistNow = false)
                                    },
                                    onValueChangeFinished = {
                                        commitPageImages(imgPid, getImages(imgPid), persistNow = true)
                                    },
                                    valueRange = 0.15f..1f,
                                    modifier = Modifier.width(80.dp)
                                )
                            }

                            // Rotate 90 deg clockwise
                            IconButton(onClick = {
                                val updated = getImages(imgPid).map {
                                    if (it.id == img.id) it.copy(rotationDegrees = (it.rotationDegrees + 90f) % 360f) else it
                                }
                                commitPageImages(imgPid, updated)
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90°", modifier = Modifier.size(18.dp))
                            }

                            // Flip Horizontal
                            IconButton(onClick = {
                                val updated = getImages(imgPid).map {
                                    if (it.id == img.id) it.copy(isFlippedHorizontal = !it.isFlippedHorizontal) else it
                                }
                                commitPageImages(imgPid, updated)
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Flip, contentDescription = "Flip Horizontal", modifier = Modifier.size(18.dp))
                            }

                            // Flip Vertical
                            IconButton(onClick = {
                                val updated = getImages(imgPid).map {
                                    if (it.id == img.id) it.copy(isFlippedVertical = !it.isFlippedVertical) else it
                                }
                                commitPageImages(imgPid, updated)
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Flip Vertical", modifier = Modifier.size(18.dp))
                            }

                            // Bring to Front
                            IconButton(onClick = {
                                val curImgs = getImages(imgPid)
                                val maxZ = (curImgs.maxOfOrNull { it.zIndex } ?: 0) + 1
                                val updated = curImgs.map {
                                    if (it.id == img.id) it.copy(zIndex = maxZ) else it
                                }
                                commitPageImages(imgPid, updated)
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.FlipToFront, contentDescription = "Bring to Front", modifier = Modifier.size(18.dp))
                            }

                            // Lock / Unlock
                            IconButton(onClick = {
                                val updated = getImages(imgPid).map {
                                    if (it.id == img.id) it.copy(isLocked = !it.isLocked) else it
                                }
                                commitPageImages(imgPid, updated)
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
                                val curImgs = getImages(imgPid)
                                val copy = img.copy(
                                    id = UUID.randomUUID().toString(),
                                    x = (img.x + 24f).coerceAtMost((pageW - img.width).coerceAtLeast(24f)),
                                    y = (img.y + 24f).coerceAtMost((pageH - img.height).coerceAtLeast(24f)),
                                    zIndex = (curImgs.maxOfOrNull { it.zIndex } ?: 0) + 1
                                )
                                commitPageImages(imgPid, curImgs + copy)
                                selectedImageId = copy.id
                                isCroppingImage = false
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(18.dp))
                            }

                            // Delete Image
                            IconButton(onClick = {
                                val updated = getImages(imgPid).filter { it.id != img.id }
                                commitPageImages(imgPid, updated)
                                selectedImageId = null
                                isCroppingImage = false
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }

                            IconButton(onClick = {
                                selectedImageId = null
                                isCroppingImage = false
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Deselect", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // --- FLOATING TEXT OBJECT CONTEXTUAL TOOLBAR ---
                selectedText?.let { txt ->
                    val txtPid = txt.pageId.ifEmpty { currentPageId ?: "" }
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
                                val updated = getTexts(txtPid).map {
                                    if (it.id == txt.id) it.copy(fontSizeSp = (it.fontSizeSp - 2f).coerceAtLeast(10f)) else it
                                }
                                commitPageTexts(txtPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Remove, contentDescription = "Smaller Font", modifier = Modifier.size(16.dp))
                            }
                            Text("${txt.fontSizeSp.toInt()}sp", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = {
                                val updated = getTexts(txtPid).map {
                                    if (it.id == txt.id) it.copy(fontSizeSp = (it.fontSizeSp + 2f).coerceAtMost(48f)) else it
                                }
                                commitPageTexts(txtPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Larger Font", modifier = Modifier.size(16.dp))
                            }

                            // Bold Toggle
                            FilterChip(
                                selected = txt.isBold,
                                onClick = {
                                    val updated = getTexts(txtPid).map { if (it.id == txt.id) it.copy(isBold = !it.isBold) else it }
                                    commitPageTexts(txtPid, updated)
                                },
                                label = { Text("B", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                            )

                            // Italic Toggle
                            FilterChip(
                                selected = txt.isItalic,
                                onClick = {
                                    val updated = getTexts(txtPid).map { if (it.id == txt.id) it.copy(isItalic = !it.isItalic) else it }
                                    commitPageTexts(txtPid, updated)
                                },
                                label = { Text("I", fontStyle = FontStyle.Italic, fontSize = 11.sp) }
                            )

                            // Background Note Color Cycle
                            IconButton(onClick = {
                                val bgCycle = listOf<Long?>(0x80FEF08A, 0x80BAE6FD, 0x80BBF7D0, 0x80FBCFE8, null)
                                val nextIdx = (bgCycle.indexOf(txt.backgroundColor) + 1) % bgCycle.size
                                val updated = getTexts(txtPid).map { if (it.id == txt.id) it.copy(backgroundColor = bgCycle[nextIdx]) else it }
                                commitPageTexts(txtPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.FormatColorFill, contentDescription = "Note Background", modifier = Modifier.size(16.dp))
                            }

                            // Delete Text
                            IconButton(onClick = {
                                val updated = getTexts(txtPid).filter { it.id != txt.id }
                                commitPageTexts(txtPid, updated)
                                selectedTextId = null
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
                    val lassoPid = currentPageId ?: ""
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
                                text = "${selectedStrokes.size} selected (drag box to move)",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )

                            // Scale Down
                            IconButton(onClick = {
                                val updated = inkEngine.scaleSelectedStrokes(0.85f, getStrokes(lassoPid))
                                if (lassoPid.isNotEmpty()) commitPageStrokes(lassoPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ZoomOutMap, contentDescription = "Shrink Selection", modifier = Modifier.size(16.dp))
                            }

                            // Scale Up
                            IconButton(onClick = {
                                val updated = inkEngine.scaleSelectedStrokes(1.18f, getStrokes(lassoPid))
                                if (lassoPid.isNotEmpty()) commitPageStrokes(lassoPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.OpenInFull, contentDescription = "Enlarge Selection", modifier = Modifier.size(16.dp))
                            }

                            // Recolor to active color
                            IconButton(onClick = {
                                val updated = inkEngine.recolorSelectedStrokes(activeColorState, getStrokes(lassoPid))
                                if (lassoPid.isNotEmpty()) commitPageStrokes(lassoPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Palette, contentDescription = "Recolor Selection", tint = Color(activeColorState.toInt()), modifier = Modifier.size(18.dp))
                            }

                            // Duplicate Selection
                            IconButton(onClick = {
                                val updated = inkEngine.duplicateSelectedStrokes(24f, 24f, getStrokes(lassoPid))
                                selectedStrokes = inkEngine.selectedStrokeIds.toSet()
                                if (lassoPid.isNotEmpty()) commitPageStrokes(lassoPid, updated)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Selection", modifier = Modifier.size(16.dp))
                            }

                            // Delete Selection
                            IconButton(onClick = {
                                val updated = inkEngine.deleteSelectedStrokes(getStrokes(lassoPid))
                                selectedStrokes = emptySet()
                                if (lassoPid.isNotEmpty()) commitPageStrokes(lassoPid, updated)
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
                                val targetScale = (availableW - margin * 2) / (pageW * baseScale)
                                zoomScale = targetScale.coerceIn(0.4f, 5.0f)
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "Fit Width", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Laser Reading Pen Quick Options Bar
                if (activeToolState == ToolType.LASER) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 94.dp)
                            .shadow(6.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Color(laserColorState.toInt()).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Flare, contentDescription = null, tint = Color(laserColorState.toInt()), modifier = Modifier.size(16.dp))
                            Text("Laser:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(laserColorState.toInt()))
                            listOf(
                                0xFFEF4444L to "Red",
                                0xFF06B6D4L to "Cyan",
                                0xFF22C55EL to "Green",
                                0xFFEC4899L to "Pink",
                                0xFFF59E0BL to "Amber"
                            ).forEach { (c, label) ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.toInt()))
                                        .border(
                                            width = if (laserColorState == c) 3.dp else 1.dp,
                                            color = if (laserColorState == c) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.4f),
                                            shape = CircleShape
                                        )
                                        .clickable { laserColorState = c },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (laserColorState == c) {
                                        Icon(Icons.Default.Check, contentDescription = label, modifier = Modifier.size(12.dp), tint = Color.White)
                                    }
                                }
                            }
                            FilterChip(
                                selected = isLaserUnderlineSnap,
                                onClick = { isLaserUnderlineSnap = !isLaserUnderlineSnap },
                                label = { Text("Underline Snap", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = showReadingGuide,
                                onClick = { showReadingGuide = !showReadingGuide },
                                label = { Text("Guide Line", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // Highlighter Quick Color Bar (shown when Highlighter is the active tool)
                if (activeToolState == ToolType.HIGHLIGHTER) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 94.dp)
                            .shadow(6.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Highlight:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(
                                0xFFFBBF24L to "Yellow",
                                0xFF86EFACL to "Green",
                                0xFFF9A8D4L to "Pink",
                                0xFF93C5FDL to "Blue",
                                0xFFFDBA74L to "Orange"
                            ).forEach { (c, label) ->
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.toInt()))
                                        .border(
                                            width = if (activeColorState == c) 3.dp else 1.dp,
                                            color = if (activeColorState == c) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                            shape = CircleShape
                                        )
                                        .clickable { selectColor(c) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (activeColorState == c) {
                                        Icon(Icons.Default.Check, contentDescription = label, modifier = Modifier.size(14.dp), tint = Color.DarkGray)
                                    }
                                }
                            }
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
                        // Inking & Reading Tools: Pen, Ballpoint, Pencil, Highlighter, Laser Reading Pen, Stroke/Pixel Erasers
                        listOf(
                            Triple(ToolType.PEN, Icons.Default.Edit, "Fountain Pen"),
                            Triple(ToolType.BALLPOINT, Icons.Default.DriveFileRenameOutline, "Ballpoint"),
                            Triple(ToolType.PENCIL, Icons.Default.Create, "Pencil"),
                            Triple(ToolType.HIGHLIGHTER, Icons.Default.BorderColor, "Highlighter"),
                            Triple(ToolType.LASER, Icons.Default.Flare, "Laser Reading Pen"),
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
                                    .background(
                                        when {
                                            isSelected && tool == ToolType.LASER -> Color(laserColorState.toInt()).copy(alpha = 0.2f)
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        }
                                    )
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = when {
                                        isSelected && tool == ToolType.LASER -> Color(laserColorState.toInt())
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
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

                        // Insert Image from Device Gallery
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { launchImageGalleryPicker() }
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
                                val pid = currentPageId ?: return@IconButton
                                val undone = inkEngine.undo(getStrokes(pid))
                                commitPageStrokes(pid, undone)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            enabled = inkEngine.canRedo,
                            onClick = {
                                val pid = currentPageId ?: return@IconButton
                                val redone = inkEngine.redo(getStrokes(pid))
                                commitPageStrokes(pid, redone)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // --- IMAGE CROP DIALOG (now only Aspect-Ratio lock + presets; actual crop via canvas handles) ---
        // showImageCropDialog is kept for backward compat but no longer triggered; canvas crop replaces it.

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
                                        strokeProvider = { pageId -> pageStrokesMap[pageId] ?: repository.loadPageStrokes(pageId) },
                                        imageProvider = { pageId -> pageImagesMap[pageId] ?: repository.loadPageImages(pageId) },
                                        textProvider = { pageId -> pageTextsMap[pageId] ?: repository.loadPageTexts(pageId) },
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Page Manager (${pages.size} pages)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                                navigateToPage(currentPageIndex.coerceAtMost(pages.size - 2))
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
                                        navigateToPage(p.index)
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
                                navigateToPage(newIndex)
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
                                val pid = currentPage?.id ?: return@Button
                                val existing = editingTextObject
                                val curTexts = getTexts(pid)
                                val updated = if (existing != null) {
                                    curTexts.map { if (it.id == existing.id) it.copy(text = insertedTextContent.trim()) else it }
                                } else {
                                    val newObj = PageTextObject(
                                        id = UUID.randomUUID().toString(),
                                        pageId = pid,
                                        x = pendingTextInsertDocCoords.first,
                                        y = pendingTextInsertDocCoords.second,
                                        text = insertedTextContent.trim(),
                                        color = activeColorState,
                                        zIndex = (curTexts.maxOfOrNull { it.zIndex } ?: 0) + 1
                                    )
                                    selectedTextId = newObj.id
                                    curTexts + newObj
                                }
                                commitPageTexts(pid, updated)
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
                                        val pid = currentPage?.id ?: return@Button
                                        val curTexts = getTexts(pid)
                                        val newNote = PageTextObject(
                                            id = UUID.randomUUID().toString(),
                                            pageId = pid,
                                            x = 72f,
                                            y = (68f + curTexts.size * 38f).coerceAtMost((currentPage?.height ?: 842f) - 60f),
                                            text = quickStudyNoteInput.trim()
                                        )
                                        commitPageTexts(pid, curTexts + newNote)
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
        blendMode = BlendMode.SrcOver,
        style = Stroke(
            width = if (isHighlight) (stroke.width * 3.8f).coerceAtLeast(10.5f) else stroke.width,
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
