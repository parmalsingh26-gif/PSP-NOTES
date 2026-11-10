package app.inkflow.studio.feature.library

import android.net.Uri
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.core.design.*
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.domain.model.PageTemplate
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class LibraryFilter {
    ALL,
    NOTEBOOKS,
    PDFS,
    WHITEBOARDS,
    FAVORITES,
    TRASH
}

@Composable
fun LibraryScreen(
    repository: DocumentRepository,
    onOpenDocument: (Document) -> Unit,
    onOpenStudy: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog States
    var showCreateNotebookDialog by remember { mutableStateOf(false) }
    var showCreateWhiteboardDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Document?>(null) }
    var renameText by remember { mutableStateOf("") }

    // PDF Picker Launcher
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                val doc = repository.createPdfDocument(
                    title = "Imported Document",
                    uri = it,
                    pageCount = 1
                )
                onOpenDocument(doc)
            }
        }
    }

    // Reactive Document Lists
    val activeDocs by repository.getAllActiveDocuments().collectAsState(initial = emptyList())
    val favoriteDocs by repository.getFavoriteDocuments().collectAsState(initial = emptyList())
    val trashedDocs by repository.getTrashedDocuments().collectAsState(initial = emptyList())

    val filteredDocs = remember(activeDocs, favoriteDocs, trashedDocs, selectedFilter, searchQuery) {
        val baseList = when (selectedFilter) {
            LibraryFilter.ALL -> activeDocs
            LibraryFilter.NOTEBOOKS -> activeDocs.filter { it.type == DocumentType.NOTEBOOK }
            LibraryFilter.PDFS -> activeDocs.filter { it.type == DocumentType.PDF }
            LibraryFilter.WHITEBOARDS -> activeDocs.filter { it.type == DocumentType.WHITEBOARD }
            LibraryFilter.FAVORITES -> favoriteDocs
            LibraryFilter.TRASH -> trashedDocs
        }
        if (searchQuery.isBlank()) {
            baseList
        } else {
            baseList.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Navigation Rail for Tablet / Desktop Layout
        NavigationRail(
            modifier = Modifier.width(88.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "InkFlow Logo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            NavigationRailItem(
                selected = selectedFilter == LibraryFilter.ALL,
                onClick = { selectedFilter = LibraryFilter.ALL },
                icon = { Icon(Icons.Outlined.Folder, contentDescription = "All") },
                label = { Text("Library", fontSize = 11.sp) }
            )
            NavigationRailItem(
                selected = selectedFilter == LibraryFilter.FAVORITES,
                onClick = { selectedFilter = LibraryFilter.FAVORITES },
                icon = { Icon(Icons.Outlined.Star, contentDescription = "Favorites") },
                label = { Text("Starred", fontSize = 11.sp) }
            )
            NavigationRailItem(
                selected = selectedFilter == LibraryFilter.WHITEBOARDS,
                onClick = { selectedFilter = LibraryFilter.WHITEBOARDS },
                icon = { Icon(Icons.Outlined.Dashboard, contentDescription = "Boards") },
                label = { Text("Boards", fontSize = 11.sp) }
            )
            NavigationRailItem(
                selected = false,
                onClick = onOpenStudy,
                icon = { Icon(Icons.Outlined.School, contentDescription = "Study") },
                label = { Text("Study", fontSize = 11.sp) }
            )
            NavigationRailItem(
                selected = selectedFilter == LibraryFilter.TRASH,
                onClick = { selectedFilter = LibraryFilter.TRASH },
                icon = { Icon(Icons.Outlined.Delete, contentDescription = "Trash") },
                label = { Text("Trash", fontSize = 11.sp) }
            )
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        // Main Library Content Area
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "InkFlow Studio",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Offline-First Note & Study Workspace",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Action Buttons: New Notebook, Import PDF, Whiteboard
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import PDF")
                            }

                            OutlinedButton(
                                onClick = { showCreateWhiteboardDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Whiteboard")
                            }

                            Button(
                                onClick = { showCreateNotebookDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("New Notebook")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Search & Filter Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search by title or tag…") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        )

                        // Filter Chips
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedFilter == LibraryFilter.ALL,
                                onClick = { selectedFilter = LibraryFilter.ALL },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = selectedFilter == LibraryFilter.NOTEBOOKS,
                                onClick = { selectedFilter = LibraryFilter.NOTEBOOKS },
                                label = { Text("Notebooks") }
                            )
                            FilterChip(
                                selected = selectedFilter == LibraryFilter.PDFS,
                                onClick = { selectedFilter = LibraryFilter.PDFS },
                                label = { Text("PDFs") }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
            ) {
                if (filteredDocs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (selectedFilter == LibraryFilter.TRASH) Icons.Outlined.DeleteSweep else Icons.Outlined.NoteAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = if (selectedFilter == LibraryFilter.TRASH) "Trash is empty" else "No notes found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selectedFilter != LibraryFilter.TRASH) {
                                Button(
                                    onClick = { showCreateNotebookDialog = true },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Create Your First Notebook")
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 240.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredDocs, key = { it.id }) { doc ->
                            DocumentCard(
                                document = doc,
                                onClick = { onOpenDocument(doc) },
                                onToggleFavorite = {
                                    coroutineScope.launch {
                                        repository.setFavorite(doc.id, !doc.isFavorite)
                                    }
                                },
                                onRename = {
                                    renameText = doc.title
                                    showRenameDialog = doc
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        if (doc.isTrashed) {
                                            repository.permanentDelete(doc.id)
                                        } else {
                                            repository.moveToTrash(doc.id)
                                        }
                                    }
                                },
                                onRestore = {
                                    coroutineScope.launch {
                                        repository.restoreFromTrash(doc.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Notebook Dialog
    if (showCreateNotebookDialog) {
        var newTitle by remember { mutableStateOf("") }
        var selectedTemplate by remember { mutableStateOf(PageTemplate.RULED) }

        AlertDialog(
            onDismissRequest = { showCreateNotebookDialog = false },
            title = { Text("Create New Notebook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Notebook Title") },
                        placeholder = { Text("e.g. Physics 101, Project Ideas") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Paper Template:", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PageTemplate.values().forEach { tmpl ->
                            FilterChip(
                                selected = selectedTemplate == tmpl,
                                onClick = { selectedTemplate = tmpl },
                                label = { Text(tmpl.name.replace("_", " ").lowercase().capitalize(Locale.ROOT)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateNotebookDialog = false
                        coroutineScope.launch {
                            val doc = repository.createNotebook(newTitle, selectedTemplate)
                            onOpenDocument(doc)
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateNotebookDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create Whiteboard Dialog
    if (showCreateWhiteboardDialog) {
        var boardTitle by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateWhiteboardDialog = false },
            title = { Text("Create Infinite Whiteboard") },
            text = {
                OutlinedTextField(
                    value = boardTitle,
                    onValueChange = { boardTitle = it },
                    label = { Text("Board Title") },
                    placeholder = { Text("e.g. Brainstorming, System Architecture") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateWhiteboardDialog = false
                        coroutineScope.launch {
                            val doc = repository.createWhiteboard(boardTitle)
                            onOpenDocument(doc)
                        }
                    }
                ) {
                    Text("Create Board")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateWhiteboardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Document Dialog
    showRenameDialog?.let { doc ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Document") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRenameDialog = null
                        coroutineScope.launch {
                            repository.renameDocument(doc.id, renameText)
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DocumentCard(
    document: Document,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Card Header with Type Badge and Favorite Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type Icon Badge
                val (badgeColor, typeIcon, typeLabel) = when (document.type) {
                    DocumentType.NOTEBOOK -> Triple(Color(0xFF2563EB), Icons.Default.MenuBook, "Notebook")
                    DocumentType.PDF -> Triple(Color(0xFFDC2626), Icons.Default.PictureAsPdf, "PDF")
                    DocumentType.WHITEBOARD -> Triple(Color(0xFF0D9488), Icons.Default.Dashboard, "Board")
                    else -> Triple(Color(0xFF6B7280), Icons.Default.Description, "Document")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = typeLabel,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Favorite & Menu
                Row {
                    if (!document.isTrashed) {
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (document.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (document.isFavorite) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (document.isTrashed) {
                                DropdownMenuItem(
                                    text = { Text("Restore Note") },
                                    leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onRestore()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Forever", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onRename()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Document Title
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata: Page count & Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${document.pageCount} ${if (document.pageCount == 1) "page" else "pages"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = dateFormat.format(Date(document.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

