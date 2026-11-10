package app.inkflow.studio.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class LocalAiProvider : AiProvider {
    override val providerName: String = "InkFlow Local Engine (On-Device)"
    override val isLocal: Boolean = true

    override suspend fun generateSummary(content: String): Result<String> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Document content is empty."))
        }

        val sentences = content.split(Regex("[.!?\\n]+"))
            .map { it.trim() }
            .filter { it.length > 15 }

        if (sentences.isEmpty()) {
            return@withContext Result.success("Note has minimal text: \"${content.take(120)}\"")
        }

        // Sentence scoring based on length, keywords, and structural words
        val scoredSentences = sentences.map { sentence ->
            var score = 0
            val lower = sentence.lowercase(Locale.ROOT)
            val keywords = listOf("important", "key", "result", "definition", "note", "therefore", "concept", "principle", "formula", "main")
            for (kw in keywords) {
                if (lower.contains(kw)) score += 3
            }
            if (sentence.length in 40..150) score += 2
            Pair(sentence, score)
        }

        val topSentences = scoredSentences.sortedByDescending { it.second }.take(4).map { it.first }

        val summaryBuilder = StringBuilder()
        summaryBuilder.append("### Summary Overview\n\n")
        for ((idx, s) in topSentences.withIndex()) {
            summaryBuilder.append("${idx + 1}. $s.\n")
        }
        summaryBuilder.append("\n**Key Takeaway:** Foundational concepts extracted directly from your handwritten and typed notes.")

        Result.success(summaryBuilder.toString())
    }

    override suspend fun generateFlashcards(content: String): Result<List<Pair<String, String>>> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Document content is empty."))
        }

        val cards = mutableListOf<Pair<String, String>>()
        val lines = content.split(Regex("[\\n.]+")).map { it.trim() }.filter { it.length > 20 }

        for (line in lines) {
            when {
                // "X is Y" or "X means Y" pattern
                line.contains(" is ", ignoreCase = true) -> {
                    val parts = line.split(Regex(" is ", RegexOption.IGNORE_CASE), limit = 2)
                    if (parts.size == 2 && parts[0].length in 3..50) {
                        cards.add(Pair("What is ${parts[0]}?", parts[1]))
                    }
                }
                line.contains(" are ", ignoreCase = true) -> {
                    val parts = line.split(Regex(" are ", RegexOption.IGNORE_CASE), limit = 2)
                    if (parts.size == 2 && parts[0].length in 3..50) {
                        cards.add(Pair("What are ${parts[0]}?", parts[1]))
                    }
                }
                line.contains(":", ignoreCase = true) -> {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].length in 3..40) {
                        cards.add(Pair(parts[0], parts[1].trim()))
                    }
                }
            }
        }

        // Fallback: If no patterns matched, turn lines into general concept review cards
        if (cards.isEmpty()) {
            for ((idx, line) in lines.take(3).withIndex()) {
                cards.add(Pair("Key Concept #${idx + 1}", line))
            }
        }

        Result.success(cards)
    }

    override suspend fun answerQuestion(content: String, question: String): Result<String> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.success("This document contains no text to reference.")
        }
        val qTokens = question.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 3 }
        val sentences = content.split(Regex("[.!?\\n]+")).map { it.trim() }.filter { it.isNotBlank() }

        var bestSentence: String? = null
        var maxMatch = 0

        for (s in sentences) {
            val sLower = s.lowercase(Locale.ROOT)
            val matchCount = qTokens.count { sLower.contains(it) }
            if (matchCount > maxMatch) {
                maxMatch = matchCount
                bestSentence = s
            }
        }

        val answer = if (bestSentence != null && maxMatch > 0) {
            "Based on your notes: \"$bestSentence.\""
        } else {
            "I could not find a direct answer to \"$question\" in this note. Try rephrasing or searching with specific keywords."
        }

        Result.success(answer)
    }
}

