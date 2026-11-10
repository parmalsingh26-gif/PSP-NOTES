package app.inkflow.studio

import app.inkflow.studio.feature.ai.LocalAiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalAiProviderTest {
    private lateinit var aiProvider: LocalAiProvider

    @Before
    fun setUp() {
        aiProvider = LocalAiProvider()
    }

    @Test
    fun testGenerateSummary_normalText() = runBlocking {
        val sampleText = """
            Photosynthesis is the key process by which plants convert light energy into chemical energy.
            Therefore, oxygen is released as a vital byproduct into the atmosphere.
            The main organelle responsible for this reaction is the chloroplast.
        """.trimIndent()

        val result = aiProvider.generateSummary(sampleText)
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertTrue(summary!!.contains("Summary Overview"))
        assertTrue(summary.contains("Photosynthesis"))
    }

    @Test
    fun testGenerateSummary_emptyText() = runBlocking {
        val result = aiProvider.generateSummary("")
        assertTrue(result.isFailure)
    }

    @Test
    fun testGenerateFlashcards_extractedDefinitions() = runBlocking {
        val sampleText = """
            Mitosis is a process of cell duplication.
            Ribosomes are the cellular machinery responsible for protein synthesis.
        """.trimIndent()

        val result = aiProvider.generateFlashcards(sampleText)
        assertTrue(result.isSuccess)
        val cards = result.getOrNull()
        assertNotNull(cards)
        assertEquals(2, cards!!.size)
        assertEquals("What is Mitosis?", cards[0].first)
        assertEquals("What are Ribosomes?", cards[1].first)
    }

    @Test
    fun testAnswerQuestion_foundKeyword() = runBlocking {
        val sampleText = "The speed of light in vacuum is approximately 300000 kilometers per second."
        val result = aiProvider.answerQuestion(sampleText, "What is the speed of light?")
        assertTrue(result.isSuccess)
        val ans = result.getOrNull()
        assertNotNull(ans)
        assertTrue(ans!!.contains("300000 kilometers"))
    }

    @Test
    fun testAnalyzeHandwritingStructure_detectsLinesAndHighlights() {
        val strokes = listOf(
            app.inkflow.studio.domain.model.InkStroke(
                id = "s1",
                tool = app.inkflow.studio.domain.model.ToolType.PEN,
                color = 0xFF000000,
                width = 3f,
                points = listOf(
                    app.inkflow.studio.domain.model.InkPoint(50f, 100f),
                    app.inkflow.studio.domain.model.InkPoint(200f, 102f)
                )
            ),
            app.inkflow.studio.domain.model.InkStroke(
                id = "s2",
                tool = app.inkflow.studio.domain.model.ToolType.HIGHLIGHTER,
                color = 0xFFFACC15,
                width = 16f,
                points = listOf(
                    app.inkflow.studio.domain.model.InkPoint(50f, 100f),
                    app.inkflow.studio.domain.model.InkPoint(200f, 100f)
                )
            )
        )
        val analysis = aiProvider.analyzeHandwritingStructure(strokes, pageNumber = 1)
        assertTrue(analysis.contains("[Page 1 Visual Layout]"))
        assertTrue(analysis.contains("1 highlighted passage"))
    }
}

