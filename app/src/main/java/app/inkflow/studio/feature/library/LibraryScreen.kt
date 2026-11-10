package app.inkflow.studio.feature.library

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.InkFlowApplication
import app.inkflow.studio.core.design.*
import app.inkflow.studio.data.repository.DocumentRepository
import app.inkflow.studio.domain.model.Document
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.domain.model.Folder
import app.inkflow.studio.domain.model.PageTemplate
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog States
    var showCreateNotebookDialog by remember { mutableStateOf(false) }
    var showCreateWhiteboardDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Document?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showTagsDialog by remember { mutableStateOf<Document?>(null) }
    var tagsInputText by remember { mutableStateOf("") }
    var showMoveFolderDialog by remember { mutableStateOf<Document?>(null) }

    // Helper to extract real filename from Uri
    fun resolvePdfTitle(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        cursor.getString(idx)
                            ?.removeSuffix(".pdf")
                            ?.removeSuffix(".PDF")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    } else null
                } else null
            } ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.removeSuffix(".pdf")
                ?.removeSuffix(".PDF")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Imported PDF"
        } catch (_: Exception) {
            "Imported PDF"
        }
    }

    // PDF Picker Launcher with safe error handling
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val extractedTitle = resolvePdfTitle(it)
            coroutineScope.launch {
                try {
                    val doc = repository.createPdfDocument(
                        title = extractedTitle,
                        uri = it
                    )
                    onOpenDocument(doc)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Could not import PDF: ${e.localizedMessage ?: "Invalid file"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val app = context.applicationContext as? InkFlowApplication
    val backupManager = app?.backupManager
    var showSettingsDialog by remember { mutableStateOf(false) }
    var pendingBackupFile by remember { mutableStateOf<File?>(null) }

    val saveBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { destUri: Uri? ->
        val srcFile = pendingBackupFile
        if (destUri != null && srcFile != null && srcFile.exists()) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(srcFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    Toast.makeText(context, "Backup exported (${srcFile.length() / 1024} KB)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreBackupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val tempBackup = File(context.cacheDir, "temp_restore.json")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(tempBackup).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val restored = backupManager?.restoreBackup(tempBackup) ?: false
                    if (restored) {
                        Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to restore backup (Checksum or schema mismatch)", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Reactive Document & Folder Lists
    val activeDocs by repository.getAllActiveDocuments().collectAsState(initial = emptyList())
    val favoriteDocs by repository.getFavoriteDocuments().collectAsState(initial = emptyList())
    val trashedDocs by repository.getTrashedDocuments().collectAsState(initial = emptyList())
    val folders by repository.getAllFolders().collectAsState(initial = emptyList())

    val filteredDocs = remember(activeDocs, favoriteDocs, trashedDocs, selectedFilter, selectedFolderId, searchQuery) {
        val baseList = when (selectedFilter) {
            LibraryFilter.ALL -> activeDocs
            LibraryFilter.NOTEBOOKS -> activeDocs.filter { it.type == DocumentType.NOTEBOOK }
            LibraryFilter.PDFS -> activeDocs.filter { it.type == DocumentType.PDF }
            LibraryFilter.WHITEBOARDS -> activeDocs.filter { it.type == DocumentType.WHITEBOARD }
            LibraryFilter.FAVORITES -> favoriteDocs
            LibraryFilter.TRASH -> trashedDocs
        }
        val folderFiltered = if (selectedFolderId != null && selectedFilter != LibraryFilter.TRASH) {
            baseList.filter { it.folderId == selectedFolderId }
        } else {
            baseList
        }
        if (searchQuery.isBlank()) {
            folderFiltered
        } else {
            folderFiltered.filter {
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
                selected = selectedFilter == LibraryFilter.PDFS,
                onClick = { selectedFilter = LibraryFilter.PDFS },
                icon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = "PDFs") },
                label = { Text("PDFs", fontSize = 11.sp) }
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
            NavigationRailItem(
                selected = showSettingsDialog,
                onClick = { showSettingsDialog = true },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                label = { Text("Settings", fontSize = 11.sp) }
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

                        // Action Buttons: New Folder, Import PDF, Whiteboard, New Notebook
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showCreateFolderDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Folder")
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(context, "No PDF picker available on device", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import PDF")
                            }

                            OutlinedButton(
                                onClick = { showCreateWhiteboardDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Whiteboard")
                            }

                            Button(
                                onClick = { showCreateNotebookDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Notebook")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Search & Type Filter Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search by title or #tag…") },
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

                    // Folder Filter Bar (if user created folders)
                    if (folders.isNotEmpty() && selectedFilter != LibraryFilter.TRASH) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = selectedFolderId == null,
                                onClick = { selectedFolderId = null },
                                label = { Text("All Folders") }
                            )
                            folders.forEach { folder ->
                                FilterChip(
                                    selected = selectedFolderId == folder.id,
                                    onClick = {
                                        selectedFolderId = if (selectedFolderId == folder.id) null else folder.id
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(folder.color.toULong()))
                                        )
                                    },
                                    label = { Text(folder.name) }
                                )
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
                                imageVector = if (selectedFilter == LibraryFilter.TRASH) Icons.Outlined.DeleteSweep else Icons.AutoMirrored.Outlined.NoteAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = if (selectedFilter == LibraryFilter.TRASH) "Trash is empty" else "No notes found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selectedFilter == LibraryFilter.PDFS) {
                                Button(
                                    onClick = {
                                        try {
                                            pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No PDF picker available", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import Your First PDF")
                                }
                            } else if (selectedFilter != LibraryFilter.TRASH) {
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
                            val folderName = remember(doc.folderId, folders) {
                                folders.find { it.id == doc.folderId }?.name
                            }
                            DocumentCard(
                                document = doc,
                                folderName = folderName,
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
                                onDuplicate = {
                                    coroutineScope.launch {
                                        val copy = repository.duplicateDocument(doc.id)
                                        if (copy != null) {
                                            Toast.makeText(context, "Duplicated \"${copy.title}\"", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onEditTags = {
                                    tagsInputText = doc.tags.joinToString(", ")
                                    showTagsDialog = doc
                                },
                                onMoveToFolder = {
                                    showMoveFolderDialog = doc
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

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        val folderColors = listOf(0xFF2563EB, 0xFF0D9488, 0xFF7C3AED, 0xFFDC2626, 0xFFD97706)
        var selectedColor by remember { mutableLongStateOf(folderColors.first()) }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Folder Name") },
                        placeholder = { Text("e.g. Semester 1, Client Projects") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Folder Color:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        folderColors.forEach { colorHex ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex.toULong()))
                                    .border(
                                        width = if (selectedColor == colorHex) 3.dp else 1.dp,
                                        color = if (selectedColor == colorHex) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorHex }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            showCreateFolderDialog = false
                            coroutineScope.launch {
                                val folder = repository.createFolder(folderName, color = selectedColor)
                                selectedFolderId = folder.id
                            }
                        }
                    }
                ) {
                    Text("Create Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
                                label = { Text(tmpl.name.replace("_", " ").lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }) }
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
                            val doc = repository.createNotebook(newTitle, selectedTemplate, folderId = selectedFolderId)
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
                            val doc = repository.createWhiteboard(boardTitle, folderId = selectedFolderId)
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

    // Edit Tags Dialog
    showTagsDialog?.let { doc ->
        AlertDialog(
            onDismissRequest = { showTagsDialog = null },
            title = { Text("Edit Document Tags") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Separate tags with commas (e.g. exam, calculus, important):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = tagsInputText,
                        onValueChange = { tagsInputText = it },
                        placeholder = { Text("exam, lecture, review") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedTags = tagsInputText
                            .split(",")
                            .map { it.trim().removePrefix("#") }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        showTagsDialog = null
                        coroutineScope.launch {
                            repository.updateDocumentTags(doc.id, parsedTags)
                        }
                    }
                ) {
                    Text("Save Tags")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagsDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Move to Folder Dialog
    showMoveFolderDialog?.let { doc ->
        AlertDialog(
            onDismissRequest = { showMoveFolderDialog = null },
            title = { Text("Move \"${doc.title}\" to Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showMoveFolderDialog = null
                            coroutineScope.launch {
                                repository.moveDocumentToFolder(doc.id, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("No Folder (Root Library)")
                    }
                    folders.forEach { folder ->
                        Button(
                            onClick = {
                                showMoveFolderDialog = null
                                coroutineScope.launch {
                                    repository.moveDocumentToFolder(doc.id, folder.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (doc.folderId == folder.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (doc.folderId == folder.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.name)
                        }
                    }
                    if (folders.isEmpty()) {
                        Text(
                            "No custom folders created yet. Use the 'Folder' button in the top bar to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveFolderDialog = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Settings & Backup Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("InkFlow Settings & Backup")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "InkFlow Studio v2.0 Pro (Native Offline)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your notebooks, vector ink strokes, text annotations, cropped images, whiteboards, and SM-2 study cards are stored 100% locally in SQLite with SHA-256 verified backups.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    Text("Full Data Portability & Safety:", fontWeight = FontWeight.SemiBold)

                    // Export Full Backup Button (with SAF file save)
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val backupFile = backupManager?.createBackup()
                                    if (backupFile != null && backupFile.exists()) {
                                        pendingBackupFile = backupFile
                                        saveBackupLauncher.launch(backupFile.name)
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Full Library Backup (JSON)")
                    }

                    // Restore Backup Button
                    OutlinedButton(
                        onClick = {
                            restoreBackupPickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Library from Backup")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun DocumentCard(
    document: Document,
    folderName: String? = null,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onEditTags: () -> Unit,
    onMoveToFolder: () -> Unit,
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
                    DocumentType.NOTEBOOK -> Triple(Color(0xFF2563EB), Icons.AutoMirrored.Filled.MenuBook, "Notebook")
                    DocumentType.PDF -> Triple(Color(0xFFDC2626), Icons.Default.PictureAsPdf, "PDF")
                    DocumentType.WHITEBOARD -> Triple(Color(0xFF0D9488), Icons.Default.Dashboard, "Board")
                    else -> Triple(Color(0xFF6B7280), Icons.Default.Description, "Document")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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

                    if (folderName != null) {
                        Text(
                            text = folderName,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
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
                                    text = { Text("Duplicate") },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDuplicate()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit Tags") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onEditTags()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to Folder") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onMoveToFolder()
                                    }
                                )
                                HorizontalDivider()
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

            Spacer(modifier = Modifier.height(14.dp))

            // Document Title
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Tags Row
            if (document.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    document.tags.forEach { tag ->
                        Text(
                            text = "#$tag",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
