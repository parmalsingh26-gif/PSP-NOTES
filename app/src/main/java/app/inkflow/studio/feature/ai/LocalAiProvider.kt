package app.inkflow.studio.feature.ai

import app.inkflow.studio.domain.model.InkStroke
import app.inkflow.studio.domain.model.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class LocalAiProvider : AiProvider {
    override val providerName: String = "InkFlow Local Engine (On-Device)"
    override val isLocal: Boolean = true

    override suspend fun generateSummary(content: String): Result<String> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Document content is empty."))
        }

        val sentences = content.split(Regex("[.!?\\n]+"))
            .map { it.trim() }
            .filter { it.length > 12 }

        if (sentences.isEmpty()) {
            return@withContext Result.success("### Summary Overview\n\n1. ${content.trim()}")
        }

        // Compute word frequencies (excluding stopwords) for extractive TextRank-style scoring
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "are", "was", "were",
            "have", "has", "had", "into", "unto", "which", "their", "about", "there"
        )
        val wordFreq = mutableMapOf<String, Int>()
        for (s in sentences) {
            val words = s.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 3 && !stopWords.contains(it) }
            for (w in words) {
                wordFreq[w] = (wordFreq[w] ?: 0) + 1
            }
        }

        val domainKeywords = listOf(
            "important", "key", "result", "definition", "note", "therefore",
            "concept", "principle", "formula", "main", "conclusion", "theorem", "process"
        )

        val scoredSentences = sentences.mapIndexed { idx, sentence ->
            var score = 0
            val lower = sentence.lowercase(Locale.ROOT)
            for (kw in domainKeywords) {
                if (lower.contains(kw)) score += 4
            }
            val words = lower.split(Regex("\\W+")).filter { it.length > 3 && !stopWords.contains(it) }
            for (w in words) {
                score += (wordFreq[w] ?: 0)
            }
            if (sentence.length in 35..160) score += 3
            if (idx == 0) score += 2 // Title / topic sentence bonus
            Triple(idx, sentence, score)
        }

        // Pick top sentences and preserve their original document reading order
        val topSentences = scoredSentences
            .sortedByDescending { it.third }
            .take(5)
            .sortedBy { it.first }
            .map { it.second }

        val topKeywords = wordFreq.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key.replaceFirstChar { c -> c.uppercase() } }

        val summaryBuilder = StringBuilder()
        summaryBuilder.append("### Summary Overview\n\n")
        for ((idx, s) in topSentences.withIndex()) {
            summaryBuilder.append("${idx + 1}. $s.\n")
        }
        if (topKeywords.isNotEmpty()) {
            summaryBuilder.append("\n**Key Topics:** ${topKeywords.joinToString(", ")}")
        }

        Result.success(summaryBuilder.toString())
    }

    override suspend fun generateFlashcards(content: String): Result<List<Pair<String, String>>> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Document content is empty."))
        }

        val cards = mutableListOf<Pair<String, String>>()
        val lines = content.split(Regex("[\\n.]+")).map { it.trim() }.filter { it.length > 15 }

        for (line in lines) {
            // Strip optional "[Page X] " prefix when forming question subject
            val cleanLine = line.replace(Regex("^\\[Page \\d+\\]\\s*"), "").trim()
            when {
                cleanLine.contains(" is ", ignoreCase = true) -> {
                    val parts = cleanLine.split(Regex(" is ", RegexOption.IGNORE_CASE), limit = 2)
                    if (parts.size == 2 && parts[0].length in 2..60 && parts[1].isNotBlank()) {
                        cards.add(Pair("What is ${parts[0].trim()}?", parts[1].trim()))
                    }
                }
                cleanLine.contains(" are ", ignoreCase = true) -> {
                    val parts = cleanLine.split(Regex(" are ", RegexOption.IGNORE_CASE), limit = 2)
                    if (parts.size == 2 && parts[0].length in 2..60 && parts[1].isNotBlank()) {
                        cards.add(Pair("What are ${parts[0].trim()}?", parts[1].trim()))
                    }
                }
                cleanLine.contains(" - ") -> {
                    val parts = cleanLine.split(" - ", limit = 2)
                    if (parts.size == 2 && parts[0].length in 2..50 && parts[1].isNotBlank()) {
                        cards.add(Pair(parts[0].trim(), parts[1].trim()))
                    }
                }
                cleanLine.contains(":", ignoreCase = true) -> {
                    val parts = cleanLine.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].length in 2..50 && parts[1].trim().isNotBlank()) {
                        cards.add(Pair(parts[0].trim(), parts[1].trim()))
                    }
                }
            }
        }

        if (cards.isEmpty()) {
            for ((idx, line) in lines.take(4).withIndex()) {
                val clean = line.replace(Regex("^\\[Page \\d+\\]\\s*"), "").trim()
                cards.add(Pair("Explain Concept #${idx + 1}", clean))
            }
        }

        Result.success(cards.distinctBy { it.first })
    }

    override suspend fun answerQuestion(content: String, question: String): Result<String> = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext Result.success("This document contains no text notes or annotations to reference yet.")
        }
        val qTokens = question.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 }
        val sentences = content.split(Regex("[.!?\\n]+")).map { it.trim() }.filter { it.isNotBlank() }

        val matches = sentences.map { s ->
            val sLower = s.lowercase(Locale.ROOT)
            val matchCount = qTokens.count { sLower.contains(it) }
            Pair(s, matchCount)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        val answer = if (matches.isNotEmpty()) {
            val top = matches.take(2).joinToString(" · ") { it.first }
            "Based on your notes: \"$top.\""
        } else {
            "No exact match for \"$question\" was found in your typed notes. Try adding a text note or keyword on the relevant page."
        }

        Result.success(answer)
    }

    /**
     * Analyzes on-page vector strokes to detect handwritten lines, highlighted regions, and diagrams.
     */
    fun analyzeHandwritingStructure(strokes: List<InkStroke>, pageNumber: Int): String {
        if (strokes.isEmpty()) return ""
        val writingStrokes = strokes.filter { it.tool == ToolType.PEN || it.tool == ToolType.BALLPOINT || it.tool == ToolType.PENCIL }
        val highlights = strokes.filter { it.tool == ToolType.HIGHLIGHTER }
        val shapes = strokes.filter { it.tool == ToolType.SHAPE }

        // Group writing strokes into horizontal lines by vertical centroid proximity (within 28pt)
        val sortedByY = writingStrokes.map { (it.bounds.top + it.bounds.bottom) / 2f }.sorted()
        var lineClusters = 0
        var lastY = -100f
        for (y in sortedByY) {
            if (abs(y - lastY) > 26f) {
                lineClusters += 1
                lastY = y
            }
        }

        val parts = mutableListOf<String>()
        if (lineClusters > 0) parts.add("$lineClusters handwritten line(s) (${writingStrokes.size} strokes)")
        if (highlights.isNotEmpty()) parts.add("${highlights.size} highlighted passage(s)")
        if (shapes.isNotEmpty()) parts.add("${shapes.size} diagram shape(s)")

        return if (parts.isEmpty()) "" else "[Page $pageNumber Visual Layout] Contains ${parts.joinToString(", ")}."
    }
}
