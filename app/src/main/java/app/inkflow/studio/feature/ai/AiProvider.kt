package app.inkflow.studio.feature.ai

interface AiProvider {
    val providerName: String
    val isLocal: Boolean

    suspend fun generateSummary(content: String): Result<String>
    suspend fun generateFlashcards(content: String): Result<List<Pair<String, String>>>
    suspend fun answerQuestion(content: String, question: String): Result<String>
}

