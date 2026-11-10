package app.inkflow.studio.feature.study.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.data.repository.StudyRepository
import app.inkflow.studio.domain.model.ReviewRating
import app.inkflow.studio.domain.model.StudyCard
import kotlinx.coroutines.launch
import java.util.Locale

enum class StudySessionMode {
    NONE,
    FLIP_REVIEW,
    QUIZ_MODE
}

@Composable
fun StudyScreen(
    studyRepository: StudyRepository,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val allCards by studyRepository.getAllCards().collectAsState(initial = emptyList())

    var sessionMode by remember { mutableStateOf(StudySessionMode.NONE) }
    var filterDueOnly by remember { mutableStateOf(false) }
    var currentCardIndex by remember { mutableIntStateOf(0) }
    var isAnswerRevealed by remember { mutableStateOf(false) }

    // Quiz Mode state
    var selectedQuizOption by remember { mutableStateOf<String?>(null) }
    var quizScore by remember { mutableIntStateOf(0) }

    // Add / Edit Card Dialog State
    var showAddCardDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<StudyCard?>(null) }
    var cardFrontText by remember { mutableStateOf("") }
    var cardBackText by remember { mutableStateOf("") }

    val now = System.currentTimeMillis()
    val dueCards = remember(allCards, now) {
        allCards.filter { it.nextReviewTimestamp <= now }
    }
    val masteredCards = remember(allCards) {
        allCards.count { it.intervalDays >= 7 }
    }
    val averageEase = remember(allCards) {
        if (allCards.isEmpty()) 2.5f else allCards.map { it.easeFactor }.average().toFloat()
    }

    val displayedCards = remember(allCards, dueCards, filterDueOnly) {
        if (filterDueOnly) dueCards else allCards
    }

    val activeSessionDeck = remember(sessionMode, displayedCards, allCards) {
        if (displayedCards.isNotEmpty()) displayedCards else allCards
    }

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
                        IconButton(onClick = {
                            if (sessionMode != StudySessionMode.NONE) {
                                sessionMode = StudySessionMode.NONE
                            } else {
                                onNavigateBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when (sessionMode) {
                                    StudySessionMode.FLIP_REVIEW -> "Flashcard Spaced Repetition"
                                    StudySessionMode.QUIZ_MODE -> "Interactive Multiple-Choice Quiz"
                                    StudySessionMode.NONE -> "Study Sets & Spaced Repetition"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "SuperMemo SM-2 Retention Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    if (sessionMode == StudySessionMode.NONE && allCards.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    sessionMode = StudySessionMode.QUIZ_MODE
                                    currentCardIndex = 0
                                    selectedQuizOption = null
                                    quizScore = 0
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Quiz, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Quiz Mode")
                            }

                            Button(
                                onClick = {
                                    sessionMode = StudySessionMode.FLIP_REVIEW
                                    currentCardIndex = 0
                                    isAnswerRevealed = false
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Start Review (${activeSessionDeck.size})")
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (sessionMode == StudySessionMode.NONE) {
                FloatingActionButton(
                    onClick = {
                        editingCard = null
                        cardFrontText = ""
                        cardBackText = ""
                        showAddCardDialog = true
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Flashcard")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            when (sessionMode) {
                StudySessionMode.FLIP_REVIEW -> {
                    if (activeSessionDeck.isEmpty() || currentCardIndex >= activeSessionDeck.size) {
                        SessionCompleteView(
                            subtitle = "You reviewed all scheduled cards for this session.",
                            onReturn = { sessionMode = StudySessionMode.NONE }
                        )
                    } else {
                        val currentCard = activeSessionDeck[currentCardIndex]

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            LinearProgressIndicator(
                                progress = { (currentCardIndex + 1).toFloat() / activeSessionDeck.size },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clickable { isAnswerRevealed = !isAnswerRevealed },
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = if (isAnswerRevealed) "ANSWER" else "QUESTION (${currentCardIndex + 1}/${activeSessionDeck.size})",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAnswerRevealed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = if (isAnswerRevealed) currentCard.back else currentCard.front,
                                            style = MaterialTheme.typography.headlineMedium,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        if (!isAnswerRevealed) {
                                            Text(
                                                text = "(Tap anywhere to flip card)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            if (isAnswerRevealed) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                studyRepository.processReview(currentCard, ReviewRating.AGAIN)
                                                currentCardIndex += 1
                                                isAnswerRevealed = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Again (1d)")
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                studyRepository.processReview(currentCard, ReviewRating.HARD)
                                                currentCardIndex += 1
                                                isAnswerRevealed = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Hard")
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                studyRepository.processReview(currentCard, ReviewRating.GOOD)
                                                currentCardIndex += 1
                                                isAnswerRevealed = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Good")
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                studyRepository.processReview(currentCard, ReviewRating.EASY)
                                                currentCardIndex += 1
                                                isAnswerRevealed = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Easy")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { isAnswerRevealed = true },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Reveal Answer")
                                }
                            }
                        }
                    }
                }

                StudySessionMode.QUIZ_MODE -> {
                    if (activeSessionDeck.isEmpty() || currentCardIndex >= activeSessionDeck.size) {
                        SessionCompleteView(
                            subtitle = "Quiz Score: $quizScore / ${activeSessionDeck.size} correct!",
                            onReturn = { sessionMode = StudySessionMode.NONE }
                        )
                    } else {
                        val currentCard = activeSessionDeck[currentCardIndex]
                        val options = remember(currentCard, allCards) {
                            val otherBacks = allCards
                                .filter { it.id != currentCard.id && it.back != currentCard.back }
                                .map { it.back }
                                .distinct()
                                .shuffled()
                                .take(3)
                            val fallbackDistractors = listOf(
                                "None of the above concepts apply",
                                "Inverse proportional relationship only",
                                "Constant baseline independent of variables"
                            )
                            val distractors = (otherBacks + fallbackDistractors).distinct()
                                .filter { it != currentCard.back }
                                .take(3)
                            (distractors + currentCard.back).shuffled()
                        }

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { (currentCardIndex + 1).toFloat() / activeSessionDeck.size },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Question ${currentCardIndex + 1} of ${activeSessionDeck.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Score: $quizScore",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = currentCard.front,
                                        style = MaterialTheme.typography.headlineSmall,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                options.forEach { option ->
                                    val isSelected = selectedQuizOption == option
                                    val isCorrect = option == currentCard.back
                                    val borderColor = when {
                                        selectedQuizOption == null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        isCorrect -> Color(0xFF16A34A)
                                        isSelected -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    }
                                    val bgColor = when {
                                        selectedQuizOption == null -> MaterialTheme.colorScheme.surface
                                        isCorrect -> Color(0xFF16A34A).copy(alpha = 0.12f)
                                        isSelected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = selectedQuizOption == null) {
                                                selectedQuizOption = option
                                                val right = option == currentCard.back
                                                if (right) quizScore += 1
                                                coroutineScope.launch {
                                                    studyRepository.processReview(
                                                        currentCard,
                                                        if (right) ReviewRating.GOOD else ReviewRating.AGAIN
                                                    )
                                                }
                                            },
                                        shape = RoundedCornerShape(14.dp),
                                        color = bgColor,
                                        border = BorderStroke(1.5.dp, borderColor)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = option,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            if (selectedQuizOption != null) {
                                                if (isCorrect) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A))
                                                } else if (isSelected) {
                                                    Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (selectedQuizOption != null) {
                                Button(
                                    onClick = {
                                        selectedQuizOption = null
                                        currentCardIndex += 1
                                    },
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(if (currentCardIndex + 1 < activeSessionDeck.size) "Next Question" else "Finish Quiz")
                                }
                            }
                        }
                    }
                }

                StudySessionMode.NONE -> {
                    if (allCards.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No flashcards created yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Create cards manually or use \"Ask InkFlow\" in any note to auto-generate study sets.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                editingCard = null
                                cardFrontText = ""
                                cardBackText = ""
                                showAddCardDialog = true
                            }) {
                                Text("Add First Flashcard")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // SM-2 Retention Statistics Dashboard
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatSummaryCard(
                                    label = "Total Cards",
                                    value = "${allCards.size}",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                StatSummaryCard(
                                    label = "Due Now",
                                    value = "${dueCards.size}",
                                    color = Color(0xFFD97706),
                                    modifier = Modifier.weight(1f)
                                )
                                StatSummaryCard(
                                    label = "Mastered (≥7d)",
                                    value = "$masteredCards",
                                    color = Color(0xFF16A34A),
                                    modifier = Modifier.weight(1f)
                                )
                                StatSummaryCard(
                                    label = "Avg Ease",
                                    value = String.format(Locale.US, "%.2f", averageEase),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Filter Chips
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = !filterDueOnly,
                                    onClick = { filterDueOnly = false },
                                    label = { Text("All Cards (${allCards.size})") }
                                )
                                FilterChip(
                                    selected = filterDueOnly,
                                    onClick = { filterDueOnly = true },
                                    label = { Text("Due for Review (${dueCards.size})") }
                                )
                            }

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(displayedCards, key = { it.id }) { card ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Q: ${card.front}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("A: ${card.back}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Interval: ${card.intervalDays}d · Reps: ${card.repetitionCount} · Ease: ${String.format(Locale.US, "%.2f", card.easeFactor)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }

                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        editingCard = card
                                                        cardFrontText = card.front
                                                        cardBackText = card.back
                                                        showAddCardDialog = true
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit Card", tint = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            studyRepository.deleteCard(card.id)
                                                        }
                                                    }
                                                ) {
                                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Card", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add or Edit Flashcard Dialog
    if (showAddCardDialog) {
        AlertDialog(
            onDismissRequest = { showAddCardDialog = false },
            title = { Text(if (editingCard != null) "Edit Study Card" else "New Study Card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = cardFrontText,
                        onValueChange = { cardFrontText = it },
                        label = { Text("Front (Prompt / Question)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cardBackText,
                        onValueChange = { cardBackText = it },
                        label = { Text("Back (Answer / Explanation)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cardFrontText.isNotBlank() && cardBackText.isNotBlank()) {
                            val existing = editingCard
                            showAddCardDialog = false
                            coroutineScope.launch {
                                if (existing != null) {
                                    studyRepository.updateCard(
                                        existing.copy(
                                            front = cardFrontText.trim(),
                                            back = cardBackText.trim()
                                        )
                                    )
                                } else {
                                    studyRepository.saveCard("default_deck", cardFrontText.trim(), cardBackText.trim())
                                }
                                editingCard = null
                                cardFrontText = ""
                                cardBackText = ""
                            }
                        }
                    }
                ) {
                    Text(if (editingCard != null) "Save Changes" else "Add Card")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatSummaryCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionCompleteView(
    subtitle: String,
    onReturn: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Session Complete!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onReturn) {
            Text("Return to Decks")
        }
    }
}
