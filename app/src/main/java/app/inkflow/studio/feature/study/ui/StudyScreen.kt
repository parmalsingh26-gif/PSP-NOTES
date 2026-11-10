package app.inkflow.studio.feature.study.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkflow.studio.data.repository.StudyRepository
import app.inkflow.studio.domain.model.ReviewRating
import app.inkflow.studio.domain.model.StudyCard
import kotlinx.coroutines.launch

@Composable
fun StudyScreen(
    studyRepository: StudyRepository,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val allCards by studyRepository.getAllCards().collectAsState(initial = emptyList())

    var isPracticeMode by remember { mutableStateOf(false) }
    var currentCardIndex by remember { mutableIntStateOf(0) }
    var isAnswerRevealed by remember { mutableStateOf(false) }

    var showAddCardDialog by remember { mutableStateOf(false) }
    var newFront by remember { mutableStateOf("") }
    var newBack by remember { mutableStateOf("") }

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
                            if (isPracticeMode) isPracticeMode = false else onNavigateBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isPracticeMode) "Flashcard Practice" else "Study Sets & Spaced Repetition",
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

                    if (!isPracticeMode && allCards.isNotEmpty()) {
                        Button(
                            onClick = {
                                isPracticeMode = true
                                currentCardIndex = 0
                                isAnswerRevealed = false
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Review (${allCards.size})")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isPracticeMode) {
                FloatingActionButton(
                    onClick = { showAddCardDialog = true },
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
            if (isPracticeMode) {
                // Practice Session Active
                if (allCards.isEmpty() || currentCardIndex >= allCards.size) {
                    // Session Complete
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
                        Text("You reviewed all cards for today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { isPracticeMode = false }) {
                            Text("Return to Decks")
                        }
                    }
                } else {
                    val currentCard = allCards[currentCardIndex]

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Progress bar
                        LinearProgressIndicator(
                            progress = { (currentCardIndex + 1).toFloat() / allCards.size },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Flashcard Surface
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
                                        text = if (isAnswerRevealed) "ANSWER" else "QUESTION",
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

                        // Rating Buttons (Again, Hard, Good, Easy)
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
            } else {
                // Deck Card List
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
                        Button(onClick = { showAddCardDialog = true }) {
                            Text("Add First Flashcard")
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(allCards, key = { it.id }) { card ->
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
                                            "Interval: ${card.intervalDays} days · Repetitions: ${card.repetitionCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
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

    // Add Flashcard Dialog
    if (showAddCardDialog) {
        AlertDialog(
            onDismissRequest = { showAddCardDialog = false },
            title = { Text("New Study Card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newFront,
                        onValueChange = { newFront = it },
                        label = { Text("Front (Prompt / Question)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newBack,
                        onValueChange = { newBack = it },
                        label = { Text("Back (Answer / Explanation)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFront.isNotBlank() && newBack.isNotBlank()) {
                            showAddCardDialog = false
                            coroutineScope.launch {
                                studyRepository.saveCard("default_deck", newFront, newBack)
                                newFront = ""
                                newBack = ""
                            }
                        }
                    }
                ) {
                    Text("Add Card")
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

