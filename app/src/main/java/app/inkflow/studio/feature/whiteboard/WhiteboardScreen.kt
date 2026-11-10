package app.inkflow.studio.feature.whiteboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.core.design.InkCanvasDark
import app.inkflow.studio.core.design.InkCanvasLight
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.domain.model.CanvasObject
import app.inkflow.studio.domain.model.CanvasObjectType
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun WhiteboardScreen(
    documentId: String,
    repository: DocumentRepository,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val document by repository.getDocument(documentId).collectAsState(initial = null)

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var objects by remember { mutableStateOf<List<CanvasObject>>(emptyList()) }
    var showAddStickyDialog by remember { mutableStateOf(false) }
    var stickyText by remember { mutableStateOf("") }

    // Load whiteboard objects
    LaunchedEffect(documentId) {
        val loaded = repository.loadWhiteboardObjects(documentId)
        objects = loaded
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
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = document?.title ?: "Whiteboard",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Infinite Canvas · 2D Spatial View",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    // Reset viewport to center
                                    panOffset = Offset.Zero
                                    zoomScale = 1.0f
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Center View", fontSize = 12.sp)
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
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(0.2f, 4.0f)
                            panOffset += pan
                        }
                    }
            ) {
                // Infinite Grid Background & Render Objects
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
                    // Infinite dot grid pattern
                    val gridSize = 40f
                    val dotColor = Color.LightGray.copy(alpha = 0.45f)
                    var x = -2000f
                    while (x <= 4000f) {
                        var y = -2000f
                        while (y <= 4000f) {
                            drawCircle(color = dotColor, radius = 1.5f, center = Offset(x, y))
                            y += gridSize
                        }
                        x += gridSize
                    }

                    // Render Canvas Objects (Sticky Notes, Rectangles, Circles)
                    for (obj in objects) {
                        when (obj.type) {
                            CanvasObjectType.STICKY_NOTE -> {
                                drawRect(
                                    color = Color(obj.color.toInt()),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height)
                                )
                                drawRect(
                                    color = Color(obj.strokeColor.toInt()).copy(alpha = 0.3f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    style = Stroke(width = 1.5f)
                                )
                            }
                            CanvasObjectType.SHAPE_RECTANGLE -> {
                                drawRect(
                                    color = Color(obj.color.toInt()).copy(alpha = 0.15f),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height)
                                )
                                drawRect(
                                    color = Color(obj.strokeColor.toInt()),
                                    topLeft = Offset(obj.x, obj.y),
                                    size = Size(obj.width, obj.height),
                                    style = Stroke(width = 2f)
                                )
                            }
                            CanvasObjectType.SHAPE_CIRCLE -> {
                                val radius = (obj.width / 2f)
                                drawCircle(
                                    color = Color(obj.color.toInt()).copy(alpha = 0.15f),
                                    radius = radius,
                                    center = Offset(obj.x + radius, obj.y + radius)
                                )
                                drawCircle(
                                    color = Color(obj.strokeColor.toInt()),
                                    radius = radius,
                                    center = Offset(obj.x + radius, obj.y + radius),
                                    style = Stroke(width = 2f)
                                )
                            }
                            else -> {}
                        }
                    }
                }

                // Floating Whiteboard Action Tool Dock
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .shadow(12.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showAddStickyDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.StickyNote2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sticky Note")
                        }

                        OutlinedButton(
                            onClick = {
                                val newRect = CanvasObject(
                                    id = UUID.randomUUID().toString(),
                                    type = CanvasObjectType.SHAPE_RECTANGLE,
                                    x = -panOffset.x / zoomScale + 100f,
                                    y = -panOffset.y / zoomScale + 100f,
                                    width = 180f,
                                    height = 120f,
                                    color = 0xFF3B82F6,
                                    strokeColor = 0xFF1D4ED8
                                )
                                objects = objects + newRect
                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CropSquare, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Box")
                        }

                        OutlinedButton(
                            onClick = {
                                val newCircle = CanvasObject(
                                    id = UUID.randomUUID().toString(),
                                    type = CanvasObjectType.SHAPE_CIRCLE,
                                    x = -panOffset.x / zoomScale + 100f,
                                    y = -panOffset.y / zoomScale + 100f,
                                    width = 140f,
                                    height = 140f,
                                    color = 0xFF10B981,
                                    strokeColor = 0xFF047857
                                )
                                objects = objects + newCircle
                                coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Circle")
                        }

                        IconButton(
                            onClick = {
                                if (objects.isNotEmpty()) {
                                    objects = objects.dropLast(1)
                                    coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Last Object")
                        }
                    }
                }
            }
        }

        // Add Sticky Note Dialog
        if (showAddStickyDialog) {
            AlertDialog(
                onDismissRequest = { showAddStickyDialog = false },
                title = { Text("Add Sticky Note") },
                text = {
                    OutlinedTextField(
                        value = stickyText,
                        onValueChange = { stickyText = it },
                        label = { Text("Note Content") },
                        placeholder = { Text("Type ideas, formulas, or reminders…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAddStickyDialog = false
                            val newSticky = CanvasObject(
                                id = UUID.randomUUID().toString(),
                                type = CanvasObjectType.STICKY_NOTE,
                                x = -panOffset.x / zoomScale + 120f,
                                y = -panOffset.y / zoomScale + 120f,
                                width = 160f,
                                height = 140f,
                                content = stickyText,
                                color = 0xFFFEF08A, // Soft yellow
                                strokeColor = 0xFFCA8A04
                            )
                            objects = objects + newSticky
                            coroutineScope.launch { repository.saveWhiteboardObjects(documentId, objects) }
                            stickyText = ""
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddStickyDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

